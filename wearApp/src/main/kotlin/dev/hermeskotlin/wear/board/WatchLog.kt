package dev.hermeskotlin.wear.board

import android.util.Log

/**
 * The Board remote's log tags: how a wrist action is proven from a cable, without a screenshot. Every step of the
 * chain says what it did — the ring's tap, the command and its send result, the phase the phone's report puts the
 * ring in, and each waveform the wrist fires. Cheap enough to keep in the board build.
 */
object WatchLog {
    /** The command chain: tap → command → phone → ack. */
    const val REMOTE = "WT-REMOTE"

    /** Every ring phase change. */
    const val PHASE = "WT-PHASE"

    /** Every waveform fired, with when. */
    const val HAPTIC = "WT-HAPTIC"

    fun remote(message: String) = Log.i(REMOTE, message)

    fun phase(message: String) = Log.i(PHASE, message)

    fun haptic(message: String) = Log.i(HAPTIC, message)
}
