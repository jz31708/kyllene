package dev.hermeskotlin.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records 16 kHz mono PCM with [AudioRecord] and hands it over as WAV, which every speech-to-text
 * provider takes. The level is Desktop's: RMS of the waveform, scaled so 1.0 is loud speech.
 */
class AndroidVoiceRecorder(
    /** How long a microphone may send nothing before the recording fails. */
    private val noAudioMs: Long = NO_AUDIO_MS,
    /** Whether a chained chat holds call routing, which changes the microphone [record] opens. */
    private val route: ChatAudioRoute? = null,
    /** How long only zeroes may come from the microphone before the turn is refused as unhearable. */
    private val silentMicMs: Long = SILENT_MIC_MS,
) : VoiceRecorder {

    @Volatile private var finishRequested = false

    @SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before starting.
    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = withContext(Dispatchers.IO) {
        finishRequested = false
        // Under a chained chat's call routing, a speech-recognition stream can be given another microphone (the back
        // one on Samsung's speakerphone route) that hears next to nothing until the idle wait ends it (#408). The call
        // microphone the reply is listened over with hears them, so the turn between replies uses it too.
        val callAudio = route?.callAudio() == true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // A permission taken back (a one-time grant the app went to the background on) fails the open, and some
        // builds throw for it instead: either way it's told apart from hearing nothing, so the chat says which.
        val record = try {
            AudioRecord(
                if (callAudio) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: SecurityException) {
            Log.i(MIC_TAG, "blocked=permission: the recorder wouldn't open (${e.message})")
            throw MicrophoneBlocked()
        } catch (e: IllegalArgumentException) {
            Log.i(MIC_TAG, "blocked=unavailable: the recorder wouldn't open (${e.message})")
            throw MicrophoneBlocked()
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.i(MIC_TAG, "blocked=permission: the recorder didn't initialize")
            throw MicrophoneBlocked()
        }
        // Noise suppression, and on the call microphone echo cancelling as over the reply. No automatic gain: it
        // lifts the room's hum in every pause until it reads as talking, and the end of speech is never found.
        val effects = ArrayList<AudioEffect>(2)
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        val bytes = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        val endOfSpeech = EndOfSpeech(activity)
        var elapsed = 0L
        try {
            // Inside the try, so the recorder is let go whatever they do; one that won't switch on is done without.
            if (callAudio && AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
            effects.forEach { runCatching { it.enabled = true } }
            try {
                record.startRecording()
            } catch (e: SecurityException) {
                Log.i(MIC_TAG, "blocked=permission: recording wouldn't start (${e.message})")
                throw MicrophoneBlocked()
            } catch (e: IllegalStateException) {
                Log.i(MIC_TAG, "blocked=permission: recording wouldn't start (${e.message})")
                throw MicrophoneBlocked()
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.i(MIC_TAG, "blocked=permission: recording isn't running")
                throw MicrophoneBlocked()
            }
            // Digits of nothing at all, not a quiet room: Android feeds a muted or withdrawn microphone zeroes
            // while reporting it as recording. Said out loud after [silentMicMs] rather than listening for ever.
            var silentMs = 0L
            while (!finishRequested) {
                ensureActive()
                if (!record.readFrame(frame) { finishRequested }) break
                bytes.clear()
                var sum = 0.0
                var silent = true
                for (sample in frame) {
                    bytes.putShort(sample)
                    sum += sample.toDouble() * sample
                    if (sample != 0.toShort()) silent = false
                }
                pcm.write(bytes.array())
                elapsed += FRAME_MS
                // Only before anyone is heard: a sentence already under way is the end-of-speech wait's to end.
                silentMs = if (silent && !endOfSpeech.heardSpeech) silentMs + FRAME_MS else 0L
                if (silentMs >= silentMicMs) {
                    Log.i(MIC_TAG, "blocked=silence: $silentMs ms of digital silence, no speech")
                    throw MicrophoneSilent()
                }
                // Desktop measures 8-bit samples (±128) against 42; 16-bit samples are 256 times larger.
                val level = min(1.0, sqrt(sum / frame.size) / 256.0 / 42.0).toFloat()
                onLevel(level)
                val heardBefore = endOfSpeech.heardSpeech
                val done = endOfSpeech.onFrame(level, elapsed)
                if (endOfSpeech.heardSpeech && !heardBefore) onSpeech()
                if (done) break
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            record.release()
            onLevel(0f)
        }
        Recording(wav(pcm.toByteArray()), "audio/wav", endOfSpeech.heardSpeech || finishRequested && pcm.size() > SAMPLE_RATE / 2)
    }

    override fun finish() {
        finishRequested = true
    }

    /**
     * The call microphone, with the phone's echo cancelling (and noise suppression) on it, so the voice coming out
     * of the speaker is mostly gone from what it hears. The last [BargeIn.PRE_ROLL_MS] are always kept; once
     * someone cuts in, the recording goes on from there until they pause.
     */
    @SuppressLint("MissingPermission") // Only during a voice chat, which asked for RECORD_AUDIO.
    override suspend fun listenOver(
        bargeIn: BargeIn,
        playing: () -> Boolean,
        onTrip: () -> Unit,
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
    ): Recording? =
        withContext(Dispatchers.IO) {
            finishRequested = false
            // Call mode the system dropped is taken up again before the microphone opens, not under it by the reply.
            route?.callAudio()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
                )
            } catch (_: Exception) {
                return@withContext null
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return@withContext null
            }
            val effects = ArrayList<AudioEffect>(2)
            val frame = ShortArray(FRAME_SAMPLES)
            val preRoll = ArrayDeque<ByteArray>()
            val preRollFrames = (BargeIn.PRE_ROLL_MS / FRAME_MS).toInt()
            val pcm = ByteArrayOutputStream()
            var elapsed = 0L
            var tripped = false
            try {
                // Inside the try, so the recorder is let go whatever they do; one that won't switch on is done without.
                if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
                if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
                effects.forEach { runCatching { it.enabled = true } }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
                onListening()
                while (true) {
                    ensureActive()
                    if (!record.readFrame(frame) { tripped && finishRequested }) break
                    val bytes = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var sum = 0.0
                    for (sample in frame) {
                        bytes.putShort(sample)
                        sum += sample.toDouble() * sample
                    }
                    elapsed += FRAME_MS
                    val level = min(1.0, sqrt(sum / frame.size) / 256.0 / 42.0).toFloat()
                    if (tripped) {
                        pcm.write(bytes.array())
                        onLevel(level)
                    } else {
                        preRoll.addLast(bytes.array())
                        if (preRoll.size > preRollFrames) preRoll.removeFirst()
                    }
                    when (bargeIn.onFrame(level, elapsed, playing())) {
                        BargeIn.Step.Tripped -> {
                            tripped = true
                            // Send now counts from here: one asked before anyone cut in isn't about what they say.
                            finishRequested = false
                            preRoll.forEach(pcm::write)
                            preRoll.clear()
                            onTrip()
                        }
                        BargeIn.Step.Done -> break
                        BargeIn.Step.Waiting, BargeIn.Step.Hearing -> Unit
                    }
                    // Send now, once it has heard them.
                    if (tripped && finishRequested) break
                }
            } finally {
                runCatching { record.stop() }
                // One that throws mustn't keep the microphone from being let go.
                effects.forEach { runCatching { it.release() } }
                record.release()
                onLevel(0f)
            }
            Recording(wav(pcm.toByteArray()), "audio/wav", heardSpeech = bargeIn.spoke || finishRequested)
        }

    /**
     * Fills [frame] with the next 20 ms, or returns false once [stop] says so. Reads don't block, so cancelling (and
     * Finish) reach the caller even when the microphone stops sending; a whole frame each time keeps the level and
     * the speech timers measuring 20 ms steps.
     */
    private suspend fun AudioRecord.readFrame(frame: ShortArray, stop: () -> Boolean): Boolean {
        var filled = 0
        var lastAudioAt = System.nanoTime()
        while (filled < frame.size) {
            currentCoroutineContext().ensureActive()
            if (stop()) return false
            val read = read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
            when {
                read == AudioRecord.ERROR_DEAD_OBJECT -> error("The microphone was disconnected.")
                read < 0 -> error("Couldn't read from the microphone (error $read).")
                read > 0 -> {
                    filled += read
                    lastAudioAt = System.nanoTime()
                }
                (System.nanoTime() - lastAudioAt) / NANOS_PER_MS >= noAudioMs -> error("The microphone stopped sending audio.")
                else -> delay(FRAME_MS / 2)
            }
        }
        return true
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size)
        }
        return header.array() + pcm
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val NANOS_PER_MS = 1_000_000L
        const val NO_AUDIO_MS = 5_000L

        /** 20 ms frames: fine-grained enough for the silence timer. */
        const val FRAME_SAMPLES = SAMPLE_RATE / 50
        const val FRAME_MS = 20L

        /** Three seconds of pure zeroes is a microphone that isn't there, not a quiet room. */
        const val SILENT_MIC_MS = 3_000L
        /**
         * The microphone chain's proof in logcat, on the phone and in the app alike: `adb logcat -s WT-MIC` says
         * whether a turn came from a permission that was gone or from a microphone hearing nothing.
         */
        const val MIC_TAG = "WT-MIC"
    }
}

/**
 * Plays each reply clip from a cache file with [MediaPlayer], and streamed speech straight into an
 * [AudioTrack] as it arrives, as assistant audio outside a chained voice chat.
 */
class AndroidSpeechPlayer(
    private val context: Context,
    /** Tells the recorder whether this player holds call routing for a chained chat. */
    route: ChatAudioRoute = ChatAudioRoute(),
) : SpeechPlayer, AudioFocusAware {
    override suspend fun playRingback() {
        val bytes = withContext(Dispatchers.IO) { context.assets.open("call_ringback.wav").use { it.readBytes() } }
        play(SpokenAudio(bytes, "audio/wav"))
    }

    private val lock = Any()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    /** Each chained chat open. The chat, a phone call and the assistant panel share this player. */
    private val chatAudioOwners = mutableSetOf<Any>()
    /** Puts the usual route back; set while the chained chats hold call routing or the headset's microphone. */
    private var restoreChatAudio: (() -> Unit)? = null
    /** The route held is the Bluetooth headset's rather than call routing on the phone's speaker. */
    private var headsetRouting = false
    /** The headset's microphone was asked for with [headsetMic], across chats until it's turned off. */
    private var headsetMicOn = false
    /** Whether the outputs are watched: while a chat is open, or the headset's microphone is wanted. */
    private var watchingOutputs = false
    /** Speeches playing now: the first takes the speaker and the last lets it go. */
    private var speechCount = 0
    /** The speaker isn't the app's, or a phone call has it: what plays is paused until it comes back. */
    private var focusHeld = false
    /** Where replies are heard, named for whoever shows the line's route. */
    private val _routeLabel = MutableStateFlow<String?>(null)
    /** The output in use now: the device's own name where it has one, else what kind of device it is. */
    val routeLabel: StateFlow<String?> = _routeLabel.asStateFlow()

    /** Whether the app may take the speaker now: a pushed line waits while navigation or a call has it. */
    override val audioFree: Boolean get() = synchronized(lock) { !focusHeld }

    /** The output in use now, for the call screen and the watch (`BoardCallSnapshot.route`). */
    override val audioRoute: String? get() = _routeLabel.value
    /** The speaker, for as long as one speech plays: taken when it starts and let go when it ends. */
    private val callFocus = CallAudioFocus(context) { free -> focusChanged(free) }
    /** Headphones or earbuds that come or go during a chained chat change its route. */
    private val outputsChanged = object : AudioDeviceCallback() {
        // One that appears mid-word waits for the next speech, which takes it up: swapping a working route under the
        // line would be heard. One that goes takes its route with it, so the speaker is put back at once.
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute(onlyWhenIdle = true)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reroute()
    }

    init {
        route.check = { callRoutingHeld() }
        publishRoute()
    }

    /**
     * Whether call routing is held, taken up again first if the system dropped it. Asked before every listen and
     * reply, so a route that didn't take isn't tried again here: that waits for the outputs to change.
     */
    private fun callRoutingHeld(): Boolean = synchronized(lock) {
        if (restoreChatAudio != null) reroute()
        restoreChatAudio != null
    }

    /** Held ([hold]): what plays now is paused, and what's ready meanwhile waits to start. */
    private var holding = false
    private var track: AudioTrack? = null
    /** Whether the track built now has been let start; a stopped one that has played isn't started over. */
    private var trackPlayed = false
    /** A clip once it's ready to play. */
    private var clip: MediaPlayer? = null

    /**
     * A chained chat with talk-over on, on the phone's own speaker, uses call routing, so the phone's echo cancelling
     * keeps the voice out of the microphone. Headphones, earbuds or another speaker keep their usual route: call routing would move
     * earbuds without a call profile to the phone's speaker and drop the others to call quality. A route that won't
     * take leaves the chat on its usual one, never on the earpiece. Headphones that come or go mid-chat change the
     * route. The returned close action is safe to call again; the route goes back once every chat has closed.
     */
    override fun chainedChatAudio(): () -> Unit = synchronized(lock) {
        val owner = Any()
        chatAudioOwners += owner
        watchOutputs(true)
        reroute()
        return@synchronized {
            synchronized(lock) {
                // An old chat's finally must not restore under a newer one, here or in another controller.
                if (chatAudioOwners.remove(owner) && chatAudioOwners.isEmpty()) {
                    watchOutputs(headsetMicOn)
                    reroute()
                }
            }
        }
    }

    /**
     * Records from a Bluetooth headset's microphone, as a call does, while one is connected: the reply and the turn
     * between replies both go over it, at call quality. Turned off, or with the headset gone, the phone's speaker has
     * the line again. Kept across chats until it's turned off.
     */
    override fun headsetMic(on: Boolean) = synchronized(lock) {
        headsetMicOn = on
        watchOutputs(on || chatAudioOwners.isNotEmpty())
        reroute()
    }

    /** Watches the outputs while one of them can change the route; called with [lock] held. */
    private fun watchOutputs(wanted: Boolean) {
        if (wanted == watchingOutputs) return
        watchingOutputs = wanted
        runCatching {
            if (wanted) audioManager.registerAudioDeviceCallback(outputsChanged, null) else audioManager.unregisterAudioDeviceCallback(outputsChanged)
        }
    }

    /** What the player set up for the line: call routing on the phone's speaker, or a headset's microphone. */
    private enum class Route { CALL, HEADSET }

    /**
     * Call routing while a chained chat is open on the phone's own speaker, the headset's microphone when it was asked
     * for, the usual route otherwise. A route the system dropped is taken up again; a mode someone else holds, such as
     * a phone call ringing or in progress, is left alone. [onlyWhenIdle] keeps a device that appeared mid-speech from
     * moving the line under it: the next speech takes it up.
     */
    private fun reroute(onlyWhenIdle: Boolean = false) {
        synchronized(lock) {
            if (onlyWhenIdle && speechCount > 0) return
            val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            val sco = outputs?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            // The headset's microphone only while the headset is there; call routing while a chained chat is open on
            // the phone's own speaker; the usual route otherwise.
            val wanted = when {
                headsetMicOn && sco != null -> Route.HEADSET
                chatAudioOwners.isNotEmpty() && outputs != null && outputs.none { it.type in OWN_OUTPUTS } -> Route.CALL
                else -> null
            }
            val held = when {
                restoreChatAudio == null -> null
                headsetRouting -> Route.HEADSET
                else -> Route.CALL
            }
            if (wanted == held) {
                // The system puts the mode back to normal once no call audio has played or listened for a few
                // seconds, as between a reply and the next turn (#408). Held routing is taken up again from there, or
                // let go if it won't take; a mode someone else holds is left alone.
                val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
                if (held != null && mode == AudioManager.MODE_NORMAL && !retake(held)) letGo()
            } else {
                if (held != null) letGo()
                if (wanted != null) open(wanted)
            }
            publishRoute()
        }
    }

    /** Takes a route up for the chats; called with [lock] held. Puts the usual route back where it won't take. */
    @Suppress("DEPRECATION")
    private fun open(route: Route) {
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        val speaker = runCatching { audioManager.isSpeakerphoneOn }.getOrDefault(false)
        val sco = runCatching { audioManager.isBluetoothScoOn }.getOrDefault(false)
        val restore = {
            // Each step on its own: a chat's end must never throw.
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    audioManager.clearCommunicationDevice()
                } else {
                    if (route == Route.HEADSET) audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = sco
                    audioManager.isSpeakerphoneOn = speaker
                }
            }
            runCatching { audioManager.mode = mode }
            Unit
        }
        try {
            check(take(route))
            restoreChatAudio = restore
            headsetRouting = route == Route.HEADSET
        } catch (_: Exception) {
            restore()
            restoreChatAudio = null
            headsetRouting = false
        }
    }

    /** Puts the usual route back; called with [lock] held. */
    private fun letGo() {
        val restore = restoreChatAudio ?: return
        restoreChatAudio = null
        headsetRouting = false
        restore()
    }

    /** A route the system dropped, taken up again with the restore from before it still in hand. */
    private fun retake(route: Route): Boolean = runCatching { take(route) }.getOrDefault(false)

    /** Call mode on [route]; false (or a throw) when its device can't be the call's. */
    @Suppress("DEPRECATION")
    private fun take(route: Route): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val device = if (route == Route.HEADSET) {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else {
                audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } ?: return false
            return audioManager.setCommunicationDevice(device)
        }
        if (route == Route.HEADSET) {
            // The headset's own microphone over SCO, as a phone call would use it.
            audioManager.isSpeakerphoneOn = false
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
        } else {
            audioManager.isSpeakerphoneOn = true
        }
        return true
    }

    /** Names the output the line goes to now; a change of route changes it. */
    private fun publishRoute() {
        synchronized(lock) {
            _routeLabel.value = runCatching {
                val output = when {
                    restoreChatAudio == null -> currentOutput()
                    headsetRouting -> if (Build.VERSION.SDK_INT >= 31) runCatching { audioManager.communicationDevice }.getOrNull() else communicationDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                    else -> communicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                }
                output?.let { name(it) } ?: if (headsetRouting) "Bluetooth" else "phone speaker"
            }.getOrDefault("phone speaker")
        }
    }

    /** The output the usual route plays to, as near as the system's list of them tells. */
    private fun currentOutput(): AudioDeviceInfo? =
        runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?.minByOrNull { outputRank(it.type) }

    /** The call device of [type] in use, where the platform names one. */
    @Suppress("DEPRECATION")
    private fun communicationDevice(type: Int): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { audioManager.communicationDevice }.getOrNull()?.let { if (it.type == type) return it }
        }
        return runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?.firstOrNull { it.type == type }
    }

    /** The device's own name when it has one, else what kind it is. */
    private fun name(device: AudioDeviceInfo): String =
        runCatching { device.productName.toString().takeIf { it.isNotBlank() } }.getOrNull() ?: typeLabel(device.type)

    /** What kind of output a device is, for one that doesn't name itself. */
    @SuppressLint("InlinedApi") // A device only reports the types its Android knows.
    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "phone earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "earbuds"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> "Bluetooth"
        else -> "audio device"
    }

    /** Which output the usual route plays to, where the system can't say which one it picked. */
    @SuppressLint("InlinedApi")
    private fun outputRank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> 0
        AudioDeviceInfo.TYPE_HEARING_AID -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 3
        AudioDeviceInfo.TYPE_USB_HEADSET -> 4
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 5
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 6
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 7
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE -> 8
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 10
        else -> 11
    }

    /** Call audio while a chained chat has call routing, assistant audio otherwise. */
    // A reply after a quiet spell finds call mode taken up again first.
    private fun speechUsage() = if (callRoutingHeld()) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT

    override fun hold(on: Boolean): Boolean {
        synchronized(lock) {
            holding = on
            applyHold()
        }
        return true
    }

    /**
     * Pauses what plays while it's held, or the speaker isn't the app's, and plays on when both let go. Called with
     * [lock] held.
     */
    private fun applyHold() {
        val paused = holding || focusHeld
        track?.let { track ->
            runCatching {
                when {
                    paused && track.playState == AudioTrack.PLAYSTATE_PLAYING -> track.pause()
                    // A track that has only been built starts here, rather than filling its buffer unheard. One that
                    // has played and been stopped for its tail to drain isn't started over.
                    !paused && (track.playState == AudioTrack.PLAYSTATE_PAUSED || !trackPlayed) -> {
                        track.play()
                        trackPlayed = true
                    }
                }
            }
        }
        clip?.let { runCatching { if (paused) { if (it.isPlaying) it.pause() } else if (!it.isPlaying) it.start() } }
    }

    /** The speaker came back, or was taken for a phone call or another app: the line pauses or plays on where it was. */
    private fun focusChanged(free: Boolean) {
        synchronized(lock) {
            focusHeld = !free
            applyHold()
        }
    }

    /** Takes the speaker for a speech starting now; a speech already playing keeps it. */
    private fun startSpeech() {
        if (!synchronized(lock) { speechCount++ == 0 }) return
        // Outside the lock: taking the focus may report back at once, and that takes the lock again.
        val free = callFocus.acquire()
        synchronized(lock) {
            focusHeld = !free
            applyHold()
        }
    }

    /** Lets the speaker go once the last speech has ended. */
    private fun endSpeech() {
        if (!synchronized(lock) { speechCount > 0 && --speechCount == 0 }) return
        callFocus.release()
        synchronized(lock) {
            focusHeld = false
            applyHold()
        }
    }

    override suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit) = coroutineScope {
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Can't play $channels-channel speech.")
        }
        // A frame is a 16-bit sample for each channel.
        val frameBytes = 2 * channels
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(speechUsage())
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            // Half a second of room: enough to ride out the network's hiccups, little enough to stop at once.
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT), sampleRate * channels))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Frames handed to the track; the head position counts the ones already heard.
        val written = AtomicLong()
        fun played(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val sound = launch(Dispatchers.Default) {
            var on = false
            var quietSince = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val pending = played() < written.get()
                if (pending && !on) {
                    on = true
                    onSound(true)
                } else if (!pending && on) {
                    if (quietSince == 0L) quietSince = now
                    // The short gaps between sentences aren't the voice stopping.
                    if (now - quietSince >= QUIET_MS) {
                        on = false
                        onSound(false)
                    }
                }
                if (pending) quietSince = 0L
                delay(POLL_MS)
            }
        }
        try {
            startSpeech()
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    this@AndroidSpeechPlayer.track = track
                    trackPlayed = false
                    // Held, it fills its buffer and waits to be let go.
                    if (!holding && !focusHeld) {
                        track.play()
                        trackPlayed = true
                    }
                }
                // Provider chunks needn't end on a whole frame; what's left over waits for the next chunk.
                var carry = ByteArray(0)
                for (chunk in chunks) {
                    val bytes = if (carry.isEmpty()) chunk else carry + chunk
                    val usable = bytes.size - bytes.size % frameBytes
                    carry = bytes.copyOfRange(usable, bytes.size)
                    var offset = 0
                    while (offset < usable) {
                        ensureActive()
                        val n = track.write(bytes, offset, usable - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (n < 0) error("Couldn't play the reply ($n).")
                        offset += n
                        written.addAndGet(n.toLong() / frameBytes)
                        if (n == 0) delay(WRITE_WAIT_MS)
                    }
                }
                // What the track still holds, counted before stopping: a stopped track's position can read 0, so
                // waiting for it to catch up would sit through the whole reply's length again in silence.
                val left = (written.get() - played()).coerceAtLeast(0)
                // Nor can it tell sound from silence any more: what's showing now stays until the end.
                sound.cancelAndJoin()
                // Stopping a streaming track plays out what it holds first. Held meanwhile, it pauses where it is and
                // goes on when let go, so the wait does too.
                track.stop()
                var draining = left * 1000 / sampleRate + DRAIN_SLACK_MS
                while (draining > 0) {
                    val step = min(draining, DRAIN_STEP_MS)
                    delay(step)
                    if (!synchronized(lock) { holding || focusHeld }) draining -= step
                }
            }
        } finally {
            try {
                withContext(NonCancellable) { sound.cancelAndJoin() }
                onSound(false)
                synchronized(lock) { if (this@AndroidSpeechPlayer.track === track) this@AndroidSpeechPlayer.track = null }
                runCatching {
                    track.pause()
                    track.flush()
                }
                track.release()
            } finally {
                endSpeech()
            }
        }
    }

    override suspend fun play(audio: SpokenAudio) {
        val extension = when {
            "ogg" in audio.mimeType || "opus" in audio.mimeType -> "ogg"
            "wav" in audio.mimeType -> "wav"
            "flac" in audio.mimeType -> "flac"
            else -> "mp3"
        }
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("hermes-speech-", ".$extension", context.cacheDir).apply { writeBytes(audio.bytes) }
        }
        val player = MediaPlayer()
        try {
            startSpeech()
            suspendCancellableCoroutine { continuation ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(speechUsage())
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setOnCompletionListener {
                    // Done: letting go of a hold mustn't start it over.
                    synchronized(lock) { if (clip === player) clip = null }
                    if (continuation.isActive) continuation.resume(Unit)
                }
                player.setOnErrorListener { _, what, _ ->
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Couldn't play the reply ($what)."))
                    true
                }
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener {
                    synchronized(lock) {
                        clip = it
                        if (!holding && !focusHeld) it.start()
                    }
                }
                player.prepareAsync()
                continuation.invokeOnCancellation { runCatching { player.stop() } }
            }
        } finally {
            try {
                synchronized(lock) { if (clip === player) clip = null }
                player.release()
                file.delete()
            } finally {
                endSpeech()
            }
        }
    }

    private companion object {
        const val POLL_MS = 50L
        const val QUIET_MS = 600L
        const val WRITE_WAIT_MS = 10L
        const val DRAIN_SLACK_MS = 400L
        /** How often the drain looks whether it's held. */
        const val DRAIN_STEP_MS = 50L

        /** Outputs that aren't the phone itself: with one in, a chained chat leaves the route alone. */
        @SuppressLint("InlinedApi") // A device only reports the types its Android knows.
        val OWN_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
    }
}

/**
 * Whether a chained chat holds call routing right now, shared by the [AndroidSpeechPlayer] that holds it and the
 * [AndroidVoiceRecorder] whose microphone depends on it.
 */
class ChatAudioRoute {
    /** Set by the player: takes held routing up again if the system dropped it, and says whether it's held. */
    @Volatile internal var check: () -> Boolean = { false }

    /** True while a chained chat has call routing, taken up again first if the system let it go meanwhile. */
    fun callAudio(): Boolean = check()
}
