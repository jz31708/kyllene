package dev.hermeskotlin.core.voice

/**
 * The microphone couldn't be opened at all: the app may not use it (a one-time grant Android took back, or one
 * refused), or the system gave it to somebody else. A voice chat says so and opens the permission page; it never
 * pretends to listen.
 */
class MicrophoneBlocked(message: String = "The microphone isn't available.") : Exception(message)

/**
 * The microphone opened, and sends nothing but zeroes: Android hands a muted or unpermitted microphone a stream of
 * digital silence rather than an error. A voice chat says it can't hear anything instead of listening for ever.
 */
class MicrophoneSilent(message: String = "The microphone sent no sound.") : Exception(message)
