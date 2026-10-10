package koharia.source.komga

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import java.io.File
import java.security.MessageDigest

internal class KomgaMetadataCacheStore(
    context: Context,
    private val namespace: () -> String = { "" },
) {

    private val cacheDir = LocalTempCacheDirectoryProvider.metadataCacheDir(context)

    fun isEligible(request: Request): Boolean {
        if (request.isKomgaProgressSync || request.isKomgaNetworkRequired) return false
        return when (request.method) {
            "GET" -> isEligibleUrl(request.url.toString())
            "POST" -> request.body?.contentType()?.subtype == "json" &&
                SEARCH_LIST_PATHS.any { request.url.encodedPath.endsWith(it) }
            else -> false
        }
    }

    fun load(request: Request, minimumFetchedAt: Long = 0): Response? {
        if (!isEligible(request)) return null

        val identity = request.cacheIdentity()?.let { scopedIdentity(it, request) } ?: return null
        return synchronized(cacheLock) {
            runCatching {
                val metadata = metaFile(identity).readLines()
                if (metadata.size < 3 || metadata[0] != identity) return@synchronized null
                val fetchedAt = metadata[2].toLongOrNull() ?: return@synchronized null
                if (fetchedAt < minimumFetchedAt) return@synchronized null
                val file = bodyFile(identity)
                if (!file.isFile) return@synchronized null
                val type = metadata[1].toMediaTypeOrNull()
                val length = file.length()
                val opened = file.source().buffer()
                val body = object : okhttp3.ResponseBody() {
                    override fun contentType() = type
                    override fun contentLength() = length
                    override fun source() = opened
                }
                Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Type", metadata[1])
                    .header("X-Koharia-Offline-Cache", "metadata").body(body).build()
            }.getOrNull()
        }
    }

    fun save(request: Request, response: Response): Response = synchronized(cacheLock) {
        if (!isEligible(request) || !response.isSuccessful) return@synchronized response

        val identity = request.cacheIdentity()?.let { scopedIdentity(it, request) } ?: return@synchronized response
        val body = response.body
        val contentType = body.contentType()
        if (contentType?.subtype?.let { it == "json" || it.endsWith("+json") } != true) return@synchronized response
        if (request.tag(KomgaCachePolicy::class.java) != null) {
            return@synchronized cacheWhileReading(identity, response, contentType)
        }
        if (body.contentLength() > MAX_CACHE_BYTES) return@synchronized response
        val bodyBytes = response.peekBody(MAX_CACHE_BYTES + 1).use { it.bytes() }
        if (bodyBytes.size > MAX_CACHE_BYTES) return@synchronized response

        writeEntry(
            identity = identity,
            body = bodyBytes,
            contentType = contentType,
        )

        response
    }

    private fun cacheWhileReading(identity: String, response: Response, type: MediaType?): Response {
        val temporary = runCatching { File.createTempFile("shelf-", ".tmp", cacheDir) }.getOrNull() ?: return response
        var output = runCatching { temporary.sink().buffer() }.getOrNull()
        if (output == null) {
            temporary.delete()
            return response
        }
        var complete = false
        val original = response.body
        val stream = object : ForwardingSource(original.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                val target = output ?: return read
                try {
                    if (read > 0) {
                        sink.copyTo(target.buffer, sink.size - read, read)
                        target.emitCompleteSegments()
                    } else if (read == -1L && !complete) {
                        target.close()
                        output = null
                        synchronized(cacheLock) {
                            val metadata = File.createTempFile("shelf-meta-", ".tmp", cacheDir)
                            try {
                                metadata.writeText("$identity\n${type.orEmptyText()}\n${System.currentTimeMillis()}\n")
                                check(temporary.renameTo(bodyFile(identity)))
                                check(metadata.renameTo(metaFile(identity)))
                                complete = true
                            } finally {
                                metadata.delete()
                            }
                        }
                    }
                } catch (_: java.io.IOException) {
                    runCatching { target.close() }
                    output = null
                    temporary.delete()
                } catch (_: IllegalStateException) {
                    output = null
                    temporary.delete()
                }
                return read
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { output?.close() }
                    output = null
                    if (!complete) temporary.delete()
                }
            }
        }.buffer()
        return response.newBuilder().body(object : okhttp3.ResponseBody() {
            override fun contentType() = type
            override fun contentLength() = original.contentLength()
            override fun source() = stream
        }).build()
    }

    private fun MediaType?.orEmptyText() = this?.toString().orEmpty()

    fun findLibraryId(contentUrl: String): String? {
        val content = readJsonObject(contentUrl) ?: return null
        return content.findLibraryId(contentUrl)
    }

    fun findLibraryIds(contentUrl: String): Set<String> {
        findLibraryId(contentUrl)?.let { return setOf(it) }
        if (!contentUrl.substringBefore('?').trimEnd('/').contains("$API_PATH/readlists/")) return emptySet()

        val booksUrl = contentUrl.trimEnd('/') + READ_LIST_BOOKS_QUERY
        val books = readJsonObject(booksUrl)?.get(CONTENT_FIELD) as? JsonArray ?: return emptySet()
        return books.mapNotNullTo(linkedSetOf()) { element ->
            runCatching { element.jsonObject.findLibraryId(contentUrl) }.getOrNull()
        }
    }

    private fun JsonObject.findLibraryId(contentUrl: String): String? {
        this[LIBRARY_ID_FIELD]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val seriesId = this[SERIES_ID_FIELD]?.jsonPrimitive?.contentOrNull
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

    private fun scopedIdentity(identity: String, request: Request? = null): String =
        (
            request?.tag(KomgaCacheNamespace::class.java)?.value?.takeIf {
                it.isNotEmpty()
            } ?: namespace()
            ).takeIf { it.isNotEmpty() }
            ?.let { "$it:$identity" } ?: identity

    private fun readJsonObject(url: String) = readEntry(scopedIdentity(url))
        ?.let { entry -> runCatching { Json.parseToJsonElement(entry.body.decodeToString()).jsonObject }.getOrNull() }

    private fun readEntry(identity: String): CacheEntry? = synchronized(cacheLock) {
        val bodyFile = bodyFile(identity)
        val metaFile = metaFile(identity)
        if (!bodyFile.exists() || !metaFile.exists() || bodyFile.length() > MAX_CACHE_BYTES) {
            return@synchronized null
        }

        runCatching {
            val metadata = metaFile.readLines()
            if (metadata.size < 3 || metadata[0] != identity) {
                return@synchronized null
            }

            val fetchedAt = metadata[2].toLongOrNull() ?: return@synchronized null

            CacheEntry(
                fetchedAt = fetchedAt,
                contentType = metadata[1].ifBlank { null }?.toMediaTypeOrNull(),
                body =
                bodyFile.source().buffer().use { it.readByteArray(minOf(bodyFile.length(), MAX_CACHE_BYTES + 1)) }
                    .takeIf { it.size <= MAX_CACHE_BYTES } ?: return@synchronized null,
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
        val fetchedAt: Long,
        val contentType: MediaType?,
        val body: ByteArray,
    )

    companion object {
        internal const val MAX_CACHE_BYTES = 2L * 1024 * 1024
        private val cacheLock = Any()

        fun isEligibleUrl(url: String): Boolean {
            val path = url.toHttpUrlOrNull()?.encodedPath ?: return false
            if (path.endsWith("/api/v2/authors")) return true
            if (!path.contains("/api/v1/")) return false
            if (path.endsWith("/file")) return false
            if (PAGE_IMAGE_REGEX.containsMatchIn(url)) return false

            return path.contains("/api/v1/client-settings/") ||
                path.contains("/api/v1/series") ||
                (path.contains("/api/v1/books") && !path.contains("/pages/")) ||
                path.contains("/api/v1/readlists") ||
                path.contains("/api/v1/libraries") ||
                path.contains("/api/v1/collections") ||
                path.contains("/api/v1/genres") ||
                path.contains("/api/v1/tags") ||
                path.contains("/api/v1/publishers") ||
                path.contains("/api/v1/age-ratings") ||
                path.contains("/api/v1/languages") ||
                path.contains("/api/v1/authors")
        }

        private val PAGE_IMAGE_REGEX = Regex("/pages/\\d+(?:\\?.*)?$")
        private val SEARCH_LIST_PATHS = setOf("/api/v1/books/list", "/api/v1/series/list")
        private const val API_PATH = "/api/v1"
        private const val CONTENT_FIELD = "content"
        private const val LIBRARY_ID_FIELD = "libraryId"
        private const val SERIES_ID_FIELD = "seriesId"
        private const val READ_LIST_BOOKS_QUERY = "/books?unpaged=true&media_status=READY&deleted=false"
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
