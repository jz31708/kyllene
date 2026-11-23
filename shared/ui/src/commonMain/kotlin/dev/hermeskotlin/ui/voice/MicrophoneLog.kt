package dev.hermeskotlin.ui.voice

/**
 * The microphone's side of a voice chat, in logcat: `adb logcat -s WT-MIC` on the phone shows every time a chat
 * wouldn't listen — no permission for the microphone, or a microphone sending nothing but zeroes — and every time
 * it was asked to try again. The recorder logs its own half under the same tag.
 */
expect fun logMicrophoneBlocked(reason: String, where: String)

/** The button, or the way back from the permission page: the chat listens again. */
expect fun logMicrophoneRetry(reason: String)
