package koharia.source.komga

import android.content.Context
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class KomgaMetadataCacheStore(
    context: Context,
) {

    private val cacheDir = LocalTempCacheDirectoryProvider.metadataCacheDir(context)

    fun isEligible(request: Request): Boolean {
        if (request.method != "GET") return false
        return isEligibleUrl(request.url.toString())
    }

    fun load(request: Request): Response? {
        if (!isEligible(request)) return null

        val entry = readEntry(request.url.toString()) ?: return null
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

        val body = response.body
        val contentType = body.contentType()
        val bodyBytes = body.bytes()

        writeEntry(
            url = request.url.toString(),
            body = bodyBytes,
            contentType = contentType,
        )

        return response.newBuilder()
            .body(bodyBytes.toResponseBody(contentType))
            .build()
    }

    private fun readEntry(url: String): CacheEntry? {
        val bodyFile = bodyFile(url)
        val metaFile = metaFile(url)
        if (!bodyFile.exists() || !metaFile.exists()) return null

        return runCatching {
            val metadata = metaFile.readLines()
            if (metadata.size < 3 || metadata[0] != url) {
                return null
            }

            val savedAt = metadata[2].toLongOrNull() ?: return null
            if (System.currentTimeMillis() - savedAt > MAX_STALE_MILLIS) {
                bodyFile.delete()
                metaFile.delete()
                return null
            }

            CacheEntry(
                contentType = metadata[1].ifBlank { null }?.toMediaTypeOrNull(),
                body = bodyFile.readBytes(),
            )
        }.getOrNull()
    }

    private fun writeEntry(url: String, body: ByteArray, contentType: MediaType?) {
        val bodyFile = bodyFile(url)
        val metaFile = metaFile(url)
        val tmpBodyFile = File(bodyFile.parentFile, "${bodyFile.name}.tmp")
        val tmpMetaFile = File(metaFile.parentFile, "${metaFile.name}.tmp")

        runCatching {
            val metadata = buildString {
                appendLine(url)
                appendLine(contentType?.toString().orEmpty())
                appendLine(System.currentTimeMillis().toString())
            }

            tmpBodyFile.writeBytes(body)
            tmpMetaFile.writeText(metadata)

            if (!tmpBodyFile.renameTo(bodyFile)) {
                throw IllegalStateException("Failed to write metadata cache body for $url")
            }
            if (!tmpMetaFile.renameTo(metaFile)) {
                throw IllegalStateException("Failed to write metadata cache metadata for $url")
            }
        }.onFailure {
            tmpBodyFile.delete()
            tmpMetaFile.delete()
        }
    }

    private fun bodyFile(url: String): File = File(cacheDir, "${key(url)}.body")

    private fun metaFile(url: String): File = File(cacheDir, "${key(url)}.meta")

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
        private val MAX_STALE_MILLIS = TimeUnit.DAYS.toMillis(7)

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
    }
}
