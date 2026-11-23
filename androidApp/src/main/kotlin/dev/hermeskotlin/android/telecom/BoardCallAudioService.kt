package dev.hermeskotlin.android.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The spike's measurement, with the phone in a pocket: a `phoneCall|microphone` foreground service, started from the
 * call's own `Connection` — that is, from the background, with the screen likely locked — which then tries to open
 * the microphone and reports every second what it hears.
 *
 * It answers the only question that decides the design: whether the microphone is actually usable in that state. If
 * Android refuses the service, or hands it zeroes, `WT-CS` says which, and no amount of call UI would have helped.
 */
class BoardCallAudioService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var probe: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TelecomSpike.log("probe: stop asked by the app")
            stopSelf()
            return START_NOT_STICKY
        }
        val reason = intent?.getStringExtra(TelecomSpike.EXTRA_REASON) ?: "unknown"
        instance = this
        if (!startAsCall(reason)) {
            // Android 14+ refuses a microphone service started from the background outright; said once, clearly.
            TelecomSpike.log("probe: service refused, no microphone to measure")
            return START_NOT_STICKY
        }
        probe = scope.launch { listen() }
        return START_NOT_STICKY
    }

    /** The call's notification: `phoneCall` wants a call-style one from 14, and it is what the wrist's call chip is. */
    private fun startAsCall(reason: String): Boolean {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Board call (spike)", NotificationManager.IMPORTANCE_LOW))
        val hangup = PendingIntent.getService(
            this, 1, Intent(this, BoardCallAudioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Notification.Builder(this, CHANNEL)
                .setStyle(Notification.CallStyle.forOngoingCall(Person.Builder().setName("Kyllene Board").build(), hangup))
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentText("Listening: $reason")
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Kyllene Board")
                .setContentText("Listening: $reason")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .build()
        }
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            TelecomSpike.log("probe: foreground started types=$types sdk=${Build.VERSION.SDK_INT}")
            true
        }.getOrElse {
            TelecomSpike.log("probe: startForeground threw ${it.javaClass.simpleName}: ${it.message}")
            stopSelf()
            false
        }
    }

    /**
     * Reads the call microphone and says what comes back: the level once a second, the first sound heard, and how
     * long a run of exact zeroes lasts (the signature of a microphone the system didn't really give us).
     */
    private suspend fun listen() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: Exception) {
            TelecomSpike.log("probe: recorder wouldn't open ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        TelecomSpike.log("probe: recorder state=${record.state} initialized=${record.state == AudioRecord.STATE_INITIALIZED}")
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        val frame = ShortArray(FRAME_SAMPLES)
        var silentMs = 0L
        var heardAt = -1L
        var loudest = 0.0
        var elapsed = 0L
        var frames = 0
        try {
            record.startRecording()
            TelecomSpike.log("probe: recording=${record.recordingState == AudioRecord.RECORDSTATE_RECORDING}")
            while (currentCoroutineContext().isActive && elapsed < MAX_MS) {
                val filled = readFrame(record, frame)
                if (filled < 0) {
                    TelecomSpike.log("probe: read error=$filled")
                    return
                }
                frames++
                elapsed += FRAME_MS
                var sum = 0.0
                var silent = true
                for (sample in frame) {
                    if (sample != 0.toShort()) silent = false
                    sum += sample.toDouble() * sample
                }
                val level = min(1.0, sqrt(sum / frame.size) / 256.0 / 42.0)
                loudest = maxOf(loudest, level)
                silentMs = if (silent) silentMs + FRAME_MS else 0L
                if (level > 0.005 && heardAt < 0) {
                    heardAt = elapsed
                    TelecomSpike.log("probe: first sound at ${elapsed}ms level=${"%.3f".format(level)}")
                }
                if (elapsed % LOG_EVERY_MS < FRAME_MS) {
                    TelecomSpike.log(
                        "probe: t=${elapsed}ms level=${"%.3f".format(level)} loudest=${"%.3f".format(loudest)} " +
                            "silent=${silentMs}ms frames=$frames",
                    )
                }
            }
        } catch (e: Exception) {
            TelecomSpike.log("probe: failed ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            TelecomSpike.log("probe: closed after ${elapsed}ms frames=$frames heard=${heardAt >= 0} loudest=${"%.3f".format(loudest)}")
        }
    }

    /** Fills one 20 ms frame; -1 on a read error, 0 when the microphone stopped answering. */
    private suspend fun readFrame(record: AudioRecord, frame: ShortArray): Int {
        var filled = 0
        var idleTries = 0
        while (filled < frame.size) {
            currentCoroutineContext().ensureActive()
            val read = record.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
            when {
                read < 0 -> return read
                read == 0 -> {
                    // A microphone that isn't answering at all: a second of nothing is a finding, not a wait.
                    if (++idleTries > 100) return -1
                    delay(10)
                }
                else -> {
                    filled += read
                    idleTries = 0
                }
            }
        }
        return filled
    }

    override fun onDestroy() {
        TelecomSpike.log("probe: service destroyed")
        probe?.cancel()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = SAMPLE_RATE / 50
        private const val FRAME_MS = 20L
        private const val LOG_EVERY_MS = 1_000L
        private const val MAX_MS = 30 * 60_000L
        private const val NOTIFICATION_ID = 4_121
        private const val CHANNEL = "board_call_spike"
        private const val ACTION_STOP = "dev.hermeskotlin.board.CALL_SPIKE_STOP"

        @Volatile private var instance: BoardCallAudioService? = null

        /** Starts the probe from the call's own connection: a background start, which is the point. */
        fun start(context: Context, reason: String) {
            val intent = Intent(context, BoardCallAudioService::class.java).putExtra(TelecomSpike.EXTRA_REASON, reason)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
                TelecomSpike.log("probe: starting from the connection reason=$reason")
            }.onFailure {
                TelecomSpike.log("probe: couldn't start ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        /** The call ended: the probe goes with it. */
        fun stop() {
            instance?.let {
                TelecomSpike.log("probe: stopping with the call")
                it.stopSelf()
            } ?: TelecomSpike.log("probe: nothing to stop")
        }
    }
}
