package dev.hermeskotlin.wear.board

import dev.hermeskotlin.wearlink.BoardCallView

/**
 * The four peripheral-ring colours are a closed set (ported from `agent-config/mobile/wear`'s `SessionState`):
 * Blue = ready, Red = recording, Amber = swarm, Green = call mode. The Board's watch shows the same phases and
 * the same words the Worktable watch does, so the wrist reads the same in both.
 */
enum class RingColor(val argb: Int, val label: String) {
    BLUE(0xFF1E88E5.toInt(), "Ready"),
    RED(0xFFE53935.toInt(), "Recording"),
    AMBER(0xFFFFB300.toInt(), "Swarm"),
    GREEN(0xFF43A047.toInt(), "Call"),
}

enum class WearPhase(val ring: RingColor, val label: String) {
    READY(RingColor.BLUE, "Ready"),
    RECORDING(RingColor.RED, "Recording"),
    SWARM(RingColor.AMBER, "Swarm"),
    CALL(RingColor.GREEN, "Call"),
    OFFLINE(RingColor.BLUE, "Offline"),
}

/** What the ring shows: its phase, an optional fallback banner, and the one line under it. */
data class WearUiState(
    val phase: WearPhase = WearPhase.READY,
    val ringColor: RingColor = WearPhase.READY.ring,
    val fallbackBanner: String? = null,
    val awaitingPhoneAck: Boolean = false,
    val connected: Boolean = true,
    val detail: String = TAP_HINT,
) {
    companion object {
        const val TAP_HINT = "Tap the ring to talk"
        const val NOT_CONNECTED = "Phone not connected"
        const val AWAITING = "Tap sent - waiting for phone"
        const val RECORDING = "Recording on Oppo"
        const val OPEN_OPPO = "Open Oppo to record"
    }
}

/**
 * The watch's state, derived only from what the phone reports. The hard rule, as upstream's: a tap never turns
 * the ring red — only the phone's `mic_open` acknowledgement does — and no phase other than the phone's own
 * recording is shown as recording. Offline stays blue with "Phone not connected": it never falls back to the
 * watch's own microphone, because the Board's watch has none.
 */
class WearStateMachine {
    private var state = WearUiState()

    val current: WearUiState get() = state

    /** The ring was tapped and the command is on its way; the ring doesn't change colour for that. */
    fun onTapQueued() {
        state = state.copy(awaitingPhoneAck = true, detail = WearUiState.AWAITING)
    }

    /** The phone reported its call: this is the only thing that may turn the ring red. */
    fun applyPhone(view: BoardCallView?) {
        val connected = view?.connected ?: true
        val banner = view?.fallback?.takeIf { it == FALLBACK_OPEN_OPPO }?.let { WearUiState.OPEN_OPPO }
        val mode = when (view?.mode) {
            "call" -> WearPhase.CALL
            "swarm" -> WearPhase.SWARM
            else -> WearPhase.READY
        }
        val phase = when {
            !connected -> WearPhase.OFFLINE
            view?.capture == CAPTURING -> WearPhase.RECORDING
            view?.capture != null -> mode
            state.phase == WearPhase.RECORDING -> WearPhase.RECORDING
            else -> mode
        }
        state = state.copy(
            phase = phase,
            ringColor = phase.ring,
            fallbackBanner = banner,
            connected = connected,
            awaitingPhoneAck = if (phase == WearPhase.RECORDING) false else state.awaitingPhoneAck,
            detail = when {
                banner != null -> WearUiState.OPEN_OPPO
                !connected -> WearUiState.NOT_CONNECTED
                phase == WearPhase.RECORDING -> WearUiState.RECORDING
                else -> phase.label
            },
        )
    }

    companion object {
        /** The phone's capture state, as its ack reports it. */
        const val CAPTURING = "capturing"

        /** The phone couldn't open its own call: the wrist says exactly this, and taps send `open_oppo`. */
        const val FALLBACK_OPEN_OPPO = "open_oppo_to_record"
    }
}
