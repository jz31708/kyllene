package dev.hermeskotlin.android.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.wearlink.BoardState
import dev.hermeskotlin.wearlink.HapticFired
import dev.hermeskotlin.wearlink.NoteSaved
import dev.hermeskotlin.wearlink.SeatSpeech
import dev.hermeskotlin.wearlink.TilesState
import dev.hermeskotlin.wearlink.WearCapabilities
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The Board's phone side: it publishes the tiles and the floor to Herald's watches, answers a watch that asks
 * for them (that round trip is what anchors the watch's clock for the speech cue), and takes the watch's note
 * answers and haptic evidence back.
 *
 * Everything here is safe with no watch at all: the reachable set is empty, every send is best-effort, and the
 * latest state is kept so a watch that connects later gets it on its next ask.
 */
class BoardSync(
    private val context: Context,
    private val outbox: WatchOutbox,
    private val scope: CoroutineScope,
) {

    private val capabilities = Wearable.getCapabilityClient(context)
    private val prefs = context.getSharedPreferences("board_sync", Context.MODE_PRIVATE)

    /** Watches advertising Herald; the capability can take minutes to arrive. */
    @Volatile
    private var advertised: Set<String> = emptySet()

    /** Watches Herald heard from: whatever the capabilities say, Herald runs there. */
    @Volatile
    private var heard: Set<String> = prefs.getStringSet(HEARD, null).orEmpty().toSet()

    /** The last state published: a watch asking for tiles gets this, never a partly built one. */
    @Volatile
    private var tiles: TilesState? = null

    @Volatile
    private var board: BoardState? = null

    /** The last haptic evidence from a wrist, newest last; the measured lead is what tunes the next cue. */
    private val evidence = ArrayDeque<HapticFired>()

    // The watch orders what it keeps by revision, so this is the one place that hands them out: a publisher's own
    // count and a note's confirmation can't then move it backwards.
    private val revisions = Any()
    private var revision = 0L
    private var boardRevision = 0L

    /**
     * Publishes what the Life and Board tiles show, and the Board's seats when there is a [board], to every watch
     * Herald knows of. Called whenever that state changes; with no watch, it only keeps it.
     */
    fun publish(tiles: TilesState, board: BoardState?) {
        val nextTiles = synchronized(revisions) {
            revision = maxOf(revision, tiles.revision) + 1
            tiles.copy(revision = revision, sentEpochMs = null)
        }
        val nextBoard = board?.let {
            synchronized(revisions) {
                boardRevision = maxOf(boardRevision, it.revision) + 1
                it.copy(revision = boardRevision)
            }
        }
        this.tiles = nextTiles
        this.board = nextBoard
        scope.launch { broadcast(nextTiles, nextBoard) }
    }

    /** Herald on watch [node] sent something: it can receive the tiles. */
    @Synchronized
    fun heardFrom(node: String) {
        if (node in heard) return
        heard = (heard + node).toList().takeLast(MAX_WATCHES).toSet()
        prefs.edit { putStringSet(HEARD, heard) }
    }

    /**
     * A pushed line is about to be audible: the watch gets its predicted onset so its cue can land about
     * 300 ms ahead. Speech never waits for this; a watch that is absent or late only loses its pulse.
     */
    fun speech(line: SeatSpeech) {
        scope.launch { watches().forEach { node -> runCatching { outbox.send(node, WearPaths.BOARD_SPEECH, WearJson.encode(line)) } } }
    }

    /** A watch asked for the tiles: it gets the last state, and the phone's clock with it. */
    fun requested(node: String) {
        heardFrom(node)
        scope.launch { sendTo(node, tiles ?: idle(), board) }
    }

    /** A status ask is answered with the tiles too: it costs one message and anchors the watch's cue. */
    fun status(node: String) = requested(node)

    /**
     * The watch's note was received (or refused): it hears so, and the tiles say it was saved — or, for a mic
     * toggle with no words, that the phone's mic was toggled.
     */
    fun noteSaved(node: String, saved: NoteSaved, micAction: Boolean = false) {
        heardFrom(node)
        scope.launch { runCatching { outbox.send(node, WearPaths.BOARD_NOTE_SAVED, WearJson.encode(saved)) } }
        val kept = tiles ?: return
        if (!saved.saved) return
        val words = when {
            micAction -> "Phone mic toggled"
            saved.pendingNotes == 1 -> "1 note saved"
            else -> "${saved.pendingNotes} notes saved"
        }
        val next = kept.copy(
            tiles = kept.tiles.map { tile ->
                if (tile.kind != BOARD) tile else tile.copy(detail = words)
            },
            sentEpochMs = null,
        )
        publish(next, board?.copy(pendingNotes = saved.pendingNotes))
    }

    /** A cue went off on a wrist: evidence, kept for tuning, never a gate on speech. */
    @Synchronized
    fun hapticFired(fired: HapticFired) {
        evidence.addLast(fired)
        if (evidence.size > MAX_EVIDENCE) evidence.removeFirst()
        Log.i(TAG, "haptic ${fired.seatId} for turn ${fired.turnId}: ${fired.leadMs} ms lead")
    }

    /** The last cues reported by the watches, newest last. */
    @Synchronized
    fun recentHaptics(): List<HapticFired> = evidence.toList()

    private suspend fun broadcast(tiles: TilesState, board: BoardState?) {
        watches().forEach { node -> sendTo(node, tiles, board) }
    }

    /** One watch gets the state, stamped with the moment it went out: the watch anchors its clock to that. */
    private suspend fun sendTo(node: String, tiles: TilesState, board: BoardState?) {
        val stamped = tiles.copy(sentEpochMs = System.currentTimeMillis())
        runCatching { outbox.send(node, WearPaths.TILES, WearJson.encode(stamped)) }
        board?.let { runCatching { outbox.send(node, WearPaths.BOARD, WearJson.encode(it)) } }
    }

    /** Every watch that may take it: the ones advertising Herald, and the ones that have spoken. */
    private suspend fun watches(): Set<String> {
        val nodes: List<Node> = try {
            capabilities.getCapability(WearCapabilities.WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No Wear OS here, or nothing paired: nothing to publish to.
            emptyList()
        }
        advertised = nodes.mapTo(HashSet()) { it.id }
        return advertised + heard
    }

    /**
     * Nothing published yet: no tiles at all, so a watch shows its own words for "waiting for your phone" rather
     * than English from here. It still carries the phone's clock, which is what the cue needs.
     */
    private fun idle() = TilesState(revision = 0)

    private companion object {
        const val TAG = "BoardSync"
        const val HEARD = "heard"
        const val MAX_WATCHES = 16
        const val MAX_EVIDENCE = 20
        const val BOARD = "board"
    }
}
