package dev.hermeskotlin.wear

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.hermeskotlin.wearlink.HapticFired
import dev.hermeskotlin.wearlink.SeatSpeech

/**
 * The phone's clock against the watch's, learned from a measured round trip and never assumed.
 *
 * The Data Layer has no timestamp of its own, so the watch asks for a [dev.hermeskotlin.wearlink.TilesState] and
 * compares the phone's [dev.hermeskotlin.wearlink.TilesState.sentEpochMs] with its own clock when the answer
 * lands. The phone is taken to have answered halfway through the round trip: the error left is half the trip,
 * which a cue with a 250 ms floor can live with. An anchor goes stale: wall clocks drift apart and can be reset.
 */
class BoardClock(private val clock: () -> Long = { System.currentTimeMillis() }) {

    @Volatile private var offset: Long? = null
    @Volatile private var anchoredAt: Long? = null

    /** The phone's clock is [offset] ahead of the watch's, as this round trip says. */
    @Synchronized
    fun anchor(sentWatchEpochMs: Long, receivedWatchEpochMs: Long, phoneEpochMs: Long): Boolean {
        val roundTrip = receivedWatchEpochMs - sentWatchEpochMs
        if (roundTrip < 0 || roundTrip > MAX_ROUND_TRIP_MS) return false
        offset = phoneEpochMs - (sentWatchEpochMs + roundTrip / 2)
        anchoredAt = receivedWatchEpochMs
        return true
    }

    @Synchronized
    fun calibrated(now: Long = clock()): Boolean {
        val at = anchoredAt ?: return false
        return now - at <= MAX_AGE_MS
    }

    /** The same moment on the phone's clock; null before the first anchor. */
    @Synchronized
    fun toPhone(watchEpochMs: Long): Long? = offset?.let { watchEpochMs + it }

    /** [phoneEpochMs] on the watch's clock; null until a fresh round trip says what the difference is. */
    @Synchronized
    fun toWatch(phoneEpochMs: Long): Long? {
        val at = anchoredAt ?: return null
        if (clock() - at > MAX_AGE_MS) return null
        return offset?.let { phoneEpochMs - it }
    }

    private companion object {
        /** A trip longer than this says more about a queue than about the clocks. */
        const val MAX_ROUND_TRIP_MS = 10_000L

        /** Clocks this far apart in time can drift and be reset; ask again rather than trust the anchor. */
        const val MAX_AGE_MS = 15 * 60_000L
    }
}

/**
 * The 300 ms cue before a seat speaks, ported from worktable-audio's `HapticScheduler` (its laws, not its Expo
 * wrapper): the cue is timed against the predicted audible onset, never the message that announced it; one that
 * can no longer lead by [minLeadMs] is dropped, never fired late "for completeness"; and a cue that went off is
 * reported with the lead it really had, so the phone can tune the next.
 *
 * Nothing here gates speech: [onSpeech] returns at once, and a watch that is late, uncalibrated or absent only
 * loses its own pulse.
 */
class BoardHaptics(
    private val clock: BoardClock,
    private val schedule: (delayMs: Long, fire: () -> Unit) -> Scheduled,
    private val pulse: () -> Unit,
    private val report: (HapticFired) -> Unit,
    private val leadMs: Long = DEFAULT_LEAD_MS,
    private val minLeadMs: Long = DEFAULT_MIN_LEAD_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** A scheduled cue, cancellable while it waits. */
    fun interface Scheduled {
        fun cancel()
    }

    enum class Result {
        /** Waiting for the cue's moment. */
        Scheduled,

        /** Its moment was close but not past: it pulsed at once. */
        FiredNow,

        /** Too late to lead the voice: dropped. */
        DroppedLate,

        /** No round trip has anchored the two clocks: dropped rather than guessed. */
        DroppedUnanchored,
    }

    private enum class State { Pending, Fired, Dropped }

    private class Cue(val turnId: String, val seatId: String, var seq: Int, var onsetWatchMs: Long) {
        var state = State.Pending
        var scheduled: Scheduled? = null
    }

    private val cues = HashMap<String, Cue>()

    /** Turns whose cue already fired: a later correction to their onset must not pulse a second time. */
    private val fired = LinkedHashSet<String>()

    /**
     * A line a seat is about to say: [SeatSpeech.onsetEpochMs] is the phone's prediction, and a later message for
     * the same turn with a newer [SeatSpeech.seq] corrects it. An older one, or one without an onset, changes
     * nothing. Null when nothing happened worth saying.
     */
    fun onSpeech(speech: SeatSpeech): Result? {
        var ready: Cue? = null
        val result: Result? = synchronized(cues) {
            if (speech.turnId in fired) return@synchronized null
            val kept = cues[speech.turnId]
            if (kept != null) {
                // A correction is a newer seq only: a message that overtook it can't move the cue backwards.
                if (speech.seq <= kept.seq) return@synchronized null
                kept.scheduled?.cancel()
                kept.scheduled = null
                kept.state = State.Dropped
                cues.remove(speech.turnId)
            }
            val onset = speech.onsetEpochMs ?: return@synchronized null
            val onsetWatch = clock.toWatch(onset) ?: return@synchronized Result.DroppedUnanchored
            val cue = Cue(speech.turnId, speech.seatId, speech.seq, onsetWatch)
            val moment = now()
            when {
                moment > onsetWatch - minLeadMs -> {
                    cue.state = State.Dropped
                    Result.DroppedLate
                }
                onsetWatch - leadMs <= moment -> {
                    cues[speech.turnId] = cue
                    ready = cue
                    Result.FiredNow
                }
                else -> {
                    cues[speech.turnId] = cue
                    cue.scheduled = schedule(onsetWatch - leadMs - moment) { fire(cue) }
                    Result.Scheduled
                }
            }
        }
        ready?.let(::fire)
        return result
    }

    /** Drops a turn's cue, before it fires or after: nothing to cancel is fine. */
    fun cancel(turnId: String) {
        synchronized(cues) {
            cues.remove(turnId)?.let { cue ->
                cue.scheduled?.cancel()
                cue.scheduled = null
                if (cue.state == State.Pending) cue.state = State.Dropped
            }
        }
    }

    fun cancelAll() {
        synchronized(cues) {
            cues.values.forEach {
                it.scheduled?.cancel()
                it.scheduled = null
                if (it.state == State.Pending) it.state = State.Dropped
            }
            cues.clear()
        }
    }

    /** How many cues wait: for tests, and for a screen that shows the mechanics. */
    fun pending(): Int = synchronized(cues) { cues.values.count { it.state == State.Pending } }

    private fun fire(cue: Cue) {
        var firedNow: HapticFired? = null
        synchronized(cues) {
            if (cue.state != State.Pending || cues[cue.turnId] !== cue) return
            cue.scheduled = null
            val moment = now()
            // The phone corrected the onset past us, or the pulse is already inside the floor: never fire late.
            if (moment > cue.onsetWatchMs - minLeadMs) {
                cue.state = State.Dropped
                cues.remove(cue.turnId)
                return
            }
            pulse()
            cue.state = State.Fired
            cues.remove(cue.turnId)
            rememberFired(cue.turnId)
            firedNow = HapticFired(
                turnId = cue.turnId,
                seatId = cue.seatId,
                firedEpochMs = clock.toPhone(moment) ?: moment,
                leadMs = (cue.onsetWatchMs - moment).coerceAtLeast(0),
            )
        }
        // Outside the lock: sending it can take a moment, and speech never waits on any of it.
        firedNow?.let(report)
    }

    private fun rememberFired(turnId: String) {
        fired += turnId
        if (fired.size > MAX_REMEMBERED) fired.remove(fired.first())
    }

    private companion object {
        const val MAX_REMEMBERED = 32
    }
}

/**
 * The cue on the main thread: one soft pulse through the watch's vibrator, and [report] with what happened.
 * No speech waits on any of it.
 */
fun boardHaptics(context: Context, clock: BoardClock, report: (HapticFired) -> Unit): BoardHaptics {
    val handler = Handler(Looper.getMainLooper())
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    return BoardHaptics(
        clock = clock,
        schedule = { delayMs, fire ->
            val runnable = Runnable { fire() }
            handler.postDelayed(runnable, delayMs)
            BoardHaptics.Scheduled { handler.removeCallbacks(runnable) }
        },
        pulse = {
            runCatching {
                if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createOneShot(SOFT_MS, SOFT_AMPLITUDE))
            }
        },
        report = report,
    )
}

/** The lead a cue aims for. */
const val DEFAULT_LEAD_MS = 300L

/** The least lead a cue may still fire with; past that it's dropped rather than landed on the voice. */
const val DEFAULT_MIN_LEAD_MS = 250L

private const val SOFT_MS = 40L
private const val SOFT_AMPLITUDE = 80
