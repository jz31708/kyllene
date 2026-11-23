package dev.hermeskotlin.android.words

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import dev.hermeskotlin.android.R
import dev.hermeskotlin.android.sharedContent
import dev.hermeskotlin.core.chat.AttachmentUploadException
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.words.OwnerWordDraft
import dev.hermeskotlin.core.words.OwnerWordFile
import dev.hermeskotlin.core.words.OwnerWordsApi
import java.io.File
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * Android's share sheet, into the Board's ledger (`worktable-spec.md` §6, C4b).
 *
 * Text, links and screenshots shared from any app become **owner words**: the text is kept exactly, the
 * files are attached, and the ledger does the interpreting. Nothing is edited or summarised here.
 *
 * Delivered over `POST /api/worktable/owner-words` on the dashboard the phone is signed in to. When that
 * route is not there yet, or the phone is offline, the draft goes to a small on-phone outbox and is
 * delivered on a later launch: a share never disappears.
 */
class OwnerWordOutbox(
    context: Context,
    private val api: OwnerWordsApi,
    private val gateways: GatewayRepository,
    /** The signed-in client, for uploading a picture too large to keep inline. */
    val sessions: SessionsApi,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, FILE)
    private val mutex = Mutex()

    /** Posts [draft] now, or keeps it for later. Returns whether the ledger took it. */
    suspend fun deliver(draft: OwnerWordDraft): Boolean = mutex.withLock {
        val url = gateways.current()?.gatewayUrl
        if (url != null && api.submit(url, draft) is ApiResult.Success) {
            // A route that works may also clear what waited behind it.
            scope.launch(Dispatchers.IO) { mutex.withLock { flushLocked() } }
            true
        } else {
            write(read() + draft)
            false
        }
    }

    /** Tries the outbox again, oldest first; stops at the first failure so order is kept. */
    fun flush() {
        scope.launch(Dispatchers.IO) { mutex.withLock { flushLocked() } }
    }

    private suspend fun flushLocked() {
        val url = gateways.current()?.gatewayUrl ?: return
        val pending = read()
        if (pending.isEmpty()) return
        val left = mutableListOf<OwnerWordDraft>()
        var blocked = false
        for (draft in pending) {
            if (blocked) {
                left += draft
                continue
            }
            if (api.submit(url, draft) is ApiResult.Success) continue
            blocked = true
            left += draft
        }
        write(left)
    }

    private fun read(): List<OwnerWordDraft> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { HermesJson.decodeFromString(OwnerWordDraft.serializer(), it) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun write(drafts: List<OwnerWordDraft>) {
        runCatching {
            if (drafts.isEmpty()) file.delete() else file.writeText(drafts.joinToString("\n") {
                HermesJson.encodeToString(OwnerWordDraft.serializer(), it)
            })
        }
    }

    private companion object {
        const val FILE = "owner-words-outbox.jsonl"
    }
}

/**
 * The share-sheet target ("Kyllene Board: save to Board"). It sits beside Kyllene's ordinary share target,
 * which still starts a chat draft; this one files what was shared as the owner's words.
 */
class ShareToBoardActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = sharedContent(this, intent)
        if (shared == null) {
            finish()
            return
        }
        val koin = GlobalContext.get()
        val outbox = koin.get<OwnerWordOutbox>()
        koin.get<CoroutineScope>().launch {
            val draft = withContext(Dispatchers.IO) { draft(shared.text, shared.subject, shared.files, outbox) }
            val taken = outbox.deliver(draft)
            // Back on the main thread: the outbox runs on the app's Default scope, which has no Looper to toast on.
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(
                    this@ShareToBoardActivity,
                    if (taken) R.string.share_saved else R.string.share_queued,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }
    }

    private suspend fun draft(
        text: String?,
        subject: String?,
        files: List<String>,
        outbox: OwnerWordOutbox,
    ): OwnerWordDraft {
        val body = text?.trim().orEmpty()
        val links = OwnerWordDraft.linksIn(body)
        val attachments = files.mapNotNull { read(it, outbox) }
        return OwnerWordDraft(
            text = body,
            kind = OwnerWordDraft.kindOf(body, attachments.any { it.mime?.startsWith("image/") == true }, links),
            subject = subject?.trim()?.takeIf { it.isNotEmpty() },
            links = links,
            files = attachments,
            capturedAt = System.currentTimeMillis() / 1000,
            device = Build.MODEL?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Reads one `content://` file. An image small enough goes inline, so the ledger can read it without a
     * second fetch; anything larger is uploaded on the signed-in client and referred to by its path.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun read(uriString: String, outbox: OwnerWordOutbox): OwnerWordFile? = runCatching {
        val uri = Uri.parse(uriString)
        val mime = contentResolver.getType(uri)
        val name = displayName(uri) ?: "shared-${System.currentTimeMillis()}"
        // Refuse before reading: a shared video must not be pulled into memory to be dropped.
        val announced = sizeOf(uri)
        if (announced != null && announced > MAX_FILE) return null
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_FILE) return null
        val inline = bytes.size <= INLINE_MAX && mime?.startsWith("image/") == true
        // An attachment that could not be uploaded is left out, rather than delivered as a nameless empty file.
        val path = if (inline) null else upload(name, mime, bytes, outbox) ?: return null
        OwnerWordFile(
            name = name,
            mime = mime,
            data = if (inline) Base64.encode(bytes) else null,
            path = path,
            size = bytes.size.toLong(),
        )
    }.getOrNull()

    private suspend fun upload(name: String, mime: String?, bytes: ByteArray, outbox: OwnerWordOutbox): String? {
        val url: GatewayUrl = GlobalContext.get().get<GatewayRepository>().current()?.gatewayUrl ?: return null
        return runCatching {
            outbox.sessions.uploads.stage(
                url,
                OutgoingAttachment(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    mimeType = mime ?: "application/octet-stream",
                    bytes = bytes,
                ),
            ).path
        }.getOrElse { e ->
            if (e is AttachmentUploadException && !e.unsupported) Log.w(TAG, "Share upload failed: ${e.message}")
            null
        }
    }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val TAG = "ShareToBoard"

        /** A screenshot that fits comfortably in one request is better inline than behind a second fetch. */
        const val INLINE_MAX = 1_400_000

        /** The largest shared file worth taking; a video shared by mistake is dropped, never read. */
        const val MAX_FILE = 24_000_000L
    }
}
