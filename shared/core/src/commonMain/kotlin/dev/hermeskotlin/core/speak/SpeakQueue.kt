package dev.hermeskotlin.core.speak

import dev.hermeskotlin.core.push.PushMessage

/**
 * One utterance a seat asked this phone to say aloud, as it arrives on the push channel
 * (`docs/board/server-push.md`). The seat chooses every word; the phone only decides when it is heard.
 */
data class SpeakItem(
    /** The push message's id: what makes a redelivered utterance a repeat rather than a second one. */
    val id: String,
    val text: String,
    val priority: SpeakPriority = SpeakPriority.WhenReady,
    /** The profile whose voice says it (`board`, `travel-deals`): the seat. */
    val seat: String? = null,
    /** The seat's label for the notification and the watch. */
    val label: String? = null,
    /** The stored session the words belong to, so the call shows the thread they came from. */
    val session: String? = null,
    /** The call the gateway bound this to; null means any active call may say it. */
    val call: String? = null,
    /** The intent revision the words belong to, when the gateway says. */
    val rev: Long? = null,
    /** Past this revision the words are no longer true and must not be started. */
    val expiresOnRev: Long? = null,
    /** When the gateway queued it, unix seconds. */
    val at: Long = 0,
) {
    /** Whether a newer revision has overtaken these words. */
    fun staleAt(revision: Long?): Boolean = expiresOnRev != null && revision != null && expiresOnRev < revision

    companion object {
        /** The utterance inside a `speak` push, or null for a message that isn't one the phone can say. */
        fun from(message: PushMessage): SpeakItem? {
            if (message.type != PushMessage.SPEAK) return null
            val text = message.text?.trim().orEmpty()
            if (text.isEmpty()) return null
            return SpeakItem(
                id = message.id,
                text = text,
                priority = SpeakPriority.of(message.priority),
                seat = message.bot,
                label = message.label,
                session = message.session,
                call = message.call,
                rev = message.rev,
                expiresOnRev = message.expiresOnRev,
                at = message.ts,
            )
        }
    }
}

/** How urgent a spoken item is. A person waiting for an answer comes first. */
enum class SpeakPriority(val wire: String) {
    AnswerNow("answer_now"),
    WhenReady("when_ready"),
    Fyi("fyi");

    companion object {
        fun of(wire: String?): SpeakPriority = entries.firstOrNull { it.wire == wire } ?: WhenReady
    }
}

/** How an item left the queue, for the acknowledgement the gateway reads. */
enum class SpeakOutcome(val wire: String) {
    Played("played"),
    Stopped("stopped"),
    Expired("expired"),
    Skipped("skipped"),
}

/**
 * Where the phone is, as the floor rules see it (`worktable-spec.md` §11.3): speech drains only while a call
 * is active, the person isn't speaking, and audio focus is the app's to take.
 */
data class SpeakFloor(
    val callActive: Boolean = false,
    val personSpeaking: Boolean = false,
    val audioFree: Boolean = true,
    /** The call's id, when the gateway gave the call one; items bound to another call wait. */
    val call: String? = null,
)

/** What happened to an utterance: [heard] characters were audible before it ended. */
data class SpeakAck(val id: String, val heard: Int, val outcome: SpeakOutcome)

/**
 * The phone's end of the speak queue: what seats said out of turn waits here until the floor lets it play.
 *
 * One queue per app. Not thread-safe by itself: the caller keeps it on one thread (the main thread in the app,
 * a test's own thread in tests), which is also what keeps an item from being spoken twice.
 */
class SpeakQueue(private val limit: Int = MAX_ITEMS) {
    private val waiting = mutableListOf<SpeakItem>()
    private val seen = LinkedHashSet<String>()
    private var speaking: SpeakItem? = null
    private var heard = 0
    private var revision: Long? = null

    /** What is being said now, if anything, and how much of it has been heard. */
    val playing: SpeakItem? get() = speaking

    /** How many characters of [playing] have been audible. */
    val playedChars: Int get() = heard

    /** What waits, in the order it would be said after the item playing now. */
    val queued: List<SpeakItem> get() = waiting.sortedWith(ORDER)

    /** The intent revision the queue follows. */
    val rev: Long? get() = revision

    /**
     * Takes an utterance from the gateway (or from a seat's tool call). A repeat of one already queued, played
     * or overtaken by a newer revision is refused, and so is one that arrives after its own revision.
     */
    fun offer(item: SpeakItem): Boolean {
        if (item.text.isBlank()) return false
        if (item.id in seen) return false
        if (item.staleAt(revision)) return false
        remember(item.id)
        waiting += item
        // The oldest news goes first when a gateway floods the queue: what a person asked for stays.
        while (waiting.size > limit) {
            val fyi = waiting.indexOfLast { it.priority == SpeakPriority.Fyi }
            waiting.removeAt(if (fyi >= 0) fyi else waiting.lastIndex)
        }
        return true
    }

    /**
     * Moves to [revision] because something was corrected somewhere. Items bound to an older revision are
     * retired; what was already heard of the playing one is kept, and it stops after the current sentence.
     * Returns the acknowledgements for the items that never started.
     */
    fun advanceTo(revision: Long): List<SpeakAck> {
        if (this.revision != null && revision <= this.revision!!) return emptyList()
        this.revision = revision
        val retired = waiting.filter { it.staleAt(revision) }
        waiting.removeAll(retired)
        return retired.map { SpeakAck(it.id, 0, SpeakOutcome.Expired) }
    }

    /** What should be said now, or null while the floor is someone else's. */
    fun next(floor: SpeakFloor): SpeakItem? {
        if (speaking != null) return null
        if (!floor.callActive || floor.personSpeaking || !floor.audioFree) return null
        val ready = waiting.sortedWith(ORDER).firstOrNull { item ->
            !item.staleAt(revision) && (item.call == null || floor.call == null || item.call == floor.call)
        } ?: return null
        waiting.remove(ready)
        speaking = ready
        heard = 0
        return ready
    }

    /** Records how much of the playing item has been audible, up to a known character position. */
    fun hear(chars: Int) {
        if (speaking == null) return
        heard = chars.coerceIn(heard, speaking!!.text.length)
    }

    /**
     * Ends the playing item — it finished, the person stopped it, or the call ended — and says how much was
     * heard so the gateway can keep a partly heard answer.
     */
    fun finished(outcome: SpeakOutcome = SpeakOutcome.Played): SpeakAck? {
        val item = speaking ?: return null
        speaking = null
        val ack = SpeakAck(item.id, heard, outcome)
        heard = 0
        return ack
    }

    /** Drops everything queued, for hanging up: unheard items stay in the seat's thread, not here. */
    fun clear(): List<SpeakAck> {
        val dropped = waiting.map { SpeakAck(it.id, 0, SpeakOutcome.Expired) }
        waiting.clear()
        return dropped
    }

    private fun remember(id: String) {
        seen += id
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    private companion object {
        const val MAX_ITEMS = 8
        const val MAX_SEEN = 64

        /** Answer now first; otherwise the order they arrived in. */
        val ORDER: Comparator<SpeakItem> = compareBy({ it.priority.ordinal }, { it.at }, { it.id })
    }
}
