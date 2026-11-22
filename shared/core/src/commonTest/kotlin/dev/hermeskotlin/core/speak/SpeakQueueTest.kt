package dev.hermeskotlin.core.speak

import dev.hermeskotlin.core.push.PushMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakQueueTest {
    private fun item(
        id: String = "s1",
        text: String = "The Lenovo queue is drained.",
        priority: SpeakPriority = SpeakPriority.WhenReady,
        call: String? = null,
        intent: String? = null,
        part: Int = 1,
        rev: Long? = null,
        expiresOnRev: Long? = null,
        expires: Long? = null,
        at: Long = 1,
    ) = SpeakItem(id, text, priority, call = call, intent = intent, part = part, rev = rev, expiresOnRev = expiresOnRev, expires = expires, at = at)

    private val floor = SpeakFloor(callActive = true)

    @Test
    fun `a repeat is refused`() {
        val queue = SpeakQueue()
        assertTrue(queue.offer(item()))
        assertFalse(queue.offer(item()))
        assertEquals(1, queue.queued.size)
    }

    @Test
    fun `an empty utterance is refused`() {
        assertFalse(SpeakQueue().offer(item(text = "   ")))
    }

    @Test
    fun `answer now goes before what was queued earlier`() {
        val queue = SpeakQueue()
        queue.offer(item(id = "first", at = 1))
        queue.offer(item(id = "urgent", priority = SpeakPriority.AnswerNow, at = 2))
        assertEquals("urgent", queue.next(floor)?.id)
    }

    @Test
    fun `the parts of one answer stay in order`() {
        val queue = SpeakQueue()
        queue.offer(item(id = "p2", part = 2, at = 2))
        queue.offer(item(id = "p1", part = 1, at = 1))
        assertEquals(listOf("p1", "p2"), queue.queued.map { it.id })
    }

    @Test
    fun `nothing is said without an active call, over the person, over other speech, or without audio`() {
        val queue = SpeakQueue()
        queue.offer(item())
        assertNull(queue.next(SpeakFloor(callActive = false)))
        assertNull(queue.next(SpeakFloor(callActive = true, personSpeaking = true)))
        assertNull(queue.next(SpeakFloor(callActive = true, busy = true)))
        assertNull(queue.next(SpeakFloor(callActive = true, audioFree = false)))
        assertEquals("s1", queue.next(floor)?.id)
    }

    @Test
    fun `one item at a time`() {
        val queue = SpeakQueue()
        queue.offer(item(id = "s1"))
        queue.offer(item(id = "s2", at = 2))
        assertEquals("s1", queue.next(floor)?.id)
        assertNull(queue.next(floor))
        queue.finished()
        assertEquals("s2", queue.next(floor)?.id)
    }

    @Test
    fun `what another call was told waits for its own call`() {
        val queue = SpeakQueue()
        queue.offer(item(id = "s1", call = "call-b"))
        assertNull(queue.next(SpeakFloor(callActive = true, call = "call-a")))
        // A phone that doesn't know which call it is on does not guess.
        assertNull(queue.next(floor))
        assertEquals("s1", queue.next(SpeakFloor(callActive = true, call = "call-b"))?.id)
    }

    @Test
    fun `an item bound to no call is said in any call`() {
        val queue = SpeakQueue()
        queue.offer(item())
        assertEquals("s1", queue.next(SpeakFloor(callActive = true, call = "call-a"))?.id)
    }

    @Test
    fun `an expired item is never started nor queued`() {
        val queue = SpeakQueue()
        queue.offer(item(expires = 100))
        assertFalse(queue.offer(item(id = "late", expires = 50), now = 60))
        assertNull(queue.next(floor, now = 100))
        assertEquals("s1", queue.next(floor, now = 99)?.id)
    }

    @Test
    fun `a correction retires what it overtook and keeps what was heard`() {
        val queue = SpeakQueue()
        queue.offer(item(id = "old", expiresOnRev = 3, at = 1))
        queue.offer(item(id = "now", rev = 3, expiresOnRev = 3, at = 2))
        assertEquals("old", queue.next(floor)?.id)
        queue.hear(11)
        val expired = queue.advanceTo(4)
        // The item playing is not cut off mid-sentence here: finishing says how much was heard.
        assertEquals(listOf("now"), expired.map { it.id })
        val ack = queue.finished(SpeakOutcome.Stopped)
        assertEquals(11, ack?.heard)
        assertEquals(SpeakOutcome.Stopped, ack?.outcome)
    }

    @Test
    fun `an item that arrives after its own revision is refused`() {
        val queue = SpeakQueue()
        queue.advanceTo(9)
        assertFalse(queue.offer(item(expiresOnRev = 8)))
    }

    @Test
    fun `a correction drops the rest of its own intent`() {
        val queue = SpeakQueue()
        queue.offer(item(id = "a", intent = "i1", part = 1, at = 1))
        queue.offer(item(id = "b", intent = "i1", part = 2, at = 2))
        queue.offer(item(id = "c", intent = "i2", at = 3))
        assertEquals(listOf("a", "b"), queue.supersede("a").map { it.id })
        assertEquals(listOf("c"), queue.queued.map { it.id })
    }

    @Test
    fun `a flood drops news before answers`() {
        val queue = SpeakQueue(limit = 2)
        queue.offer(item(id = "answer", priority = SpeakPriority.AnswerNow, at = 1))
        queue.offer(item(id = "news-1", priority = SpeakPriority.Fyi, at = 2))
        queue.offer(item(id = "news-2", priority = SpeakPriority.Fyi, at = 3))
        assertEquals(listOf("answer", "news-1"), queue.queued.map { it.id })
    }

    @Test
    fun `hearing cannot run past the words`() {
        val queue = SpeakQueue()
        queue.offer(item(text = "short"))
        queue.next(floor)
        queue.hear(99)
        assertEquals(5, queue.playedChars)
    }

    @Test
    fun `hanging up drops the queue and says so`() {
        val queue = SpeakQueue()
        queue.offer(item(id = "s1"))
        queue.offer(item(id = "s2", at = 2))
        assertEquals(listOf("s1", "s2"), queue.clear().map { it.id })
        assertTrue(queue.queued.isEmpty())
    }

    @Test
    fun `an older revision does not move the queue back`() {
        val queue = SpeakQueue()
        queue.advanceTo(5)
        assertTrue(queue.advanceTo(4).isEmpty())
        assertEquals(5L, queue.rev)
    }

    @Test
    fun `a speak push becomes an item and other pushes do not`() {
        val message = PushMessage(
            id = "p1", ts = 1, type = PushMessage.SPEAK, bot = "board", label = "Board", session = "s-1",
            text = " Two decisions wait. ", priority = "answer_now", call = "c1", rev = 7, expiresOnRev = 7,
            intent = "i-1", utterance = "u-1", part = 2, parts = 3,
        )
        val item = SpeakItem.from(message)
        assertEquals("Two decisions wait.", item?.text)
        assertEquals(SpeakPriority.AnswerNow, item?.priority)
        assertEquals("board", item?.seat)
        assertEquals("c1", item?.call)
        assertEquals("i-1", item?.intent)
        assertEquals("2 of 3", item?.place)
        assertEquals(7L, item?.expiresOnRev)
        assertNull(SpeakItem.from(message.copy(type = PushMessage.BOT_MESSAGE)))
        assertNull(SpeakItem.from(message.copy(text = " ")))
    }

    @Test
    fun `an unknown priority is said when ready`() {
        val message = PushMessage(id = "p1", ts = 1, type = PushMessage.SPEAK, text = "Hi", priority = "now-ish")
        assertEquals(SpeakPriority.WhenReady, SpeakItem.from(message)?.priority)
    }

    @Test
    fun `a corrected answer only says what was not heard`() {
        assertEquals("and the queue is empty.", SpeakText.remainder("Two decisions wait and the queue is empty.", "Two decisions wait"))
        assertTrue(SpeakText.continues("Two decisions wait and the queue is empty.", "Two decisions wait"))
        // A rewrite is said whole, after a cue.
        assertEquals("Three decisions wait.", SpeakText.remainder("Three decisions wait.", "Two decisions wait"))
        assertFalse(SpeakText.continues("Three decisions wait.", "Two decisions wait"))
        assertEquals("Anything.", SpeakText.remainder("Anything.", ""))
    }
}
