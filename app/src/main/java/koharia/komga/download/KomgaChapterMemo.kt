package koharia.komga.download

import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.isEpub
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

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
    const val IS_EPUB = "isEpub"
    const val FILE_LAST_MODIFIED = "fileLastModified"
    const val FILE_NAME = "fileName"
    const val PAGES_COUNT = "pagesCount"

    private const val PAGE_CACHE_VERSION_FRAGMENT = "#koharia-publication="

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
        val fingerprint = buildMemo(buildFingerprint(baseUrl, book))
        return buildJsonObject {
            fingerprint.forEach { (key, value) -> put(key, value) }
            put(IS_EPUB, book.isEpub)
            if (book.fileLastModified.isNotBlank()) put(FILE_LAST_MODIFIED, book.fileLastModified)
            if (book.name.isNotBlank()) put(FILE_NAME, book.name)
            if (book.media.pagesCount > 0) put(PAGES_COUNT, book.media.pagesCount)
        }
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

    fun mergeInto(
        existing: JsonObject,
        baseUrl: String,
        book: BookDto,
    ): JsonObject {
        val additions = buildMemo(baseUrl, book)
        return buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            additions.forEach { (key, value) -> put(key, value) }
        }
    }

    fun mergePublicationMetadata(
        existing: JsonObject,
        bookUrl: String,
        fileHash: String?,
        fileLastModified: String?,
        sizeBytes: Long,
        fileName: String?,
        isEpub: Boolean,
        pagesCount: Int,
    ): JsonObject {
        return buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            if (bookUrl.isNotBlank()) put(BOOK_URL, bookUrl)
            fileHash?.takeIf(String::isNotBlank)?.let { put(FILE_HASH, it) }
            fileLastModified?.takeIf(String::isNotBlank)?.let { put(FILE_LAST_MODIFIED, it) }
            if (sizeBytes > 0L) put(SIZE_BYTES, sizeBytes)
            fileName?.takeIf(String::isNotBlank)?.let { put(FILE_NAME, it) }
            put(IS_EPUB, isEpub)
            if (pagesCount > 0) put(PAGES_COUNT, pagesCount)
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

    fun isEpub(memo: JsonObject): Boolean? = memo[IS_EPUB]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

    fun fileLastModified(memo: JsonObject): String? = memo.string(FILE_LAST_MODIFIED)

    fun fileName(memo: JsonObject): String? = memo.string(FILE_NAME)

    fun pagesCount(memo: JsonObject): Int? = memo.long(PAGES_COUNT)?.toInt()?.takeIf { it > 0 }

    fun publicationVersion(memo: JsonObject): String? {
        val fileHash = memo.string(FILE_HASH)
        if (!fileHash.isNullOrBlank()) return "hash:$fileHash"

        val fileLastModified = fileLastModified(memo)
        val sizeBytes = memo.long(SIZE_BYTES) ?: 0L
        return if (!fileLastModified.isNullOrBlank() && sizeBytes > 0L) {
            "modified:$fileLastModified:size:$sizeBytes"
        } else {
            null
        }
    }

    fun pageImageCacheToken(memo: JsonObject): String? = publicationVersion(memo)?.cacheToken()

    fun versionedPageImageUrl(imageUrl: String, memo: JsonObject): String {
        return versionedPageImageUrl(imageUrl, pageImageCacheToken(memo))
    }

    fun versionedPageImageUrl(imageUrl: String, cacheToken: String?): String {
        val networkUrl = networkPageImageUrl(imageUrl)
        return if (cacheToken != null) {
            "$networkUrl$PAGE_CACHE_VERSION_FRAGMENT$cacheToken"
        } else {
            networkUrl
        }
    }

    fun networkPageImageUrl(imageUrl: String): String = imageUrl.substringBefore(PAGE_CACHE_VERSION_FRAGMENT)

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull()

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? {
        return content.takeIf { it.isNotBlank() }
    }

    private val kotlinx.serialization.json.JsonPrimitive.longOrNull: Long?
        get() = content.toLongOrNull()

    private fun String.cacheToken(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
