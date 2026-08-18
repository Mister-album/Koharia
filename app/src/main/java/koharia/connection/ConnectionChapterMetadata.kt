package koharia.connection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object ConnectionChapterMetadata {
    private const val SIZE_BYTES = "sizeBytes"
    private const val FILE_HASH = "fileHash"
    private const val FILE_LAST_MODIFIED = "fileLastModified"
    private const val EMBEDDED_FILE_SIZE = "embeddedFileSize"
    private const val PAGES_COUNT = "pagesCount"

    fun sizeBytes(memo: JsonObject): Long? = memo.long(SIZE_BYTES)?.takeIf { it > 0L }

    fun pagesCount(memo: JsonObject): Int? = memo.long(PAGES_COUNT)?.toInt()?.takeIf { it > 0 }

    fun publicationVersion(memo: JsonObject): String? {
        val fileHash = memo.string(FILE_HASH)
        if (fileHash != null) return "hash:$fileHash"

        val fileLastModified = memo.string(FILE_LAST_MODIFIED)
        val sizeBytes = memo.long(SIZE_BYTES) ?: 0L
        return if (fileLastModified != null && sizeBytes > 0L) {
            "modified:$fileLastModified:size:$sizeBytes"
        } else {
            null
        }
    }

    fun removeTrailingEmbeddedFileSize(title: String, memo: JsonObject): String {
        val embeddedFileSize = memo.string(EMBEDDED_FILE_SIZE) ?: return title
        val withoutEmbeddedFileSize = title.replace(
            Regex("\\s*\\(\\s*${Regex.escape(embeddedFileSize)}\\s*\\)\\s*$", RegexOption.IGNORE_CASE),
            "",
        )
        return withoutEmbeddedFileSize.ifBlank { title }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
}
