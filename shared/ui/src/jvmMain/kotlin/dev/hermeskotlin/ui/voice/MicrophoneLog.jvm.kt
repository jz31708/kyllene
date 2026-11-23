package dev.hermeskotlin.ui.voice

// The desktop's voice chat has no permission to be refused; the recorder says itself when nothing hears.
actual fun logMicrophoneBlocked(reason: String, where: String) = Unit

actual fun logMicrophoneRetry(reason: String) = Unit
