package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.BoardState
import dev.hermeskotlin.wearlink.CallTile
import dev.hermeskotlin.wearlink.ChatListRequest
import dev.hermeskotlin.wearlink.NoteSaved
import dev.hermeskotlin.wearlink.QuickNote
import dev.hermeskotlin.wearlink.Seat
import dev.hermeskotlin.wearlink.SeatSpeech
import dev.hermeskotlin.wearlink.TilesState
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.ChatList
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.PhoneStatusRequest
import dev.hermeskotlin.wearlink.RecordedAsk
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplySpeech
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchChat
import dev.hermeskotlin.wearlink.WatchBot
import dev.hermeskotlin.wearlink.WatchStop
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only: stands in for Herald on the phone when the watch has no phone at all, an emulator that
 * isn't paired, so every screen can be tried. It answers with made-up sample turns, never anything real.
 */
class FakePhone(private val scope: CoroutineScope, private val deliver: (String, ByteArray) -> Unit) {

    private var turn: Job? = null
    private var revision = 0L
    private var notes = 0

    fun receive(path: String, data: ByteArray) {
        when {
            path == WearPaths.ASK || path == WearPaths.BOT_ASK -> WearJson.decode<WatchAsk>(data)?.let { answer(it, heard = null) }
            WearPaths.isRecording(path) -> RecordedAsk.read(data)?.let { (ask, _) -> answer(ask, heard = SAMPLE_HEARD) }
            path == WearPaths.STOP -> WearJson.decode<WatchStop>(data)?.let { turn?.cancel() }
            path == WearPaths.CHATS -> deliver(WearPaths.CHATS, WearJson.encode(ChatList(SAMPLE_CHATS, bots = SAMPLE_BOTS, requestId = WearJson.decode<ChatListRequest>(data)?.requestId, scope = SAMPLE_SCOPE)))
            path == WearPaths.STATUS -> {
                val request = WearJson.decode<PhoneStatusRequest>(data)
                deliver(WearPaths.STATUS, WearJson.encode(PhoneStatus(signedIn = true, connected = true, botChats = true,
                    botProfile = request?.botProfile, requestId = request?.requestId, profileSync = true,
                    activeScope = SAMPLE_SCOPE, scope = request?.scope ?: SAMPLE_SCOPE)))
                // As Herald's phone does: the status answer is also the clock anchor for the speech cue.
                publishTiles()
            }
            // The tiles ask: the same state, and a line about to be said, so the cue can be tried on an emulator.
            path == WearPaths.TILES -> {
                publishTiles()
                scope.launch {
                    delay(600)
                    deliver(WearPaths.BOARD_SPEECH, WearJson.encode(SeatSpeech(TURN, SAMPLE_SEAT, 1, SAMPLE_LINE,
                        onsetEpochMs = System.currentTimeMillis() + CUE_AHEAD_MS, playingOnWatch = false)))
                }
            }
            path == WearPaths.BOARD_NOTES -> WearJson.decode<QuickNote>(data)?.let(::saved)
        }
    }

    /** A note: kept when it has words; a blank one is the mic toggle, as on the phone. Answered as one either way. */
    private fun saved(note: QuickNote) {
        if (note.text.isNotBlank()) notes++
        deliver(WearPaths.BOARD_NOTE_SAVED, WearJson.encode(NoteSaved(note.id, saved = true, pendingNotes = notes)))
        publishTiles()
    }

    private fun publishTiles() {
        deliver(WearPaths.BOARD, WearJson.encode(BoardState(++revision, SAMPLE_BOARD, SAMPLE_SEATS, floor = SAMPLE_SEAT, pendingNotes = notes)))
        deliver(WearPaths.TILES, WearJson.encode(TilesState(
            revision = ++revision,
            tiles = listOf(
                CallTile("life", "life", "Life", if (notes > 0) "muted" else "listening", SAMPLE_LINE),
                CallTile("board", "board", "Board", "live", if (notes > 0) "$notes saved" else "Ada holds the floor"),
            ),
            sentEpochMs = System.currentTimeMillis(),
        )))
    }

    private fun answer(ask: WatchAsk, heard: String?) {
        turn?.cancel()
        turn = scope.launch {
            var seq = 0
            val chatId = ask.chatId ?: "sample-chat"
            fun tell(update: ReplyUpdate) = deliver(WearPaths.REPLY, WearJson.encode(update.copy(seq = ++seq)))
            val base = ReplyUpdate(ask.id, ReplyPhase.Sending, chatId = chatId, title = "Weekend plans", heard = heard, model = "Sample model", contextPercent = 34, scope = ask.scope ?: SAMPLE_SCOPE)
            if (heard != null) {
                tell(base.copy(phase = ReplyPhase.Transcribing))
                delay(700)
            }
            tell(base)
            delay(500)
            tell(base.copy(phase = ReplyPhase.Working, activity = "Searching the web"))
            delay(1_200)
            val words = SAMPLE_REPLY.split(' ')
            for (n in 4..words.size step 4) {
                tell(base.copy(phase = ReplyPhase.Working, text = words.take(n).joinToString(" "), activity = null))
                delay(300)
            }
            tell(base.copy(phase = ReplyPhase.Done, text = SAMPLE_REPLY, speech = if (ask.speak) ReplySpeech.Unavailable else ReplySpeech.Off))
        }
    }

    private companion object {
        val SAMPLE_SCOPE = WatchScope("https://sample.example")
        const val SAMPLE_BOARD = "sample-board"
        const val SAMPLE_SEAT = "ada"
        const val TURN = "sample-turn"
        const val CUE_AHEAD_MS = 900L
        val SAMPLE_SEATS = listOf(Seat("ada", "Ada", 0xFFF5A524), Seat("sam", "Sam", 0xFF2F6BF5))
        const val SAMPLE_LINE = "Saturday looks sunny with a high of 22°C."
        const val SAMPLE_HEARD = "What's the weather like this weekend?"
        const val SAMPLE_REPLY = "Saturday looks sunny with a high of 22°C, so it's a good day to be outside. " +
            "Sunday turns cloudy in the afternoon, with a chance of light rain after 4 pm. " +
            "If you're planning the hike, Saturday morning is the best window."
        val SAMPLE_CHATS = listOf(
            WatchChat("sample-chat", "Weekend plans"),
            WatchChat("sample-2", "Grocery list"),
            WatchChat("sample-3", "Fix the build script"),
            WatchChat("sample-4", "Book recommendations"),
        )
        val SAMPLE_BOTS = listOf(WatchBot("assistant", "Assistant"), WatchBot("research", "Research"))
    }
}
