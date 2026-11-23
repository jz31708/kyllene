package dev.hermeskotlin.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * The speaker for a chained voice call, held for one speech at a time: taken when a reply or a ring starts and let go
 * when it ends, so navigation, a phone call and a safety alert are heard over the line. A loss pauses the speech where
 * it is rather than ducking it — turned down under the directions, the agent would only be talked over — and the gain
 * that follows lets it go on from there. A loss for good gives the speaker up, and the next speech asks for it again.
 *
 * A phone call ringing or in progress holds the speech too, even where Android hasn't asked it to step aside: the mode
 * says so on every device, and the call state where READ_PHONE_STATE happens to be granted. While a speech waits for
 * the speaker it looks again once a second, so a call that ends without handing the focus back doesn't leave it silent.
 *
 * [acquire] and [release] are safe from any thread and any number of times; [onFree] only hears of a change.
 */
class CallAudioFocus(
    context: Context,
    /** Whether speech may play now ([true]) or has to hold ([false]). */
    private val onFree: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext ?: context
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** A speech asked for the speaker at [acquire] and hasn't [release]d it. */
    private var session = false
    /** The focus held, null when none is: never granted, or given up for good. */
    private var request: AudioFocusRequest? = null
    /** What was last reported, so an unchanged state reaches [onFree] only once; null between speeches. */
    private var reported: Boolean? = null
    /** What the phone says about its own call, from the callback while the permission for it was granted. */
    private var callState = TelephonyManager.CALL_STATE_IDLE
    private var phoneManager: TelephonyManager? = null
    private var phoneCallback: PhoneCalls? = null

    /** Looks again while a speech waits for a speaker it couldn't take. */
    private val lookAgain = object : Runnable {
        override fun run() {
            val free = synchronized(lock) {
                if (!session) return
                (request != null || takeFocus()) && freeOfCalls()
            }
            report(free)
            if (!free) again()
        }
    }

    /**
     * Asks for the speaker if no speech has it already; true when speech may play now. False while another app has it
     * or a phone call is ringing or in progress, and the speech waits as it would after a loss.
     */
    fun acquire(): Boolean {
        val free = synchronized(lock) {
            session = true
            (request != null || takeFocus()) && freeOfCalls()
        }
        report(free)
        if (!free) again()
        return free
    }

    /** Lets the speaker go; the next speech asks for it again. Safe to call when none was held. */
    fun release() {
        main.removeCallbacks(lookAgain)
        val held = synchronized(lock) {
            session = false
            reported = null
            val held = request
            request = null
            unwatchCalls()
            held
        }
        if (held != null) runCatching { audioManager.abandonAudioFocusRequest(held) }
    }

    /** Tells the player what changed, never the same state twice. */
    private fun report(free: Boolean) {
        val changed = synchronized(lock) {
            if (!session || reported == free) {
                false
            } else {
                reported = free
                true
            }
        }
        if (!changed) return
        if (free) main.removeCallbacks(lookAgain)
        onFree(free)
    }

    /** Asks for the speaker; called with [lock] held. False when the system won't give it. */
    private fun takeFocus(): Boolean {
        val wanted = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Lost for a moment, the line pauses and goes on afterwards; the system needn't talk the agent down.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::focusChanged)
            .build()
        val granted = runCatching { audioManager.requestAudioFocus(wanted) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            request = wanted
            watchCalls()
        }
        return granted
    }

    /** The system gave the speaker back, took it for a moment, or took it for good. */
    private fun focusChanged(change: Int) {
        if (!synchronized(lock) { session }) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                val free = freeOfCalls()
                report(free)
                if (!free) again()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                report(false)
                // Lost to a phone call, the speaker comes back when the call ends; lost to another app for a moment,
                // its gain brings the line back by itself.
                if (!synchronized(lock) { freeOfCalls() }) again()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                report(false)
                // Taken for good: the speaker goes back rather than talking over whatever took it, and the speech
                // waits — a call that ends without handing focus back must not leave the line silent for the rest
                // of the conversation.
                giveUp()
                again()
            }
        }
    }

    /** Lets the request go without ending the speech: the player stays held until it's cancelled or let go. */
    private fun giveUp() {
        main.removeCallbacks(lookAgain)
        val held = synchronized(lock) {
            val held = request
            request = null
            unwatchCalls()
            held
        }
        if (held != null) runCatching { audioManager.abandonAudioFocusRequest(held) }
    }

    /** Looks again in a second while a speech waits. */
    private fun again() {
        main.removeCallbacks(lookAgain)
        main.postDelayed(lookAgain, RECHECK_MS)
    }

    /** True unless a phone call is ringing or in progress, or a call's mode has the audio. */
    private fun freeOfCalls(): Boolean {
        if (callState == TelephonyManager.CALL_STATE_RINGING || callState == TelephonyManager.CALL_STATE_OFFHOOK) return false
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        // MODE_IN_COMMUNICATION is this app's own chained call, which is what the speaker was asked for.
        return mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_RINGTONE
    }

    /**
     * Watches the phone's own calls where the permission to read them happens to be granted. Never asks for it: the
     * mode alone holds the line everywhere, and the app runs on a phone whose owner didn't give it one.
     */
    @SuppressLint("MissingPermission") // Checked at the top, and a system that refuses fails without a sound.
    @Suppress("DEPRECATION") // The callback is the modern way; the one read that seeds it is still this.
    private fun watchCalls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || phoneCallback != null) return
        val granted = runCatching {
            appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) return
        val manager = runCatching { appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager }.getOrNull() ?: return
        val callback = PhoneCalls(::callChanged)
        try {
            manager.registerTelephonyCallback(appContext.mainExecutor, callback)
        } catch (_: SecurityException) {
            return
        } catch (_: Exception) {
            return
        }
        phoneManager = manager
        phoneCallback = callback
        // A call already ringing or in progress doesn't send its state again.
        callChanged(runCatching { manager.callState }.getOrDefault(TelephonyManager.CALL_STATE_IDLE))
    }

    /** Called with [lock] held. */
    private fun unwatchCalls() {
        val manager = phoneManager
        val callback = phoneCallback
        phoneManager = null
        phoneCallback = null
        if (manager != null && callback != null) runCatching { manager.unregisterTelephonyCallback(callback) }
    }

    /** The phone's own call started ringing, went offhook or ended: the speech pauses or goes on. */
    private fun callChanged(state: Int) {
        val watching = synchronized(lock) {
            callState = state
            session && request != null
        }
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> if (watching) report(false)
            TelephonyManager.CALL_STATE_IDLE -> if (watching) {
                val free = freeOfCalls()
                report(free)
                if (!free) again()
            }
        }
    }

    /** The phone's own call state on Android 12 and later, where the permission to read it was granted. */
    private class PhoneCalls(private val onState: (Int) -> Unit) :
        TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onState(state)
    }

    private companion object {
        /** How long a speech waiting for the speaker waits before looking again. */
        const val RECHECK_MS = 1_000L
    }
}
