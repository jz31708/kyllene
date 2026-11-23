package dev.hermeskotlin.android.wear

/**
 * The phone's end of the Board remote's proof: one tag for the whole chain, so `adb logcat -s WT-REMOTE` on both
 * devices shows a wrist action, the command it sent, the phone acting on it and the state it published back.
 */
object WatchBoardLog {
    const val TAG = "WT-REMOTE"
}
