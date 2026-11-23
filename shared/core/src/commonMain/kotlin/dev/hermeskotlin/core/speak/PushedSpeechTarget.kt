package dev.hermeskotlin.core.speak

/**
 * What the open voice call offers the app's speak queue (`worktable-spec.md` §11.3): the floor as the call
 * sees it, and the way to say a line the owner did not ask for.
 *
 * The phone's queue lives at app level, but only a chained call can render a seat's words — it holds the TTS
 * lease and the speaker. While no call implements this, a pushed line becomes a notification instead.
 */
interface PushedSpeechTarget {
    /** A chained call is open and can take speech. */
    val callActive: Boolean

    /** The owner is talking, so nothing the app says may start. */
    val personSpeaking: Boolean

    /** The app may take the speaker now: no navigation, phone call or other app holds audio focus. */
    val audioFree: Boolean

    /** The app itself is already speaking (a reply, a read-aloud). */
    val busy: Boolean

    /** The call's id when the gateway gave it one; items bound to another call wait. */
    val callId: String?

    /**
     * The wrist's mic button: it toggles the **phone's** microphone, because the Oppo records and the watch
     * never opens one for the Board. A target with no microphone does nothing.
     */
    fun toggleMic() = Unit

    /**
     * Says [item] in the call's voice, verbatim, and reports how many characters have been audible.
     * Returns how it ended; [SpeakOutcome.Stopped] means barge-in or Skip cut it short.
     */
    suspend fun sayPushed(item: SpeakItem, onHeard: (Int) -> Unit): SpeakOutcome
}

/**
 * The one open call, if any. Set when a chained chat starts and cleared when that same chat ends, so a
 * newer call on another screen never has its floor taken by an older one winding down.
 */
object PushedSpeechTargets {
    @kotlin.concurrent.Volatile
    var current: PushedSpeechTarget? = null
        private set

    /** Claims the floor for [target]; only that target may release it. */
    fun register(target: PushedSpeechTarget) {
        current = target
    }

    fun release(target: PushedSpeechTarget) {
        if (current === target) current = null
    }
}
