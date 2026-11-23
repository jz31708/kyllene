package dev.hermeskotlin.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.wear.compose.material3.MaterialTheme

/**
 * The Board's call, from the wrist: what the phone is doing (listening, muted, the floor, the route it plays on),
 * a button that toggles the **phone's** microphone, and one that asks the phone to start or end its call.
 *
 * The watch never opens a microphone here, or anywhere else in this build: the Oppo records and streams, the
 * watch asks and shows. A tap on a tile is the wrist's start ([EXTRA_START]); opening the app on the wrist is
 * not, so the phone isn't called just by looking at its state.
 */
class BoardCallActivity : ComponentActivity() {

    private val app get() = HeraldWatch.of(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.link.clearBoardCallProblem()
        // The tiles and the phone's clock: the cue and the state on screen both come from what the phone publishes.
        app.link.refreshTiles()
        startIfTapped(intent)
        setContent {
            val accent by app.store.accent.collectAsState()
            val tiles by app.store.tilesFlow.collectAsState()
            val board by app.store.boardFlow.collectAsState()
            val note by app.link.note.collectAsState()
            val problem by app.link.boardCallProblem.collectAsState()
            val phone by app.link.phone.collectAsState()
            MaterialTheme(colorScheme = watchColors(accent)) {
                BoardCallScreen(
                    board = board,
                    tiles = tiles,
                    note = note,
                    problem = problem,
                    phone = phone,
                    onMic = { app.link.togglePhoneMic() },
                    onStart = { app.link.startBoardCall() },
                    onEnd = { app.link.stopBoardCall() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startIfTapped(intent)
    }

    /** A tile's tap is the start; the phone ignores one when its call is already on. */
    private fun startIfTapped(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START, false) == true) app.link.startBoardCall()
    }

    companion object {
        /** The tile's tap: the wrist asking the phone to take the Board call. */
        const val EXTRA_START = "board_call_start"
    }
}
