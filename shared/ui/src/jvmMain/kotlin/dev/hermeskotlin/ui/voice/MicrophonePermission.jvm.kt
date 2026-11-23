package dev.hermeskotlin.ui.voice

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.voice.MicrophoneCheck

// The desktop asks nobody: the recorder itself says when there's no microphone to use.
@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission = MicrophonePermission { _, onGranted -> onGranted() }

// Nothing to grant: a voice chat blocked by the microphone offers its own recorder's message instead.
@Composable
actual fun rememberMicrophoneSettings(): () -> Unit = {}

// The desktop's recorder says itself when there's no microphone; nothing here ever says no.
@Composable
actual fun rememberMicrophoneCheck(): MicrophoneCheck = MicrophoneCheck.Always
