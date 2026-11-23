package dev.hermeskotlin.core.voice

/**
 * Whether the platform lets this app open the microphone right now, asked without asking anyone: a voice chat
 * checks before it listens and the screen offers the permission page when the answer is no. Android can take a
 * grant back between listens (a one-time one, once the app goes to the background), so it's asked each time,
 * never remembered.
 */
fun interface MicrophoneCheck {
    fun granted(): Boolean

    companion object {
        /** A host that asks nobody, like the desktop. */
        val Always = MicrophoneCheck { true }
    }
}
