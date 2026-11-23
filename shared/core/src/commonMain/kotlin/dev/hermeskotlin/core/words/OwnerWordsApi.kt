package dev.hermeskotlin.core.words

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One thing Thomas shared into the Board from Android's share sheet: text, a link, or a screenshot.
 *
 * It is an **owner word** (`worktable-spec.md` §6): kept verbatim, with its capture time, by the ledger
 * that owns owner words. The phone never interprets it; it only hands it over.
 */
@Serializable
data class OwnerWordDraft(
    /** Exactly what was shared, unedited. For a screenshot, the sender's caption, if any. */
    val text: String = "",
    /** `text`, `link`, or `screenshot`; a hint for the interpreter, not a decision. */
    val kind: String,
    /** The sending app's subject, when it gave one. */
    val subject: String? = null,
    /** Every `http(s)` link found in [text], in order. */
    val links: List<String> = emptyList(),
    /** Screenshots and other files, uploaded or inline. */
    val files: List<OwnerWordFile> = emptyList(),
    /** When the share sheet handed it over, unix seconds. */
    @SerialName("captured_at") val capturedAt: Long,
    /** The phone that shared it, for coverage (`§8.4`). */
    val device: String? = null,
    /** How it arrived. The ledger attributes it to the owner; this names the door. */
    val source: String = SOURCE,
) {
    companion object {
        const val SOURCE = "kyllene-share"

        fun kindOf(text: String, hasImage: Boolean, links: List<String>): String = when {
            hasImage -> "screenshot"
            links.isNotEmpty() -> "link"
            else -> "text"
        }

        private val LINK = Regex("""https?://[^\s<>"']+""")

        fun linksIn(text: String): List<String> = LINK.findAll(text).map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }.toList()
    }
}

/** A shared file: either a path it was uploaded to on the gateway, or its bytes inline (small screenshots). */
@Serializable
data class OwnerWordFile(
    val name: String,
    val mime: String? = null,
    /** `path` from the gateway's upload endpoint (`POST /api/files/upload-stream`). */
    val path: String? = null,
    /** Base64 of the file, only when it was too small to be worth a second round trip. */
    val data: String? = null,
    val size: Long? = null,
)

/** What the ledger answers: the row it made, if it says. */
@Serializable
data class OwnerWordReceipt(
    val ok: Boolean = true,
    val id: String? = null,
    val revision: Long? = null,
)

/**
 * The Board's owner-word intake on the dashboard the phone already signs in to
 * (`POST /api/worktable/owner-words`).
 *
 * The route is the phone's side of the contract C1 owns with the ledger: it takes exactly one
 * [OwnerWordDraft] and appends it as an owner word from `source = kyllene-share`, with [OwnerWordDraft.text]
 * unchanged. A gateway that doesn't serve it answers 404, which the phone reports as "queued" rather than
 * losing the words (§6, "dropped nowhere").
 */
class OwnerWordsApi(private val client: HttpClient) {

    suspend fun submit(url: GatewayUrl, draft: OwnerWordDraft): ApiResult<OwnerWordReceipt> = apiCall {
        client.post(url.resolve(PATH)) {
            contentType(ContentType.Application.Json)
            setBody(draft)
        }
    }.map { it.body<OwnerWordReceipt>() }

    companion object {
        const val PATH = "api/worktable/owner-words"
    }
}
