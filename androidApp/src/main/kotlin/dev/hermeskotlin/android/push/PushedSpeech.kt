package dev.hermeskotlin.android.push

import android.util.Log
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.speak.PushedSpeechTargets
import dev.hermeskotlin.core.speak.SpeakAck
import dev.hermeskotlin.core.speak.SpeakFloor
import dev.hermeskotlin.core.speak.SpeakItem
import dev.hermeskotlin.core.speak.SpeakOutcome
import dev.hermeskotlin.core.speak.SpeakQueue
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The phone's end of server-initiated speech (`worktable-spec.md` §11.3, `docs/board/server-push.md`).
 *
 * A seat's line arrives over the open `/api/ws` connection while a call is on, or as an ntfy `speak` push
 * when the app is backgrounded. Either way it goes through the one [SpeakQueue]: it waits for the floor,
 * is said by the open chained call in the call's own voice, and is acknowledged with how much was heard.
 * With no call, it is a notification — Android does not let a background app start audio, and a seat never
 * surprises the owner.
 */
class PushedSpeech(
    private val connection: GatewayConnection,
    private val notifications: ChatNotifications,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = SpeakQueue()
    /** Ids already handled from either channel, so one line arriving twice is said once. */
    private val seen = LinkedHashSet<String>()
    private var drainJob: Job? = null

    fun start() {
        scope.launch {
            connection.events.collect { event ->
                SpeakItem.fromEvent(event.type, event.payload)?.let(::offer)
                if (event.type in REVISION_TYPES) revision(event.payload)
            }
        }
    }

    /** A `speak` from the herald-push channel (the no-call path). */
    fun offer(message: PushMessage) {
        SpeakItem.from(message)?.let(::offer)
    }

    private fun offer(item: SpeakItem) {
        if (!remember(item.id)) return
        val target = PushedSpeechTargets.current
        // No call: a notification, never surprise audio. The line stays in the seat's thread too.
        if (target?.callActive != true) {
            notifications.postPushedSpeech(item)
            return
        }
        if (!queue.offer(item, nowSeconds())) return
        drain()
    }

    /**
     * Says what the floor allows, one line at a time. Waits (rather than drops) while the owner talks, the
     * app is speaking, or navigation/another app holds audio focus, so a queued line follows a few seconds
     * later instead of interrupting.
     */
    private fun drain() {
        if (drainJob?.isActive == true) return
        drainJob = scope.launch {
            try {
                while (isActive) {
                    val target = PushedSpeechTargets.current
                    // Hanging up: unheard lines stay in the seat's thread, not in a queue the next call inherits.
                    if (target?.callActive != true) {
                        queue.clear().forEach(::acknowledge)
                        break
                    }
                    if (target.personSpeaking || target.busy || !target.audioFree) {
                        delay(FLOOR_POLL_MS)
                        continue
                    }
                    val item = queue.next(
                        SpeakFloor(
                            callActive = true,
                            personSpeaking = target.personSpeaking,
                            audioFree = target.audioFree,
                            busy = target.busy,
                            call = target.callId,
                        ),
                        nowSeconds(),
                    ) ?: break
                    val outcome = try {
                        target.sayPushed(item) { heard -> queue.hear(heard) }
                    } catch (e: CancellationException) {
                        queue.finished(SpeakOutcome.Stopped)?.let(::acknowledge)
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Pushed line failed: ${e::class.simpleName}")
                        SpeakOutcome.Stopped
                    }
                    queue.finished(outcome)?.let(::acknowledge)
                    // A line cut short by barge-in: the owner is talking now, so the next one waits.
                    if (outcome == SpeakOutcome.Stopped) delay(FLOOR_POLL_MS)
                }
            } finally {
                drainJob = null
            }
        }
    }

    /** A correction elsewhere retires queued lines bound to the old revision (`§11.3`). */
    private fun revision(payload: JsonElement?) {
        val rev = (payload as? JsonObject)?.get("rev")
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            ?: return
        queue.advanceTo(rev).forEach(::acknowledge)
    }

    /** Evidence, not permission: tells the seat how much of its line was audible. Never blocks the call. */
    private fun acknowledge(ack: SpeakAck) {
        val client = (connection.state.value as? ConnectionState.Connected)?.client ?: return
        scope.launch {
            runCatching {
                client.notify(ACK_METHOD, buildJsonObject {
                    put("v", 1)
                    put("id", UUID.randomUUID().toString())
                    put("ts", nowSeconds())
                    put("type", "speak_ack")
                    put("speak", ack.id)
                    put("heard", ack.heard)
                    put("outcome", ack.outcome.wire)
                })
            }
        }
    }

    private fun remember(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        return true
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val TAG = "PushedSpeech"
        const val ACK_METHOD = "speak_ack"
        const val FLOOR_POLL_MS = 400L
        const val MAX_SEEN = 128
        val REVISION_TYPES = setOf("intent.revision", "worktable.intent.revision")
    }
}
