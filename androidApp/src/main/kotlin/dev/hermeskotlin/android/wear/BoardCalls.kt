package dev.hermeskotlin.android.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.android.R
import dev.hermeskotlin.android.notify.AppVisibility
import dev.hermeskotlin.android.telecom.TelecomSpike
import dev.hermeskotlin.core.speak.PushedSpeechTargets
import dev.hermeskotlin.wearlink.BoardCallRequest

/**
 * The phone's end of the Board call the wrist asks for (`docs/board/watch-plan.md` §2): the **Oppo** records and
 * streams, the watch sends start and stop, and the watch renders what the phone reports.
 *
 * A start is the app's own voice chat on the Default Call Target's chat, so the phone does what Thomas would have
 * done there: Kyllene is raised and the chat opens its call. Android won't let a background service raise an
 * activity by itself, so when Kyllene is out of sight the phone says so with a notification whose tap opens the
 * same call, and the watch shows its "Open Kyllene on your phone to record" line. A start with a call already on
 * changes nothing; a stop hangs up the call the phone holds.
 */
class BoardCalls(
    private val context: Context,
    private val visibility: AppVisibility,
) {

    /**
     * The phone couldn't raise its own call, so the wrist shows "Open Oppo to record" and the next tap asks again.
     * Cleared when a call really opens ([opened]).
     */
    @Volatile
    var fallback: String? = null
        private set

    /** The phone's call is on: the fallback line is no longer true. */
    fun opened() {
        fallback = null
    }

    /** What the watch asked: start its Board call, or hang it up. */
    fun requested(request: BoardCallRequest) {
        val target = PushedSpeechTargets.current
        Log.i(TAG, "BoardCalls.requested ${request.action} id=${request.id} target=${target != null} active=${target?.callActive}")
        if (request.stopping) {
            target?.hangUp()
            Log.i(TAG, "BoardCalls: hangUp done, target still=${PushedSpeechTargets.current != null}")
        } else {
            start()
        }
    }

    /**
     * Starts the phone's Board call: the voice chat of the Default Call Target, with the Oppo's microphone.
     *
     * The spike puts the ask to telecom first, as a call on a self-managed account, so the phone is in a call the
     * system knows about — the measurement is whether its microphone works from there with the screen locked
     * (`WT-CS`, `docs/board/call-while-locked.md`). The app's own way in is unchanged below it.
     */
    fun start() {
        Log.i(TAG, "BoardCalls.start: visible=${visibility.visible.value}")
        if (visibility.visible.value) {
            // In sight, the app's own way in is the one being tested elsewhere; the spike doesn't touch it.
            open()
            return
        }
        // Out of sight — a pocket, a locked screen — is the case the spike is for: telecom is asked for a real call.
        val reported = TelecomSpike.reportIncoming(context, reason = "watch")
        Log.i(TAG, "BoardCalls.start: telecom reported=$reported")
        notice()
    }

    /** Opens Kyllene on the call's chat; the app's own `hermes://voice-chat` link is what starts the call there. */
    private fun open() {
        val started = runCatching { context.startActivity(callIntent()) }.isSuccess
        Log.i(TAG, "BoardCalls.open: started=$started")
        if (!started) notice()
    }

    private fun callIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_CHAT_LINK), context, HeraldActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Out of sight: a quiet notification, tapped to open the same call. Without that the phone would sit with no
     * sign it heard the wrist at all.
     */
    private fun notice() {
        Log.i(TAG, "BoardCalls.notice: posting the open-oppo notification")
        fallback = FALLBACK_OPEN_OPPO
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_board_name))
                .setDescription(context.getString(R.string.channel_board_description))
                .build(),
        )
        val tap = PendingIntent.getActivity(
            context,
            NOTICE_ID,
            callIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.board_call_notice_title))
            .setContentText(context.getString(R.string.board_call_notice_text))
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTICE_ID, notification) }
    }

    private companion object {
        const val TAG = WatchBoardLog.TAG
        const val CHANNEL = "board_calls"
        const val NOTICE_ID = 4_120
        const val VOICE_CHAT_LINK = "hermes://voice-chat"

        /** What the wrist shows, in the reference watch's exact words, when the phone can't take the call. */
        const val FALLBACK_OPEN_OPPO = "open_oppo_to_record"
    }
}
