package dev.hermeskotlin.wear.tiles

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.hermeskotlin.wear.HeraldWatch
import dev.hermeskotlin.wear.R

/** A small action under a tile's text: its [id], the [label] it shows, and what its [action] does. */
data class TileChip(val id: String, val label: String, val action: ActionBuilders.Action)

/**
 * What every Herald tile shares: the one layout a round wrist tile can hold (a title, one state word, one line
 * of detail, and a chip or two), the resources reply, and the actions a tile is allowed to take.
 *
 * A tile renders what the phone last published and [dev.hermeskotlin.wear.WatchStore] kept: it never asks the
 * phone on the request path, where the system waits on it, and it never starts a recording.
 */
abstract class CallTileService : TileService() {

    /** This tile's resources' version: bumped when its layout or images change. */
    protected abstract val resourcesVersion: String

    protected fun done(value: TileBuilders.Tile): ListenableFuture<TileBuilders.Tile> = CallbackToFutureAdapter.getFuture { it.set(value) }

    protected fun done(value: ResourceBuilders.Resources): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { it.set(value) }

    protected fun tile(layout: LayoutElementBuilders.LayoutElement): TileBuilders.Tile = TileBuilders.Tile.Builder()
        .setResourcesVersion(resourcesVersion)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
        .build()

    protected fun resources(images: Map<String, Int> = emptyMap()): ResourceBuilders.Resources {
        val builder = ResourceBuilders.Resources.Builder().setVersion(resourcesVersion)
        images.forEach { (id, drawable) ->
            builder.addIdToImageMapping(
                id,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(drawable).build())
                    .build(),
            )
        }
        return builder.build()
    }

    /** The phone's accent, once synced, for the tile's state word and its first chip. */
    protected fun accentFill(context: Context): Int = HeraldWatch.of(context).store.accent.value?.fill ?: HERALD_BLUE

    protected fun accentOnFill(context: Context): Int = HeraldWatch.of(context).store.accent.value?.onFill ?: ON_FILL

    /**
     * The tile: [title] on top, the one [state] word under it, one line of [detail], then either [chips] or, while
     * the canned asks show, one short [ask] each. A tap anywhere else runs [onClick]. It fits a round 192 dp
     * screen: no line wraps past two, and the chips sit side by side.
     */
    protected fun stateLayout(
        title: String,
        state: String,
        detail: String?,
        onClick: ActionBuilders.Action,
        chips: List<TileChip> = emptyList(),
        asks: List<TileChip> = emptyList(),
        fill: Int = HERALD_BLUE,
        onFill: Int = ON_FILL,
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(line(title, TITLE_SIZE, TEXT, maxLines = 1))
            .addContent(spacer(2f))
            .addContent(line(state, STATE_SIZE, fill, maxLines = 1))
        if (!detail.isNullOrBlank()) {
            column.addContent(spacer(2f))
            column.addContent(line(detail, DETAIL_SIZE, MUTED, maxLines = 2))
        }
        val row = asks.ifEmpty { chips }
        if (row.isNotEmpty()) {
            column.addContent(spacer(6f))
            column.addContent(chipRow(row, fill, onFill))
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable("tile", onClick)).build())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column.build())
            .build()
    }

    private fun chipRow(chips: List<TileChip>, fill: Int, onFill: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .apply {
                chips.forEachIndexed { index, chip ->
                    val primary = index == 0
                    addContent(
                        LayoutElementBuilders.Box.Builder()
                            .setHeight(dp(CHIP_HEIGHT))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setClickable(clickable(chip.id, chip.action))
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setColor(argb(if (primary) fill else SURFACE))
                                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(CHIP_HEIGHT / 2)).build())
                                            .build(),
                                    )
                                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(chip.label).build())
                                    .build(),
                            )
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(
                                LayoutElementBuilders.Text.Builder()
                                    .setText(chip.label)
                                    .setMaxLines(1)
                                    .setFontStyle(
                                        LayoutElementBuilders.FontStyle.Builder()
                                            .setSize(sp(CHIP_TEXT))
                                            .setColor(argb(if (primary) onFill else TEXT))
                                            .build(),
                                    )
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(10f)).setEnd(dp(10f)).build())
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                }
            }
            .build()

    private fun line(text: String, size: Float, color: Int, maxLines: Int) = LayoutElementBuilders.Text.Builder()
        .setText(text)
        .setMaxLines(maxLines)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(20f)).setEnd(dp(20f)).build())
                .build(),
        )
        .build()

    protected fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    protected fun clickable(id: String, onClick: ActionBuilders.Action): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(onClick).build()

    /** Starts [target] on this watch, with [extra] set true when there is one. */
    protected fun launch(target: Class<*>, extra: String? = null): ActionBuilders.Action {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(target.name)
        if (extra != null) activity.addKeyToExtraMapping(extra, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
        return ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()
    }

    /** Starts [target] with a short [value] in [extra]: a canned ask, from a chip on the tile. */
    protected fun launchText(target: Class<*>, extra: String, value: String): ActionBuilders.Action {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(target.name)
            .addKeyToExtraMapping(extra, ActionBuilders.AndroidStringExtra.Builder().setValue(value).build())
        return ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()
    }

    /** Asks the system for this tile again, the tap on the asks chip recorded: the canned asks show once. */
    protected fun reload(): ActionBuilders.Action = ActionBuilders.LoadAction.Builder().build()

    /** Whether this request follows a tap on the asks chip. */
    protected fun asking(request: RequestBuilders.TileRequest): Boolean =
        request.currentState.lastClickableId == ASKS_CHIP

    /** The state word in the watch's language; an unrecognised state is shown as the phone sent it. */
    protected fun stateWord(context: Context, state: String): String = when (state.lowercase()) {
        "idle" -> context.getString(R.string.wear_state_idle)
        "listening" -> context.getString(R.string.wear_state_listening)
        "live" -> context.getString(R.string.wear_state_live)
        "muted" -> context.getString(R.string.wear_state_muted)
        else -> state
    }
}

/** The id of the chip that reveals the canned asks. */
internal const val ASKS_CHIP = "asks"

private const val TITLE_SIZE = 15f
private const val STATE_SIZE = 13f
private const val DETAIL_SIZE = 12f
private const val CHIP_TEXT = 11f
private const val CHIP_HEIGHT = 26f
internal const val HERALD_BLUE = 0xFF2F6BF5.toInt()
private const val ON_FILL = 0xFFFFFFFF.toInt()
private const val TEXT = 0xFFF4F4F6.toInt()
private const val MUTED = 0xFF8A8A94.toInt()
private const val SURFACE = 0xFF1C1C22.toInt()

/** The soft ring round a tile's action: [fill] at a quarter strength, as Herald blue's ring always was. */
internal fun softRing(fill: Int): Int = (fill and 0x00FFFFFF) or 0x40000000
