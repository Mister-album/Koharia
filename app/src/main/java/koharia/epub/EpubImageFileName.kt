package koharia.epub

import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil

internal fun buildEpubImageBaseName(
    seriesTitle: String?,
    bookTitle: String?,
    originalFileName: String,
    extension: String,
): String {
    val normalizedExtension = extension.trim().trimStart('.')
    val extensionBytes = normalizedExtension
        .takeIf(String::isNotBlank)
        ?.let { ".$it".byteSize() }
        ?: 0
    val maxBaseNameBytes = (DiskUtil.MAX_FILE_NAME_BYTES - extensionBytes).coerceAtLeast(1)
    var result = originalFileName.validFileNameComponent(maxBaseNameBytes)
        ?: "image".validFileNameComponent(maxBaseNameBytes)
        ?: "i"

    listOf(bookTitle, seriesTitle).forEach { component ->
        val remainingBytes = maxBaseNameBytes - result.byteSize() - EPUB_IMAGE_NAME_SEPARATOR.byteSize()
        if (remainingBytes <= 0) return@forEach
        component.validFileNameComponent(remainingBytes)?.let { prefix ->
            result = "$prefix$EPUB_IMAGE_NAME_SEPARATOR$result"
        }
    }
    return result
}

private fun String?.validFileNameComponent(maxBytes: Int): String? {
    val value = this
        ?.trim()
        ?.takeIf { it.trim('.', ' ').isNotEmpty() }
        ?: return null
    return DiskUtil.buildValidFilename(value, maxBytes).takeIf(String::isNotBlank)
}

private const val EPUB_IMAGE_NAME_SEPARATOR = " - "
