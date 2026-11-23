package dev.hermeskotlin.wear.board

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The frozen four-pattern haptic grammar, ported from `agent-config/mobile/wear`'s `HapticGrammar`: the wrist feels
 * the same four things for the same four reasons. Timings are milliseconds, the first element the initial delay;
 * amplitudes are 0..255.
 *
 * | pattern        | waveform                  |
 * |----------------|---------------------------|
 * | mic open       | 1 × 20 ms, full           |
 * | sent           | 2 × 15 ms (80 ms gap)     |
 * | pre-TTS cue    | 1 × 300 ms soft (96)      |
 * | approval/alert | 2 × 80 ms (100 ms gap)    |
 */
enum class HapticPattern(val wire: String) {
    MIC_OPEN("mic_open"),
    SENT("sent"),
    PRE_TTS("pre_tts_300ms"),
    APPROVAL("pending_approval"),
}

/** The watch's vibration, in the grammar's waveforms. */
class WatchHaptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun play(pattern: HapticPattern) {
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        val (timings, amplitudes) = waveform(pattern)
        runCatching { device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
    }

    private fun waveform(pattern: HapticPattern): Pair<LongArray, IntArray> = when (pattern) {
        HapticPattern.MIC_OPEN -> longArrayOf(0, 20) to intArrayOf(0, FULL)
        HapticPattern.SENT -> longArrayOf(0, 15, 80, 15) to intArrayOf(0, FULL, 0, FULL)
        // The 300 ms cue is scheduled by BoardHaptics against the phone's clock; this is its waveform.
        HapticPattern.PRE_TTS -> longArrayOf(0, 300) to intArrayOf(0, SOFT)
        HapticPattern.APPROVAL -> longArrayOf(0, 80, 100, 80) to intArrayOf(0, FULL, 0, FULL)
    }

    companion object {
        const val FULL = 255
        const val SOFT = 96
    }
}
