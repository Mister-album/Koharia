package koharia.source.komga

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import java.io.File
import java.security.MessageDigest

internal class KomgaMetadataCacheStore(
    context: Context,
) {

    private val cacheDir = LocalTempCacheDirectoryProvider.metadataCacheDir(context)

    fun isEligible(request: Request): Boolean {
        return when (request.method) {
            "GET" -> isEligibleUrl(request.url.toString())
            "POST" -> request.body?.contentType()?.subtype == "json" &&
                SEARCH_LIST_PATHS.any { request.url.encodedPath.endsWith(it) }
            else -> false
        }
    }

    fun load(request: Request): Response? {
        if (!isEligible(request)) return null

        val identity = request.cacheIdentity() ?: return null
        val entry = readEntry(identity) ?: return null
        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", entry.contentType?.toString().orEmpty())
            .header("X-Koharia-Offline-Cache", "metadata")
            .body(entry.body.toResponseBody(entry.contentType))
            .build()
    }

    fun save(request: Request, response: Response): Response {
        if (!isEligible(request) || !response.isSuccessful) return response

        val identity = request.cacheIdentity() ?: return response
        val body = response.body
        val contentType = body.contentType()
        val bodyBytes = body.bytes()

        writeEntry(
            identity = identity,
            body = bodyBytes,
            contentType = contentType,
        )

        return response.newBuilder()
            .body(bodyBytes.toResponseBody(contentType))
            .build()
    }

    fun findLibraryId(contentUrl: String): String? {
        val content = readJsonObject(contentUrl) ?: return null
        content[LIBRARY_ID_FIELD]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val seriesId = content[SERIES_ID_FIELD]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val baseUrl = contentUrl.substringBefore(API_PATH, missingDelimiterValue = "")
        if (baseUrl.isBlank()) return null

        return readJsonObject("$baseUrl$API_PATH/series/$seriesId")
            ?.get(LIBRARY_ID_FIELD)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun readJsonObject(url: String) = readEntry(url)
        ?.let { entry -> runCatching { Json.parseToJsonElement(entry.body.decodeToString()).jsonObject }.getOrNull() }

    private fun readEntry(identity: String): CacheEntry? {
        val bodyFile = bodyFile(identity)
        val metaFile = metaFile(identity)
        if (!bodyFile.exists() || !metaFile.exists()) return null

        return runCatching {
            val metadata = metaFile.readLines()
            if (metadata.size < 3 || metadata[0] != identity) {
                return null
            }

            metadata[2].toLongOrNull() ?: return null

            CacheEntry(
                contentType = metadata[1].ifBlank { null }?.toMediaTypeOrNull(),
                body = bodyFile.readBytes(),
            )
        }.getOrNull()
    }

    private fun writeEntry(identity: String, body: ByteArray, contentType: MediaType?) {
        val bodyFile = bodyFile(identity)
        val metaFile = metaFile(identity)
        val tmpBodyFile = File(bodyFile.parentFile, "${bodyFile.name}.tmp")
        val tmpMetaFile = File(metaFile.parentFile, "${metaFile.name}.tmp")

        runCatching {
            val metadata = buildString {
                appendLine(identity)
                appendLine(contentType?.toString().orEmpty())
                appendLine(System.currentTimeMillis().toString())
            }

            tmpBodyFile.writeBytes(body)
            tmpMetaFile.writeText(metadata)

            if (!tmpBodyFile.renameTo(bodyFile)) {
                throw IllegalStateException("Failed to write metadata cache body for $identity")
            }
            if (!tmpMetaFile.renameTo(metaFile)) {
                throw IllegalStateException("Failed to write metadata cache metadata for $identity")
            }
        }.onFailure {
            tmpBodyFile.delete()
            tmpMetaFile.delete()
        }
    }

    private fun bodyFile(identity: String): File = File(cacheDir, "${key(identity)}.body")

    private fun metaFile(identity: String): File = File(cacheDir, "${key(identity)}.meta")

    private fun key(url: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private data class CacheEntry(
        val contentType: MediaType?,
        val body: ByteArray,
    )

    companion object {
        fun isEligibleUrl(url: String): Boolean {
            if (!url.contains("/api/v1/")) return false
            if (url.endsWith("/file")) return false
            if (PAGE_IMAGE_REGEX.containsMatchIn(url)) return false

            return url.contains("/api/v1/client-settings/") ||
                url.contains("/api/v1/series") ||
                (url.contains("/api/v1/books") && !url.contains("/pages/")) ||
                url.contains("/api/v1/readlists") ||
                url.contains("/api/v1/libraries") ||
                url.contains("/api/v1/collections") ||
                url.contains("/api/v1/genres") ||
                url.contains("/api/v1/tags") ||
                url.contains("/api/v1/publishers") ||
                url.contains("/api/v1/authors")
        }

        private val PAGE_IMAGE_REGEX = Regex("/pages/\\d+(?:\\?.*)?$")
        private val SEARCH_LIST_PATHS = setOf("/api/v1/books/list", "/api/v1/series/list")
        private const val API_PATH = "/api/v1"
        private const val LIBRARY_ID_FIELD = "libraryId"
        private const val SERIES_ID_FIELD = "seriesId"
    }
}

private fun Request.cacheIdentity(): String? {
    if (method == "GET") return url.toString()
    val bodyBytes = runCatching {
        Buffer().use { buffer ->
            body?.writeTo(buffer) ?: return null
            buffer.readByteArray()
        }
    }.getOrNull() ?: return null
    val bodyDigest = MessageDigest.getInstance("SHA-256")
        .digest(bodyBytes)
        .joinToString("") { "%02x".format(it) }
    return "$method $url $bodyDigest"
}
