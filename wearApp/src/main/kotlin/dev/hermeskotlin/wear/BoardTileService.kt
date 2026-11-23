package dev.hermeskotlin.wear

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.common.util.concurrent.ListenableFuture
import dev.hermeskotlin.wear.tiles.ASKS_CHIP
import dev.hermeskotlin.wear.tiles.CallTileService
import dev.hermeskotlin.wear.tiles.TileChip

/**
 * The "Board" tile: the Board's state word and who holds the floor, in that seat's colour. A tap opens the call
 * screen; "Note" opens the little note screen, which dictates a quick note or toggles the phone's mic — the watch
 * itself never opens a microphone for the Board. "Ask" turns the tile over to two canned asks.
 */
class BoardTileService : CallTileService() {

    override val resourcesVersion = "1"

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        done(tile(content(requestParams)))

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        done(resources())

    private fun content(request: RequestBuilders.TileRequest): LayoutElementBuilders.LayoutElement {
        val store = HeraldWatch.of(this).store
        val state = store.tiles
        val board = store.board
        val tile = state?.tile(BOARD)
        val floor = board?.floor
        val seat = floor?.let { id -> board.seats.firstOrNull { it.id == id } }
        val floorLabel = floor?.let { id -> seat?.label ?: id }
        val floorColor = seat?.color?.toInt()
        val pending = board?.pendingNotes ?: 0
        val accents = store.accent.value
        // One line, and the phone owns it: it may say the floor, or that a note was just saved. The floor and
        // the queue are the fallback for a phone that publishes only state words.
        val detail = tile?.detail
            ?: state?.error
            ?: floorLabel?.let { getString(R.string.wear_board_floor, it) }
            ?: pending.takeIf { it > 0 }?.let { getString(R.string.wear_board_notes_waiting, it) }
            ?: getString(R.string.wear_tile_waiting)
        // The floor holder's own colour says who speaks; without one, the phone's accent.
        return stateLayout(
            title = tile?.title ?: getString(R.string.wear_board_tile_default),
            state = stateWord(this, tile?.state ?: "idle"),
            detail = detail,
            onClick = launch(MainActivity::class.java, MainActivity.EXTRA_CALL),
            chips = listOf(
                TileChip("note", getString(R.string.wear_note), launch(NoteActivity::class.java)),
                TileChip(ASKS_CHIP, getString(R.string.wear_tile_ask), reload()),
            ),
            asks = if (asking(request)) cannedAsks() else emptyList(),
            fill = floorColor ?: accents?.fill ?: HERALD_BLUE,
            onFill = if (floorColor != null) WHITE else accents?.onFill ?: WHITE,
        )
    }

    /** Two questions worth a tap from the wrist; each opens the talk screen already asking it. */
    private fun cannedAsks(): List<TileChip> = listOf(
        TileChip("ask-status", getString(R.string.wear_ask_status), launchText(MainActivity::class.java, MainActivity.EXTRA_ASK, getString(R.string.wear_ask_status))),
        TileChip("ask-next", getString(R.string.wear_ask_next), launchText(MainActivity::class.java, MainActivity.EXTRA_ASK, getString(R.string.wear_ask_next))),
    )

    private companion object {
        const val BOARD = "board"
        const val HERALD_BLUE = 0xFF2F6BF5.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
