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
    val components = buildList {
        seriesTitle.validFileNameComponent(maxBaseNameBytes)?.let(::add)
        bookTitle.validFileNameComponent(maxBaseNameBytes)?.let(::add)
        add(
            originalFileName.validFileNameComponent(maxBaseNameBytes)
                ?: "image".validFileNameComponent(maxBaseNameBytes)
                ?: "i",
        )
    }
    return components.fitFileNameComponents(maxBaseNameBytes)
}

private fun String?.validFileNameComponent(maxBytes: Int): String? {
    val value = this
        ?.trim()
        ?.takeIf { it.trim('.', ' ').isNotEmpty() }
        ?: return null
    return DiskUtil.buildValidFilename(value, maxBytes).takeIf(String::isNotBlank)
}

private fun List<String>.fitFileNameComponents(maxBytes: Int): String {
    val fullName = joinToString(EPUB_IMAGE_NAME_SEPARATOR)
    if (fullName.byteSize() <= maxBytes) return fullName

    val separatorBytes = EPUB_IMAGE_NAME_SEPARATOR.byteSize() * (size - 1)
    val contentBudget = maxBytes - separatorBytes
    if (contentBudget < size) {
        return DiskUtil.truncateToLength(last(), maxBytes).ifBlank { "i" }
    }

    val componentSizes = map(String::byteSize)
    val componentBudgets = IntArray(size)
    val remainingComponents = indices.toMutableList()
    var remainingBudget = contentBudget
    while (remainingComponents.isNotEmpty()) {
        val sharedBudget = remainingBudget / remainingComponents.size
        val fittingComponents = remainingComponents.filter { componentSizes[it] <= sharedBudget }
        if (fittingComponents.isEmpty()) {
            val extraBytes = remainingBudget % remainingComponents.size
            remainingComponents.forEachIndexed { index, componentIndex ->
                componentBudgets[componentIndex] = sharedBudget + if (index < extraBytes) 1 else 0
            }
            break
        }
        fittingComponents.forEach { componentIndex ->
            componentBudgets[componentIndex] = componentSizes[componentIndex]
            remainingBudget -= componentSizes[componentIndex]
        }
        remainingComponents.removeAll(fittingComponents.toSet())
    }

    return mapIndexed { index, component ->
        DiskUtil.truncateToLength(component, componentBudgets[index])
    }.joinToString(EPUB_IMAGE_NAME_SEPARATOR)
}

private const val EPUB_IMAGE_NAME_SEPARATOR = " - "
