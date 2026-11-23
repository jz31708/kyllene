package dev.hermeskotlin.ui.voice

import android.util.Log

private const val TAG = "WT-MIC"

actual fun logMicrophoneBlocked(reason: String, where: String) {
    Log.i(TAG, "blocked=$reason at=$where")
}

actual fun logMicrophoneRetry(reason: String) {
    Log.i(TAG, "retry=$reason")
}
