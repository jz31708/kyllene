package dev.hermeskotlin.ui.voice

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.voice.MicrophoneCheck

/** Asks for the microphone before voice input starts. */
fun interface MicrophonePermission {
    /** Runs [onGranted] once the app may record, asking first if needed; [onDenied] otherwise. */
    fun withMicrophone(onDenied: () -> Unit, onGranted: () -> Unit)
}

@Composable
expect fun rememberMicrophonePermission(): MicrophonePermission

/**
 * Opens this app's own permission page in the system settings, where a microphone that was refused — or a
 * one-time one Android took back — can be given for good. A voice chat blocked by the microphone offers it.
 */
@Composable
expect fun rememberMicrophoneSettings(): () -> Unit

/** Whether this app may open the microphone now, asked without asking anyone (the desktop always may). */
@Composable
expect fun rememberMicrophoneCheck(): MicrophoneCheck
