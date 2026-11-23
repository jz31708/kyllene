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
 * The "Life" tile: what the phone's Life call is doing — idle, listening, live or muted — and the line it would
 * say. The phone publishes that as a [dev.hermeskotlin.wearlink.TilesState]; the tile never asks for it, so it
 * answers at once, and a tap opens the Board's call screen, which asks the phone to take the call: the Oppo
 * records, the watch shows and toggles. The "Ask" chip turns the tile over to two canned asks.
 */
class LifeTileService : CallTileService() {

    override val resourcesVersion = "1"

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        done(tile(content(requestParams)))

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        done(resources())

    private fun content(request: RequestBuilders.TileRequest): LayoutElementBuilders.LayoutElement {
        val state = HeraldWatch.of(this).store.tiles
        val life = state?.tile(LIFE)
        val detail = life?.detail ?: state?.error ?: getString(R.string.wear_tile_waiting)
        val accents = HeraldWatch.of(this).store.accent.value
        return stateLayout(
            title = life?.title ?: getString(R.string.wear_life_tile_default),
            state = stateWord(this, life?.state ?: "idle"),
            detail = detail,
            onClick = launch(BoardCallActivity::class.java, BoardCallActivity.EXTRA_START),
            // A remote, as the Board tile is: nothing dictated here, and no asks sent from the wrist.
            chips = if (BuildConfig.BOARD_REMOTE) emptyList() else listOf(TileChip(ASKS_CHIP, getString(R.string.wear_tile_ask), reload())),
            asks = if (!BuildConfig.BOARD_REMOTE && asking(request)) cannedAsks() else emptyList(),
            fill = accents?.fill ?: HERALD_BLUE,
            onFill = accents?.onFill ?: ON_FILL,
        )
    }

    /** Two questions worth a tap from the wrist; each opens the talk screen already asking it. */
    private fun cannedAsks(): List<TileChip> = listOf(
        TileChip("ask-status", getString(R.string.wear_ask_status), launchText(MainActivity::class.java, MainActivity.EXTRA_ASK, getString(R.string.wear_ask_status))),
        TileChip("ask-next", getString(R.string.wear_ask_next), launchText(MainActivity::class.java, MainActivity.EXTRA_ASK, getString(R.string.wear_ask_next))),
    )

    private companion object {
        const val LIFE = "life"
        const val HERALD_BLUE = 0xFF2F6BF5.toInt()
        const val ON_FILL = 0xFFFFFFFF.toInt()
    }
}
