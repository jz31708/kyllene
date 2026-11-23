package dev.hermeskotlin.core.speak

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.push.PushMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One utterance a seat asked this phone to say aloud, as it arrives on the push channel
 * (`docs/board/server-push.md`). The seat chooses every word; the phone only decides when it is heard.
 */
data class SpeakItem(
    /** The push message's id: what makes a redelivered part a repeat rather than a second one. */
    val id: String,
    val text: String,
    val priority: SpeakPriority = SpeakPriority.WhenReady,
    /** The profile whose voice says it (`board`, `travel-deals`): the seat. */
    val seat: String? = null,
    /** The seat's label for the notification and the watch. */
    val label: String? = null,
    /** The stored session the words belong to, so the call shows the thread they came from. */
    val session: String? = null,
    /** The company and project the words belong to; intent revisions are compared inside one project. */
    val company: String? = null,
    val project: String? = null,
    /** The call the gateway bound this to; null means any active call may say it. */
    val call: String? = null,
    /** The intent whose correction these words answer. Words of one intent never overlap. */
    val intent: String? = null,
    /** What the seat is answering, stable across the parts of one answer. */
    val utterance: String? = null,
    val part: Int = 1,
    val parts: Int = 1,
    /** The intent revision the words belong to, when the gateway says. */
    val rev: Long? = null,
    /** Past this revision the words are no longer true and must not be started. */
    val expiresOnRev: Long? = null,
    /** After this, unix seconds, the words are news rather than an answer. */
    val expires: Long? = null,
    /** When the gateway queued it, unix seconds. */
    val at: Long = 0,
) {
    /** Whether a newer revision has overtaken these words. */
    fun staleAt(revision: Long?): Boolean = expiresOnRev != null && revision != null && expiresOnRev < revision

    /** Whether the moment for these words has passed. */
    fun expiredAt(now: Long?): Boolean = expires != null && now != null && expires <= now

    /** Where this part sits in its answer, for the call screen ("2 of 3"). */
    val place: String? get() = if (parts > 1) "$part of $parts" else null

    companion object {
        /** The utterance inside a `speak` push, or null for a message that isn't one the phone can say. */
        fun from(message: PushMessage): SpeakItem? {
            if (message.type != PushMessage.SPEAK) return null
            val text = message.text?.trim().orEmpty()
            if (text.isEmpty()) return null
            return SpeakItem(
                id = message.id,
                text = text,
                priority = SpeakPriority.of(message.priority),
                seat = message.bot,
                label = message.label,
                session = message.session,
                company = message.company,
                project = message.project,
                call = message.call,
                intent = message.intent,
                utterance = message.utterance,
                part = message.part.coerceAtLeast(1),
                parts = message.parts.coerceAtLeast(1),
                rev = message.rev,
                expiresOnRev = message.expiresOnRev,
                expires = message.expires,
                at = message.ts,
            )
        }

        /**
         * A `speak` that arrived as a gateway event over `/api/ws` rather than as an ntfy push
         * (`worktable-spec.md` §11.3): the event's `type` is the push type and its `payload` is the same
         * plaintext, with `type` left out of the body.
         */
        fun fromEvent(type: String, payload: JsonElement?): SpeakItem? {
            if (type != PushMessage.SPEAK) return null
            val body = payload as? JsonObject ?: return null
            // An id is what makes a redelivery a repeat; without one the item can't be deduped or acknowledged.
            if ((body["id"] as? JsonPrimitive)?.isString != true) return null
            val message = runCatching {
                val fields = buildJsonObject {
                    body.forEach { (key, value) -> if (key != "type") put(key, value) }
                    put("type", PushMessage.SPEAK)
                    // Events carry the server's `ts` on the envelope, not always in the payload.
                    if (body["ts"] == null) put("ts", 0L)
                }
                HermesJson.decodeFromJsonElement(PushMessage.serializer(), fields)
            }.getOrNull() ?: return null
            return from(message)
        }
    }
}

/** How urgent a spoken item is. A person waiting for an answer comes first. */
enum class SpeakPriority(val wire: String) {
    AnswerNow("answer_now"),
    WhenReady("when_ready"),
    Fyi("fyi");

    companion object {
        fun of(wire: String?): SpeakPriority = entries.firstOrNull { it.wire == wire } ?: WhenReady
    }
}

/** How an item left the queue, for the acknowledgement the gateway reads. */
enum class SpeakOutcome(val wire: String) {
    Played("played"),
    Stopped("stopped"),
    Expired("expired"),
    Skipped("skipped"),
}

/**
 * Where the phone is, as the floor rules see it (`worktable-spec.md` §11.3): speech drains only while a call
 * is active, the owner isn't speaking, audio focus is the app's to take, and nothing else is talking.
 */
data class SpeakFloor(
    val callActive: Boolean = false,
    val personSpeaking: Boolean = false,
    val audioFree: Boolean = true,
    /** Something else of the app's is speaking (a read-aloud, another chat's reply). */
    val busy: Boolean = false,
    /** The call's id, when the gateway gave the call one; items bound to another call wait. */
    val call: String? = null,
)

/** What happened to an utterance: [heard] characters were audible before it ended. */
data class SpeakAck(val id: String, val utterance: String?, val heard: Int, val outcome: SpeakOutcome)

/**
 * What of a corrected answer still needs saying. A correction usually repeats most of what was already heard,
 * so only the rest is spoken; a rewrite is spoken whole, from its first difference, after a short cue.
 */
object SpeakText {
    fun remainder(corrected: String, heard: String): String {
        val said = heard.trimEnd()
        if (said.isEmpty()) return corrected
        if (corrected.startsWith(said)) return corrected.removePrefix(said).trimStart()
        return corrected
    }

    /** Whether [corrected] only continues what was heard, so the rest can follow without a cue. */
    fun continues(corrected: String, heard: String): Boolean {
        val said = heard.trimEnd()
        return said.isNotEmpty() && corrected.startsWith(said)
    }
}

/**
 * The phone's end of the speak queue: what seats said out of turn waits here until the floor lets it play.
 *
 * One queue per app. Not thread-safe by itself: the caller keeps it on one thread (the main thread in the app,
 * a test's own thread in tests), which is also what keeps an item from being spoken twice.
 */
class SpeakQueue(private val limit: Int = MAX_ITEMS) {
    private val waiting = mutableListOf<SpeakItem>()
    private val seen = LinkedHashSet<String>()
    private var speaking: SpeakItem? = null
    private var heard = 0
    /** The revision of a frame that named no project: it still retires every project's older lines. */
    private var revision: Long? = null
    /** Revisions are per project; a frame for one never retires another's queued lines. */
    private val projectRevisions = mutableMapOf<String, Long>()

    /** What is being said now, if anything. */
    val playing: SpeakItem? get() = speaking

    /** How many characters of [playing] have been audible. */
    val playedChars: Int get() = heard

    /** What waits, in the order it would be said after the item playing now. */
    val queued: List<SpeakItem> get() = waiting.sortedWith(ORDER)

    /** The intent revision the queue follows when no project was named. */
    val rev: Long? get() = revision

    /** The revision the queue follows for [project]: its own, or a project-less frame's when that is newer. */
    fun revOf(project: String?): Long? =
        if (project == null) revision else listOfNotNull(projectRevisions[project], revision).maxOrNull()

    /**
     * Takes an utterance from the gateway (or from a seat's tool call). A repeat of one already queued, played
     * or overtaken by a newer revision is refused, and so is one that arrives after its own revision.
     */
    fun offer(item: SpeakItem, now: Long? = null): Boolean {
        if (item.text.isBlank()) return false
        if (item.expiredAt(now)) return false
        if (item.id in seen) return false
        if (item.staleAt(revOf(item.project))) return false
        remember(item.id)
        waiting += item
        // The oldest news goes first when a gateway floods the queue: what a person asked for stays.
        while (waiting.size > limit) {
            val fyi = waiting.indexOfLast { it.priority == SpeakPriority.Fyi }
            waiting.removeAt(if (fyi >= 0) fyi else waiting.lastIndex)
        }
        return true
    }

    /**
     * Moves to [revision] because something was corrected. With a [project], only that project's queued lines
     * are compared — a correction in one company never retires another's answer. Without one, today's rule
     * stands: every project's lines expire together.
     *
     * What was already heard of the playing line is kept; it stops after the current sentence. Returns the
     * acknowledgements for the lines that never started.
     */
    fun advanceTo(revision: Long, project: String? = null): List<SpeakAck> {
        if (project == null) {
            val current = this.revision
            if (current != null && revision <= current) return emptyList()
            this.revision = revision
            // A frame that names no project keeps the old, global meaning: it retires every project's old lines.
            projectRevisions.keys.toList().forEach { p ->
                projectRevisions[p] = maxOf(projectRevisions[p] ?: revision, revision)
            }
        } else {
            val current = projectRevisions[project]
            if (current != null && revision <= current) return emptyList()
            projectRevisions[project] = revision
        }
        val retired = waiting.filter { item ->
            (project == null || item.project == project) && item.staleAt(revOf(item.project))
        }
        waiting.removeAll(retired)
        return retired.map { SpeakAck(it.id, it.utterance, 0, SpeakOutcome.Expired) }
    }

    /**
     * A corrected answer that supersedes [id] within the same intent: drops whatever of that intent still
     * waited. The caller speaks the remainder itself ([SpeakText.remainder]) once the correction is current.
     */
    fun supersede(id: String): List<SpeakAck> {
        val previous = waiting.firstOrNull { it.id == id } ?: return emptyList()
        val dropped = waiting.filter { it.intent != null && it.intent == previous.intent }.map {
            SpeakAck(it.id, it.utterance, 0, SpeakOutcome.Expired)
        }
        waiting.removeAll { it.intent != null && it.intent == previous.intent }
        return dropped
    }

    /** What should be said now, or null while the floor is someone else's. */
    fun next(floor: SpeakFloor, now: Long? = null): SpeakItem? {
        if (speaking != null) return null
        if (!floor.callActive || floor.personSpeaking || !floor.audioFree || floor.busy) return null
        val ready = waiting.sortedWith(ORDER).firstOrNull { item ->
            !item.staleAt(revOf(item.project)) && !item.expiredAt(now) &&
                // Words bound to a call wait for that call, and for a phone that knows which call it is on.
                (item.call == null || item.call == floor.call)
        } ?: return null
        waiting.remove(ready)
        speaking = ready
        heard = 0
        return ready
    }

    /** Records how much of the playing item has been audible, up to a known character position. */
    fun hear(chars: Int) {
        val item = speaking ?: return
        heard = chars.coerceIn(heard, item.text.length)
    }

    /**
     * Ends the playing item — it finished, the owner stopped it, or the call ended — and says how much was
     * heard so the gateway can keep a partly heard answer.
     */
    fun finished(outcome: SpeakOutcome = SpeakOutcome.Played): SpeakAck? {
        val item = speaking ?: return null
        speaking = null
        val ack = SpeakAck(item.id, item.utterance, heard, outcome)
        heard = 0
        return ack
    }

    /** Drops everything queued, for hanging up: unheard items stay in the seat's thread, not here. */
    fun clear(): List<SpeakAck> {
        val dropped = waiting.map { SpeakAck(it.id, it.utterance, 0, SpeakOutcome.Expired) }
        waiting.clear()
        return dropped
    }

    private fun remember(id: String) {
        seen += id
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    private companion object {
        const val MAX_ITEMS = 8
        const val MAX_SEEN = 64

        /** Answer now first; otherwise the order they arrived in, a part after the part before it. */
        val ORDER: Comparator<SpeakItem> = compareBy({ it.priority.ordinal }, { it.at }, { it.part }, { it.id })
    }
}
