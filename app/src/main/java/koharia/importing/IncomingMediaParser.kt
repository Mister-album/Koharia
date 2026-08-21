package koharia.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import koharia.connection.ConnectionMediaImportItem
import koharia.media.LocalMediaFormats
import tachiyomi.core.common.util.lang.withIOContext
import java.util.Locale

internal object IncomingMediaParser {

    suspend fun parse(context: Context, uriValues: List<String>): List<ConnectionMediaImportItem> = withIOContext {
        uriValues.distinct().mapNotNull { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@mapNotNull null
            val metadata = queryMetadata(context, uri)
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val header = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ByteArray(512).let { buffer ->
                        val count = input.read(buffer)
                        if (count > 0) buffer.copyOf(count) else byteArrayOf()
                    }
                }
            }.getOrNull() ?: byteArrayOf()
            val extension = detectMediaExtension(metadata.displayName, mimeType, header)
                ?: return@mapNotNull null
            val displayName = normalizedMediaDisplayName(metadata.displayName, extension)
            ConnectionMediaImportItem(
                uri = value,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = metadata.sizeBytes,
                extension = extension,
            )
        }
    }

    private fun queryMetadata(context: Context, uri: Uri): MediaMetadata {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                MediaMetadata(
                    displayName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString)
                        ?.takeIf(String::isNotBlank),
                    sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
                )
            }
        }.getOrNull() ?: MediaMetadata(
            displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank),
            sizeBytes = null,
        )
    }

    private data class MediaMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )
}

internal fun detectMediaExtension(
    displayName: String?,
    mimeType: String?,
    header: ByteArray,
): String? {
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it in SUPPORTED_MEDIA_EXTENSIONS }
    if (extension != null) return extension

    if (mimeType?.lowercase(Locale.ROOT) in LocalMediaFormats.images.mimeTypes) {
        return LocalMediaFormats.extensionFromMimeType(mimeType)
    }

    return when (mimeType?.lowercase(Locale.ROOT)) {
        "application/epub+zip" -> "epub"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
        "application/x-palm-database",
        -> "mobi"
        "image/vnd.djvu", "image/x-djvu" -> "djvu"
        "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
        "application/vnd.comicbook-rar", "application/x-cbr" -> "cbr"
        "application/zip", "application/x-zip-compressed" -> detectZipContainerExtension(header)
        "application/rar", "application/vnd.rar", "application/x-rar-compressed" -> "rar"
        "application/x-7z-compressed" -> "7z"
        "application/x-tar" -> "tar"
        else -> detectMediaExtensionFromHeader(header)
    }.takeIf { it in SUPPORTED_MEDIA_EXTENSIONS }
}

private fun detectMediaExtensionFromHeader(header: ByteArray): String? {
    return when {
        header.startsWith("%PDF-".encodeToByteArray()) -> "pdf"
        header.startsWith("AT&TFORM".encodeToByteArray()) -> "djvu"
        header.size >= 68 && header.copyOfRange(64, 68).contentEquals("MOBI".encodeToByteArray()) -> "mobi"
        header.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> "jpg"
        header.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "png"
        header.startsWith("GIF8".encodeToByteArray()) -> "gif"
        header.size >= 12 && header.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()) -> "webp"
        header.startsWith(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) -> detectZipContainerExtension(header)
        header.startsWith(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> "rar"
        header.startsWith(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) -> "7z"
        header.size >= 262 && header.copyOfRange(257, 262).contentEquals("ustar".encodeToByteArray()) -> "tar"
        else -> null
    }
}

private fun detectZipContainerExtension(header: ByteArray): String {
    val epubMime = "application/epub+zip".encodeToByteArray()
    return if (header.indexOf(epubMime) >= 0) "epub" else "zip"
}

private fun ByteArray.indexOf(sequence: ByteArray): Int {
    if (sequence.isEmpty() || sequence.size > size) return -1
    return (0..size - sequence.size).firstOrNull { start ->
        sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
    } ?: -1
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}

private fun normalizedMediaDisplayName(displayName: String?, extension: String): String {
    val candidate = displayName
        ?.substringAfterLast('/')
        ?.replace(Regex("[\\u0000-\\u001f]"), "")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: "Imported media.$extension"
    val currentExtension = candidate.substringAfterLast('.', missingDelimiterValue = "")
    return if (currentExtension.lowercase(Locale.ROOT) == extension) {
        candidate
    } else {
        "${candidate.substringBeforeLast('.', missingDelimiterValue = candidate)}.$extension"
    }
}

internal val SUPPORTED_MEDIA_EXTENSIONS = LocalMediaFormats.allExtensions
