package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.MicrophoneBlocked
import dev.hermeskotlin.core.voice.MicrophoneCheck
import dev.hermeskotlin.core.voice.MicrophoneSilent
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A voice chat that can't listen says so and stays silent: no orb breathing at a microphone the app was never
 * given, and no waiting for ever on one that sends nothing but zeroes (Thomas's Board voice attempts, 2026-11-23).
 */
class VoiceMicrophoneBlockedTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Throws what the recorder throws, [failures] times, then hears a sentence. */
    private class FaultyRecorder(private val fault: Exception, private val failures: Int = Int.MAX_VALUE) : VoiceRecorder {
        var recordings = 0
        val listening = MutableStateFlow(0)
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            recordings++
            listening.value = recordings
            if (recordings <= failures) throw fault
            onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() = Unit
    }

    private object Silent : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private fun TestScope.session(): ChatSession {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = createHttpClient(MockEngine { error("no REST in this test") }, cookies)
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> error("not connecting") }, backgroundScope)
        return ChatSession(gateway, null, null, connection, SessionsApi(http), backgroundScope)
    }

    private fun audio() = AudioApi(
        createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"Hello"}""", HttpStatusCode.OK, json)
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        ),
    )

    /** A microphone the app may not open never listens at all, and says so where the chat would have been. */
    @Test
    fun aRefusedMicrophoneNeverListens() = runTest {
        val recorder = FaultyRecorder(MicrophoneBlocked())
        val voice = VoiceController(
            audio(), recorder, Silent, backgroundScope, backgroundScope,
            microphone = MicrophoneCheck { false },
        )

        voice.startChat(session(), gateway, null)

        assertEquals(VoicePhase.Blocked, voice.chat.value.phase)
        assertEquals(MicrophoneBlock.Permission, voice.chat.value.blocked)
        assertEquals(0, recorder.recordings, "the microphone was opened without permission")
        voice.stopChat()
    }

    /** The refusal comes before any call: refusing it is what puts the screen back. */
    @Test
    fun aRefusedChatIsEndedByOneTap() = runTest {
        val voice = VoiceController(
            audio(), FaultyRecorder(MicrophoneBlocked()), Silent, backgroundScope, backgroundScope,
            microphone = MicrophoneCheck { false },
        )
        voice.showMicrophoneBlocked()
        assertEquals(VoicePhase.Blocked, voice.chat.value.phase)

        voice.stopChat()

        assertEquals(VoicePhase.Off, voice.chat.value.phase)
        assertNull(voice.chat.value.blocked)
    }

    /** A microphone that sends zeroes isn't "listening": the chat says it can't hear, and the call stays up. */
    @Test
    fun digitalSilenceStopsListeningAndSaysSo() = runTest {
        val recorder = FaultyRecorder(MicrophoneSilent())
        val voice = VoiceController(audio(), recorder, Silent, backgroundScope, backgroundScope)

        voice.startChat(session(), gateway, null)
        voice.chat.first { it.phase == VoicePhase.Blocked }

        assertEquals(MicrophoneBlock.Silence, voice.chat.value.blocked)
        // Waiting, not listening on: the microphone is not opened again and again while it's blocked.
        advanceTimeBy(30_000)
        testScheduler.runCurrent()
        assertEquals(1, recorder.recordings, "the microphone kept opening while blocked")

        // The button: it listens again, with a microphone the app may use.
        voice.retryBlocked()
        recorder.listening.first { it >= 2 }
        voice.stopChat()
    }

    /** Granted at last (from the permission page): a chat blocked before it started leaves the screen. */
    @Test
    fun grantingAfterARefusalLetsTheChatStart() = runTest {
        var granted = false
        val recorder = FaultyRecorder(MicrophoneBlocked())
        val voice = VoiceController(
            audio(), recorder, Silent, backgroundScope, backgroundScope,
            microphone = MicrophoneCheck { granted },
        )
        voice.startChat(session(), gateway, null)
        assertEquals(VoicePhase.Blocked, voice.chat.value.phase)
        assertFalse(voice.retryBlocked(), "a chat that never started has nothing to resume")

        granted = true
        assertFalse(voice.retryBlocked(), "nothing waiting: the screen goes so the chat can be started")
        assertEquals(VoicePhase.Off, voice.chat.value.phase)

        // Started again, as coming back from Settings does, it listens.
        val hearing = FaultyRecorder(MicrophoneBlocked(), failures = 0)
        val again = VoiceController(
            audio(), hearing, Silent, backgroundScope, backgroundScope,
            microphone = MicrophoneCheck { granted },
        )
        again.startChat(session(), gateway, null)
        hearing.listening.first { it >= 1 }
        assertTrue(again.chat.value.phase != VoicePhase.Blocked)
        again.stopChat()
    }
}
