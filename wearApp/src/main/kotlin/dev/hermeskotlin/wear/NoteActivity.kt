package dev.hermeskotlin.wear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.delay

/**
 * The Board's quick note, from the tile's "Note" chip: the last state the phone published, a button that dictates
 * a note on the wrist, and one that toggles the phone's microphone. A note is text, typed or recognized; the
 * watch opens no microphone of its own for the Board, and records nothing.
 */
class NoteActivity : ComponentActivity() {

    private val app get() = HeraldWatch.of(this)
    private var error by mutableStateOf<String?>(null)

    private val dictate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrBlank()) app.link.sendNote(said)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The tiles and the phone's clock, so a cue timed from here has an anchor; a note answers with newer.
        app.link.clearNote()
        app.link.refreshTiles()
        setContent {
            val accent by app.store.accent.collectAsState()
            val tiles by app.store.tilesFlow.collectAsState()
            val board by app.store.boardFlow.collectAsState()
            val note by app.link.note.collectAsState()
            // Saved (or the mic toggled): it stays a moment, then out of the way.
            LaunchedEffect(note?.stage) {
                if (note?.stage == NoteStage.Saved) {
                    delay(AFTER_SAVED_MS)
                    finish()
                }
            }
            MaterialTheme(colorScheme = watchColors(accent)) {
                BoardNoteScreen(
                    board = board,
                    tiles = tiles,
                    note = note,
                    error = error,
                    onNote = ::note,
                    onMic = {
                        error = null
                        app.link.togglePhoneMic()
                    },
                )
            }
        }
    }

    /** The watch's own recognizer, which is how words reach the note; nothing is recorded here. */
    private fun note() {
        error = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.wear_note))
        if (intent.resolveActivity(packageManager) == null) {
            error = getString(R.string.wear_note_no_speech)
            return
        }
        try {
            dictate.launch(intent)
        } catch (_: ActivityNotFoundException) {
            error = getString(R.string.wear_note_no_speech)
        }
    }

    companion object {
        private const val AFTER_SAVED_MS = 1_400L
    }
}
