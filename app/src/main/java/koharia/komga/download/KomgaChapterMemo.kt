package koharia.komga.download

import koharia.komga.api.dto.BookDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class KomgaBookFingerprint(
    val bookUrl: String,
    val seriesUrl: String,
    val fileHash: String,
    val sizeBytes: Long,
    val seriesTitle: String,
    val bookTitle: String,
    val numberSort: Double,
    val isbn: String,
)

object KomgaChapterMemo {
    const val BOOK_URL = "bookUrl"
    const val SERIES_URL = "seriesUrl"
    const val FILE_HASH = "fileHash"
    const val SIZE_BYTES = "sizeBytes"
    const val SERIES_TITLE = "seriesTitle"
    const val BOOK_TITLE = "bookTitle"
    const val NUMBER_SORT = "numberSort"
    const val ISBN = "isbn"

    fun buildFingerprint(baseUrl: String, book: BookDto): KomgaBookFingerprint {
        return KomgaBookFingerprint(
            bookUrl = "$baseUrl/api/v1/books/${book.id}",
            seriesUrl = book.seriesId.takeIf { it.isNotBlank() }?.let { "$baseUrl/api/v1/series/$it" }.orEmpty(),
            fileHash = book.fileHash.orEmpty(),
            sizeBytes = book.sizeBytes,
            seriesTitle = book.seriesTitle,
            bookTitle = book.metadata.title,
            numberSort = book.metadata.numberSort.toDouble(),
            isbn = book.metadata.isbn,
        )
    }

    fun buildMemo(baseUrl: String, book: BookDto): JsonObject {
        return buildMemo(buildFingerprint(baseUrl, book))
    }

    fun buildMemo(fingerprint: KomgaBookFingerprint): JsonObject {
        return buildJsonObject {
            put(BOOK_URL, fingerprint.bookUrl)
            if (fingerprint.seriesUrl.isNotBlank()) {
                put(SERIES_URL, fingerprint.seriesUrl)
            }
            if (fingerprint.fileHash.isNotBlank()) {
                put(FILE_HASH, fingerprint.fileHash)
            }
            if (fingerprint.sizeBytes > 0L) {
                put(SIZE_BYTES, fingerprint.sizeBytes)
            }
            if (fingerprint.seriesTitle.isNotBlank()) {
                put(SERIES_TITLE, fingerprint.seriesTitle)
            }
            if (fingerprint.bookTitle.isNotBlank()) {
                put(BOOK_TITLE, fingerprint.bookTitle)
            }
            put(NUMBER_SORT, fingerprint.numberSort)
            if (fingerprint.isbn.isNotBlank()) {
                put(ISBN, fingerprint.isbn)
            }
        }
    }

    fun mergeInto(
        existing: JsonObject,
        fingerprint: KomgaBookFingerprint,
    ): JsonObject {
        val additions = buildMemo(fingerprint)
        if (additions.isEmpty()) return existing
        return buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            additions.forEach { (key, value) -> put(key, value) }
        }
    }

    fun readFingerprint(memo: JsonObject): KomgaBookFingerprint? {
        val bookUrl = memo.string(BOOK_URL).orEmpty()
        if (bookUrl.isBlank()) return null

        return KomgaBookFingerprint(
            bookUrl = bookUrl,
            seriesUrl = memo.string(SERIES_URL).orEmpty(),
            fileHash = memo.string(FILE_HASH).orEmpty(),
            sizeBytes = memo.long(SIZE_BYTES) ?: 0L,
            seriesTitle = memo.string(SERIES_TITLE).orEmpty(),
            bookTitle = memo.string(BOOK_TITLE).orEmpty(),
            numberSort = memo.double(NUMBER_SORT) ?: 0.0,
            isbn = memo.string(ISBN).orEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull()

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? {
        return content.takeIf { it.isNotBlank() }
    }

    private val kotlinx.serialization.json.JsonPrimitive.longOrNull: Long?
        get() = content.toLongOrNull()
}
