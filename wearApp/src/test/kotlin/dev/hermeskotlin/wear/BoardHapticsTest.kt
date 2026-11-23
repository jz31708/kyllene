package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.HapticFired
import dev.hermeskotlin.wearlink.SeatSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cue's laws, away from a wrist: it fires a lead before the predicted onset, never inside the floor, never
 * without a measured anchor, and a correction that lands after it fired doesn't pulse twice.
 */
class BoardHapticsTest {

    private var now = 1_000_000L
    private val phoneOffset = 1_000L
    private var pulses = 0
    private val reported = mutableListOf<HapticFired>()
    private val waiting = mutableListOf<Runnable>()

    private val clock = BoardClock { now }
    private val haptics = BoardHaptics(
        clock = clock,
        schedule = { _, fire ->
            waiting += Runnable { fire() }
            BoardHaptics.Scheduled { waiting.clear() }
        },
        pulse = { pulses++ },
        report = { reported += it },
        now = { now },
    )

    private fun anchor() {
        // An answer that came back in the same instant: the estimate is exact, so the cue's arithmetic is what
        // the test measures, not the trip's asymmetry.
        clock.anchor(now, now, now + phoneOffset)
    }

    /** The phone's epoch for a moment on the watch's clock. */
    private fun phoneAt(watch: Long): Long = watch + phoneOffset

    @Test
    fun firesOneLeadBeforeTheOnset() {
        anchor()
        val onset = now + 1_000
        assertEquals(BoardHaptics.Result.Scheduled, haptics.onSpeech(SeatSpeech("t1", "ada", 1, onsetEpochMs = phoneAt(onset))))

        now += 700
        waiting.single().run()

        assertEquals(1, pulses)
        assertEquals(1, reported.size)
        assertEquals(300, reported.single().leadMs)
        assertEquals(phoneAt(now), reported.single().firedEpochMs)
    }

    @Test
    fun dropsACueInsideTheFloorRatherThanLandingOnTheVoice() {
        anchor()
        val onset = now + 200
        assertEquals(BoardHaptics.Result.DroppedLate, haptics.onSpeech(SeatSpeech("t2", "ada", 1, onsetEpochMs = phoneAt(onset))))
        assertEquals(0, pulses)

        // Scheduled, then the watch runs late: the floor still drops it.
        val later = haptics.onSpeech(SeatSpeech("t3", "ada", 1, onsetEpochMs = phoneAt(now + 1_000)))
        assertEquals(BoardHaptics.Result.Scheduled, later)
        now += 760
        waiting.last().run()
        assertEquals(0, pulses)
    }

    @Test
    fun guessesNothingWithoutARoundTrip() {
        assertEquals(BoardHaptics.Result.DroppedUnanchored, haptics.onSpeech(SeatSpeech("t4", "ada", 1, onsetEpochMs = now + 1_000)))
        assertEquals(0, pulses)
    }

    @Test
    fun aCorrectionAfterThePulseDoesNotFireAgain() {
        anchor()
        haptics.onSpeech(SeatSpeech("t5", "ada", 1, onsetEpochMs = phoneAt(now + 600)))
        now += 300
        waiting.single().run()
        assertEquals(1, pulses)

        // The phone measured a later onset and corrects the same turn: already cued, so nothing more.
        haptics.onSpeech(SeatSpeech("t5", "ada", 2, onsetEpochMs = phoneAt(now + 300)))
        assertEquals(1, pulses)
        assertTrue(reported.single().leadMs in 250..350)
    }

    @Test
    fun anOldMessageCannotMoveACueBackwards() {
        anchor()
        haptics.onSpeech(SeatSpeech("t6", "ada", 2, onsetEpochMs = phoneAt(now + 1_000)))
        assertNull(haptics.onSpeech(SeatSpeech("t6", "ada", 1, onsetEpochMs = phoneAt(now + 100))))
        assertEquals(1, haptics.pending())
    }
}
