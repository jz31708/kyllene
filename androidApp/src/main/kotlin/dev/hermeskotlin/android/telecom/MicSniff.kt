package dev.hermeskotlin.android.telecom

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The second half of the spike's answer. If Android refuses to start the call's foreground service from the
 * background, the foreground service can't be measured — but one more thing can: whether the microphone is usable
 * from the connection at all, with no foreground service behind it. Five seconds of pure zeroes says the denial is
 * about the app's state, not the account; anything else says the call itself bought something.
 *
 * Deliberately short, and never on the shipped path.
 */
object MicSniff {

    private const val SAMPLE_RATE = 16_000
    private const val FRAME_SAMPLES = SAMPLE_RATE / 50
    private const val FRAME_MS = 20L
    private const val SECONDS = 5

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun sniff(why: String) {
        scope.launch {
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
                TelecomSpike.log("sniff: recorder wouldn't open ${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                TelecomSpike.log("sniff: recorder not initialized ($why)")
                record.release()
                return@launch
            }
            val frame = ShortArray(FRAME_SAMPLES)
            var frames = 0
            var nonZero = 0
            var loudest = 0.0
            try {
                record.startRecording()
                TelecomSpike.log("sniff: recording=$why")
                val until = System.currentTimeMillis() + SECONDS * 1_000
                while (System.currentTimeMillis() < until) {
                    var filled = 0
                    while (filled < frame.size) {
                        val read = record.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
                        if (read < 0) {
                            TelecomSpike.log("sniff: read error=$read")
                            return@launch
                        }
                        if (read == 0) {
                            delay(10)
                            continue
                        }
                        filled += read
                    }
                    frames++
                    var sum = 0.0
                    var any = false
                    for (sample in frame) {
                        if (sample != 0.toShort()) any = true
                        sum += sample.toDouble() * sample
                    }
                    if (any) nonZero++
                    loudest = maxOf(loudest, min(1.0, sqrt(sum / frame.size) / 256.0 / 42.0))
                }
            } catch (e: Exception) {
                TelecomSpike.log("sniff: failed ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
                TelecomSpike.log(
                    "sniff: done no-foreground ${SECONDS}s frames=$frames nonZero=$nonZero loudest=${"%.3f".format(loudest)}",
                )
            }
        }
    }
}
