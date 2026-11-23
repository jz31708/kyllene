package dev.hermeskotlin.core.speak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a call on the phone is doing, as the watch companion needs it (`docs/board/watch-plan.md` §4).
 *
 * The phone publishes; the watch renders. Nothing here is derived from captions, and none of it makes the
 * watch open a microphone: the Oppo is the microphone, the watch shows and toggles.
 */
data class BoardCallSnapshot(
    /** A chained call is open and the phone can listen and speak. */
    val active: Boolean = false,
    /** GPT-Live rather than the chained flow (no pushed speech, no mic toggle of ours). */
    val live: Boolean = false,
    /** The phone's microphone is off. */
    val muted: Boolean = false,
    /** The phone is listening for an utterance. */
    val listening: Boolean = false,
    /** The phone has picked up speech in this turn (before the pause commits it). */
    val hearing: Boolean = false,
    /** Sound is coming out of the phone. */
    val speaking: Boolean = false,
    /** The seat whose voice is being heard now, or null between lines. */
    val floor: String? = null,
    /** The floor's human name, for the tile ("Board"). */
    val floorLabel: String? = null,
    /** The output in use ("phone speaker", "Shokz OpenRun Pro 2"), once the player knows it. */
    val route: String? = null,
    /** A line is about to be said: the seat, its predicted audible onset on the phone's clock, and its sequence. */
    val speechSeat: String? = null,
    val speechOnsetEpochMs: Long? = null,
    val speechSeq: Int = 0,
) {
    /** The state word a tile shows. */
    val stateWord: String
        get() = when {
            !active -> "idle"
            muted -> "muted"
            speaking -> "live"
            listening -> "listening"
            else -> "live"
        }
}

/** The one open call, published for the app's watch link. */
object BoardCallBus {
    private val _state = MutableStateFlow(BoardCallSnapshot())
    val state: StateFlow<BoardCallSnapshot> = _state.asStateFlow()

    fun publish(value: BoardCallSnapshot) {
        // A recorder level tick or a caption is not a tile change: publish only what the wrist can see.
        if (_state.value == value) return
        _state.value = value
    }
}
