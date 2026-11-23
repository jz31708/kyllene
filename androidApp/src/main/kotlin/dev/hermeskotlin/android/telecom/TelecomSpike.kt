package dev.hermeskotlin.android.telecom

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The ConnectionService spike (`docs/board/call-while-locked.md`): the wrist's ask becomes a **call** on a
 * self-managed phone account, so telecom owns it — it survives the screen locking, it can appear in the system's
 * call UI (and on the watch, in the car, over Bluetooth), and the microphone belongs to a `phoneCall|microphone`
 * foreground service rather than to an app that has to be on screen.
 *
 * Every step is a `WT-CS` line, so `adb logcat -s WT-CS` on the phone proves the chain without a screenshot.
 * Spike only: nothing here is wired into the shipped call path.
 */
object TelecomSpike {

    const val TAG = "WT-CS"

    /** The account's id. Stable, so a restart finds the account it registered. */
    const val ACCOUNT_ID = "kyllene-board-spike"

    /** Why the call was placed, for the connection to log. */
    const val EXTRA_REASON = "dev.hermeskotlin.telecom.REASON"

    fun log(step: String) {
        Log.i(TAG, step)
    }

    private fun telecom(context: Context) = context.getSystemService(TelecomManager::class.java)

    /** The account this app registers. */
    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, BoardConnectionService::class.java), ACCOUNT_ID)

    /**
     * Registers the self-managed account once. Telecom keeps accounts across reboots, so registering again is
     * harmless; it is logged every time, so a missing registration is visible rather than guessed.
     */
    fun ensureAccount(context: Context): Boolean {
        val manager = telecom(context) ?: run {
            log("account: no TelecomManager on this device")
            return false
        }
        val manage = ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        log("account: manage_own_calls=$manage sdk=${Build.VERSION.SDK_INT}")
        val account = PhoneAccount.Builder(handle(context), "Kyllene Board")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
            .setShortDescription("Hermes on the phone's microphone")
            .build()
        val registered = runCatching { manager.registerPhoneAccount(account) }
            .onFailure { log("account: register threw ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
        val own = runCatching { manager.ownSelfManagedPhoneAccounts }.getOrNull().orEmpty()
        val known = own.any { it.id == ACCOUNT_ID }
        log("account: registered=$registered own=${own.size} known=$known")
        return registered && known
    }

    /**
     * Hands the wrist's ask to telecom as an incoming call on the self-managed account. False when telecom refuses
     * (no account, a real call already on, an OEM that never enabled self-managed accounts), which the caller shows
     * as the phone's old "open it yourself" line.
     */
    fun reportIncoming(context: Context, reason: String): Boolean {
        val manager = telecom(context) ?: return false.also { log("incoming: no TelecomManager") }
        if (!runCatching { ensureAccount(context) }.getOrDefault(false)) {
            return false.also { log("incoming: not reported, the account isn't there") }
        }
        val extras = Bundle().apply {
            putString(EXTRA_REASON, reason)
            // A call, so the audio goes to the earpiece/speaker rather than to media.
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
        }
        log("incoming: place reason=$reason")
        return runCatching {
            manager.addNewIncomingCall(handle(context), extras)
        }.fold(
            onSuccess = { log("incoming: placed"); true },
            onFailure = {
                log("incoming: refused ${it.javaClass.simpleName}: ${it.message}")
                false
            },
        )
    }

    /** Ends the spike's call from this side, which also takes the probe's service down. */
    fun end(context: Context, why: String) {
        log("end: $why")
        context.stopService(Intent(context, BoardCallAudioService::class.java))
    }
}
