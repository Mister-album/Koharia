package koharia.importing

import android.content.Context
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object IncomingMediaSessionLocator {
    private const val SCHEME = "koharia-incoming-v1"
    private const val CACHE_DIRECTORY = "incoming-media"

    data class Location(
        val sessionId: String,
        val fileName: String?,
    )

    fun seriesUrl(sourceId: Long, sessionId: String): String {
        return "$SCHEME://$sourceId/${encode(sessionId)}"
    }

    fun chapterUrl(sourceId: Long, sessionId: String, fileName: String): String {
        return "$SCHEME://$sourceId/${encode(sessionId)}/${encode(fileName)}"
    }

    fun location(url: String, sourceId: Long): Location? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != sourceId.toString()) return null
        val segments = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        val sessionId = segments.firstOrNull()?.let(::decode)?.takeIf(::isSafeSegment) ?: return null
        val fileName = segments.getOrNull(1)?.let(::decode)?.takeIf(::isSafeFileName)
        if (segments.size > 2 || (segments.size == 2 && fileName == null)) return null
        return Location(sessionId, fileName)
    }

    fun cacheRoot(context: Context): File = File(context.cacheDir, CACHE_DIRECTORY)

    fun sessionDirectory(context: Context, sessionId: String): File? {
        if (!isSafeSegment(sessionId)) return null
        return File(cacheRoot(context), sessionId).takeIf { it.isDirectory }
    }

    fun chapterFile(context: Context, url: String, sourceId: Long): File? {
        val location = location(url, sourceId) ?: return null
        val fileName = location.fileName ?: return null
        val root = cacheRoot(context).canonicalFile
        val file = File(File(root, location.sessionId), fileName).canonicalFile
        return file.takeIf { it.isFile && it.toPath().startsWith(root.toPath()) }
    }

    fun isSessionUrl(url: String, sourceId: Long): Boolean = location(url, sourceId) != null

    private fun isSafeSegment(value: String): Boolean {
        return value.isNotBlank() && value != "." && value != ".." &&
            '/' !in value && '\\' !in value
    }

    private fun isSafeFileName(value: String): Boolean {
        return isSafeSegment(value) && value.none { it.code < 32 }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
