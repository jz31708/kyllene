package dev.hermeskotlin.android.wear

import dev.hermeskotlin.core.speak.BoardCallBus
import dev.hermeskotlin.core.speak.BoardCallSnapshot
import dev.hermeskotlin.core.speak.PushedSpeechTargets
import dev.hermeskotlin.wearlink.BoardState
import dev.hermeskotlin.wearlink.CallTile
import dev.hermeskotlin.wearlink.Seat
import dev.hermeskotlin.wearlink.SeatSpeech
import dev.hermeskotlin.wearlink.TilesState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Turns the call's state into what the wrist shows (`docs/board/watch-plan.md` §4): the Life and Board
 * tiles, the floor holder, the seat's 300 ms cue, and — through [BoardNotes] — the mic toggle.
 *
 * The phone is the only judge of the call; the watch renders and sends back a note. Nothing here opens a
 * microphone on the watch, and nothing waits on a watch.
 */
class BoardTiles(
    private val sync: BoardSync,
    private val notes: BoardNotes,
    private val scope: CoroutineScope,
) {
    // The watch rejects a revision it has already seen: a reinstall must still move it forward.
    private var revision = System.currentTimeMillis()
    private var announced: Pair<String?, Int>? = null

    fun start() {
        // The wrist's mic button toggles the phone's mic, whichever call holds it.
        notes.mic = BoardMic { PushedSpeechTargets.current?.toggleMic() }
        scope.launch {
            BoardCallBus.state.collect { snapshot -> publish(snapshot) }
        }
    }

    private fun publish(snapshot: BoardCallSnapshot) {
        revision += 1
        val board = CallTile(
            id = BOARD,
            kind = BOARD,
            title = "Board",
            state = snapshot.stateWord,
            detail = when {
                snapshot.floorLabel != null -> snapshot.floorLabel
                snapshot.active -> "ready"
                else -> null
            },
        )
        val life = CallTile(
            id = LIFE,
            kind = LIFE,
            title = "Life",
            state = if (snapshot.active) snapshot.stateWord else "idle",
            detail = null,
        )
        sync.publish(
            TilesState(revision = revision, tiles = listOf(life, board)),
            BoardState(
                revision = revision,
                boardId = BOARD,
                seats = SEATS,
                floor = snapshot.floor,
                pendingNotes = notes.pending(),
            ),
        )
        // A line is about to be audible: announce its predicted onset once, so the watch can lead it by 300 ms.
        val seat = snapshot.speechSeat
        val onset = snapshot.speechOnsetEpochMs
        val mark = seat to snapshot.speechSeq
        if (seat != null && onset != null && mark != announced) {
            announced = mark
            sync.speech(
                SeatSpeech(
                    turnId = UUID.randomUUID().toString(),
                    seatId = seat,
                    seq = snapshot.speechSeq,
                    onsetEpochMs = onset,
                ),
            )
        }
        if (seat == null) announced = null
    }

    private companion object {
        const val BOARD = "board"
        const val LIFE = "life"

        /** The Board's seats tonight; a registry on the gateway replaces this when it exists. */
        val SEATS = listOf(Seat("board", "Board"))
    }
}
