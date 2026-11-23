package dev.hermeskotlin.android.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dev.hermeskotlin.android.HeraldActivity

/**
 * The spike's `ConnectionService`: telecom binds this when its self-managed account is given a call, and every
 * callback is a `WT-CS` line — the answer, the hold, the mute and the route the system's own call UI sends (on the
 * phone, on the watch, in the car) are the thing being measured, not assumed.
 *
 * The connection is self-managed: this app shows the call's own UI, which is what [Connection.onShowIncomingCallUi]
 * asks for. That callback is also the honest test of raising the app while the phone is locked.
 */
class BoardConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(handle: PhoneAccountHandle?, request: ConnectionRequest?): Connection? {
        TelecomSpike.log("connection: incoming request=${request?.extras?.getString(TelecomSpike.EXTRA_REASON)}")
        return board(request)
    }

    override fun onCreateIncomingConnectionFailed(handle: PhoneAccountHandle?, request: ConnectionRequest?) {
        TelecomSpike.log("connection: incoming refused by telecom")
    }

    override fun onCreateOutgoingConnection(handle: PhoneAccountHandle?, request: ConnectionRequest?): Connection? {
        TelecomSpike.log("connection: outgoing requested")
        return board(request)
    }

    override fun onCreateOutgoingConnectionFailed(handle: PhoneAccountHandle?, request: ConnectionRequest?) {
        TelecomSpike.log("connection: outgoing refused by telecom")
    }

    private fun board(request: ConnectionRequest?): Connection {
        val reason = request?.extras?.getString(TelecomSpike.EXTRA_REASON) ?: "unknown"
        val call = BoardConnection(applicationContext, reason).apply {
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            connectionCapabilities = Connection.CAPABILITY_MUTE or
                Connection.CAPABILITY_SUPPORT_HOLD or
                Connection.CAPABILITY_HOLD
            audioModeIsVoip = true
            setAddress(Uri.parse("tel:board"), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName("Kyllene Board", TelecomManager.PRESENTATION_ALLOWED)
            // A self-managed call starts ringing: that is what makes the system ask the app for its own call
            // screen (onShowIncomingCallUi), which is the thing being measured while the phone is locked.
            setRinging()
        }
        TelecomSpike.log("connection: created reason=$reason state=${call.state}")
        // The measurement: a phoneCall|microphone service started from here, with the phone in a pocket. If Android
        // refuses the start outright, the microphone is still tried for five seconds — that separates "no
        // microphone in this state" from "no foreground service in this state".
        if (!BoardCallAudioService.start(applicationContext, reason)) {
            MicSniff.sniff("the probe's service was refused, so the connection has no foreground service")
        }
        // Nothing answers a call nobody can see: the spike ends it rather than leaving a ringing call on the phone.
        call.autoEndAfter(60_000L)
        return call
    }
}

/** The call itself, as telecom sees it. Every system-driven change is logged; ending it takes the probe down. */
class BoardConnection(private val context: Context, private val reason: String) : Connection() {

    init {
        TelecomSpike.log("call: attached reason=$reason")
    }

    /** A self-managed call's own UI: the system asks the app for it, which is the documented way up from the lock. */
    override fun onShowIncomingCallUi() {
        TelecomSpike.log("call: show-incoming-ui (the system asked for the app's call screen)")
        raiseCallScreen()
    }

    /**
     * The app's own call screen, asked for by the system. Whether Android lets a background app raise it over the
     * keyguard is one of the things being measured, so the answer is logged rather than assumed.
     */
    private fun raiseCallScreen() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_CHAT_LINK), context, HeraldActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }.fold(
            onSuccess = { TelecomSpike.log("ui: call screen started") },
            onFailure = { TelecomSpike.log("ui: call screen refused ${it.javaClass.simpleName}: ${it.message}") },
        )
    }

    override fun onAnswer() {
        TelecomSpike.log("call: answer from the system's UI")
        setActive()
        raiseCallScreen()
    }

    override fun onReject() {
        TelecomSpike.log("call: reject from the system's UI")
        finish(DisconnectCause.REJECTED, "rejected")
    }

    override fun onDisconnect() {
        TelecomSpike.log("call: disconnect from the system's UI")
        finish(DisconnectCause.LOCAL, "disconnected")
    }

    override fun onAbort() {
        TelecomSpike.log("call: abort")
        finish(DisconnectCause.CANCELED, "aborted")
    }

    /** Ended after [delayMs] unless someone answered, so a locked phone doesn't leave a live call behind. */
    fun autoEndAfter(delayMs: Long) {
        AUTO_END.postDelayed(
            {
                if (state == STATE_RINGING || state == STATE_INITIALIZING) {
                    TelecomSpike.log("call: unanswered after ${delayMs}ms, ending it")
                    finish(DisconnectCause.CANCELED, "unanswered")
                }
            },
            delayMs,
        )
    }

    /** Over: the call is told, the probe goes with it. */
    private fun finish(code: Int, why: String) {
        TelecomSpike.log("call: over ($why)")
        AUTO_END.removeCallbacksAndMessages(null)
        setDisconnected(DisconnectCause(code))
        destroy()
        BoardCallAudioService.stop()
    }

    override fun onHold() {
        TelecomSpike.log("call: hold from the system's UI")
        setOnHold()
    }

    override fun onUnhold() {
        TelecomSpike.log("call: unhold from the system's UI")
        setActive()
    }

    override fun onMuteStateChanged(muted: Boolean) {
        TelecomSpike.log("call: mute=$muted from the system's UI")
    }

    override fun onCallAudioStateChanged(state: CallAudioState?) {
        TelecomSpike.log("call: audio-state route=${state?.route} muted=${state?.isMuted}")
    }

    private companion object {
        /** The app's own call: the same link the wrist's notification and the app's own button use. */
        const val VOICE_CHAT_LINK = "hermes://voice-chat"
        const val UNANSWERED_MS = 60_000L
        val AUTO_END = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
