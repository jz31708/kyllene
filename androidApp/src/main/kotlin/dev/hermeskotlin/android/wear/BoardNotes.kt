package dev.hermeskotlin.android.wear

import android.content.Context
import android.util.Log
import dev.hermeskotlin.wearlink.NoteSaved
import dev.hermeskotlin.wearlink.QuickNote
import dev.hermeskotlin.wearlink.WearJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/**
 * The Board's microphone, as far as the watch's toggle goes. The real one belongs to whatever holds the Board's
 * call: with no call on, it does nothing, and the watch is told the note was saved all the same.
 */
fun interface BoardMic {
    fun toggle()

    companion object {
        /** No Board call on the phone, or nothing wired to it yet. */
        val None: BoardMic = BoardMic { }
    }
}

/** One saved note, as the queue keeps it: what was said, and when the phone received it. */
@Serializable
private data class StoredNote(
    val id: String,
    val text: String,
    val capturedAt: Long,
    val boardId: String? = null,
    val seatId: String? = null,
)

/**
 * What the watch's notes become on the phone: a local queue in `filesDir/board-notes.jsonl`, one JSON object a
 * line, until the Board acts on it. A note with no words is not a note at all: it asks for the phone's own
 * microphone to be toggled, which is the whole of "mute" on the wrist — the watch records nothing.
 *
 * [mic] is the seam the call side plugs into; unset, a toggle is a no-op and still answered, so the watch's
 * screen never hangs on it.
 */
class BoardNotes(
    context: Context,
    mic: BoardMic = BoardMic.None,
    /** Hands words to the Board as owner words; false keeps them in the local queue for the next try. */
    private val deliver: suspend (text: String, capturedAt: Long) -> Boolean = { _, _ -> true },
) {

    /** The Board's microphone; whoever holds the call may set it, and set it back when the call ends. */
    @Volatile
    var mic: BoardMic = mic

    private val file = File(context.filesDir, NOTES_FILE)
    private val lock = Any()

    /** Notes this process already kept, so a message that arrives twice isn't written twice. */
    private val seen = LinkedHashSet<String>()

    init {
        // A restart: ids from the end of the queue are enough to refuse the one that was being retried.
        runCatching {
            file.takeIf { it.exists() }?.useLines { lines ->
                lines.toList().takeLast(RECENT_IDS).forEach { line ->
                    WearJson.decode<StoredNote>(line.encodeToByteArray())?.let { seen += it.id }
                }
            }
        }
    }

    /**
     * Saves [note]'s words, or toggles the phone's microphone when it has none, and says how many notes wait.
     * Never throws: a queue that can't be written is an answer the watch can show, not a crash in the service.
     */
    suspend fun receive(note: QuickNote): NoteSaved = withContext(Dispatchers.IO) {
        if (note.text.isBlank()) {
            Log.i(WatchBoardLog.TAG, "BoardNotes.receive id=${note.id}: blank, toggling the phone's mic")
            val outcome = runCatching { mic.toggle() }
            Log.i(WatchBoardLog.TAG, "BoardNotes.receive id=${note.id}: mic.toggle done, error=${outcome.exceptionOrNull()?.message}")
            return@withContext NoteSaved(note.id, saved = true, pendingNotes = pending())
        }
        if (synchronized(lock) { note.id in seen }) {
            return@withContext NoteSaved(note.id, saved = true, pendingNotes = pending())
        }
        // What waited behind an offline phone goes first, in order; then this one.
        retryQueued()
        val stored = StoredNote(note.id, note.text, System.currentTimeMillis(), note.boardId, note.seatId)
        val taken = runCatching { deliver(stored.text, stored.capturedAt) }.getOrDefault(false)
        synchronized(lock) {
            seen += note.id
            if (seen.size > RECENT_IDS) seen.remove(seen.first())
            if (!taken) append(stored)
            NoteSaved(note.id, saved = true, pendingNotes = pending(), error = if (taken) null else "Kept on the phone")
        }
    }

    /** Tries the notes a previous delivery left behind; one that still fails stays, and the rest wait their turn. */
    private suspend fun retryQueued() {
        val waiting = synchronized(lock) { readAll() }
        if (waiting.isEmpty()) return
        val left = mutableListOf<StoredNote>()
        var blocked = false
        for (stored in waiting) {
            if (blocked) { left += stored; continue }
            val taken = runCatching { deliver(stored.text, stored.capturedAt) }.getOrDefault(false)
            if (!taken) { blocked = true; left += stored }
        }
        synchronized(lock) { rewrite(left) }
    }

    private fun append(stored: StoredNote) {
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { out ->
                out.write(WearJson.encode(stored))
                out.write('\n'.code)
            }
        }
    }

    private fun readAll(): List<StoredNote> = runCatching {
        file.takeIf { it.exists() }?.useLines { lines ->
            lines.mapNotNull { WearJson.decode<StoredNote>(it.encodeToByteArray()) }.toList()
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun rewrite(notes: List<StoredNote>) {
        runCatching {
            if (notes.isEmpty()) file.delete() else file.writeText(notes.joinToString("\n") { WearJson.encode(it).decodeToString() } + "\n")
        }
    }

    /** The notes waiting in the queue. */
    fun pending(): Int = runCatching { file.takeIf { it.exists() }?.useLines { lines -> lines.count { it.isNotBlank() } } ?: 0 }.getOrDefault(0)

    private companion object {
        const val NOTES_FILE = "board-notes.jsonl"
        const val RECENT_IDS = 200
    }
}
