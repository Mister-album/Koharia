package koharia.komga.api.dto

import eu.kanade.tachiyomi.source.model.SManga
import koharia.komga.download.KomgaChapterMemo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PageWrapperDto<T>(
    val content: List<T>,
    val empty: Boolean = false,
    val first: Boolean = false,
    val last: Boolean = false,
    val number: Long = 0,
    val numberOfElements: Long = 0,
    val size: Long = 0,
    val totalElements: Long = 0,
    val totalPages: Long = 0,
)

@Serializable
data class LibraryDto(
    val id: String,
    val name: String,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val roles: List<String> = emptyList(),
)

@Serializable
data class ClientSettingDto(
    val value: String? = null,
)

@Serializable
data class ClientSettingUpdateDto(
    val value: String? = null,
    val allowUnauthenticated: Boolean = false,
)

@Serializable
data class AuthorDto(
    val name: String,
    val role: String,
)

@Serializable
data class CollectionDto(
    val id: String,
    val name: String,
    val ordered: Boolean,
    val seriesIds: List<String> = emptyList(),
    val createdDate: String,
    val lastModifiedDate: String,
    val filtered: Boolean = false,
)

@Serializable
data class AlternateTitleDto(
    val label: String,
    val title: String,
)

@Serializable
data class WebLinkDto(
    val label: String,
    val url: String,
)

@Serializable
data class SeriesMetadataDto(
    val status: String = "",
    val created: String? = null,
    val lastModified: String? = null,
    val title: String,
    val titleSort: String = title,
    val summary: String = "",
    val summaryLock: Boolean = false,
    val readingDirection: String = "",
    val readingDirectionLock: Boolean = false,
    val publisher: String = "",
    val publisherLock: Boolean = false,
    val ageRating: Int? = null,
    val ageRatingLock: Boolean = false,
    val language: String = "",
    val languageLock: Boolean = false,
    val genres: Set<String> = emptySet(),
    val genresLock: Boolean = false,
    val tags: Set<String> = emptySet(),
    val tagsLock: Boolean = false,
    val totalBookCount: Int? = null,
    val alternateTitles: List<AlternateTitleDto> = emptyList(),
    val alternateTitlesLock: Boolean = false,
    val links: List<WebLinkDto> = emptyList(),
    val linksLock: Boolean = false,
)

@Serializable
data class BookMetadataAggregationDto(
    val authors: List<AuthorDto> = emptyList(),
    val tags: Set<String> = emptySet(),
    val releaseDate: String? = null,
    val summary: String = "",
    val summaryNumber: String = "",
    val created: String = "",
    val lastModified: String = "",
)

@Serializable
data class SeriesDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String,
    val booksCount: Int = 0,
    val metadata: SeriesMetadataDto,
    val booksMetadata: BookMetadataAggregationDto = BookMetadataAggregationDto(),
)

@Serializable
data class MediaDto(
    val status: String = "",
    val mediaType: String = "",
    val pagesCount: Int = 0,
    val mediaProfile: String = "DIVINA",
    val epubDivinaCompatible: Boolean = false,
)

@Serializable
data class BookMetadataDto(
    val title: String,
    val titleLock: Boolean = false,
    val summary: String = "",
    val summaryLock: Boolean = false,
    val number: String = "",
    val numberLock: Boolean = false,
    val numberSort: Float = 0F,
    val numberSortLock: Boolean = false,
    val releaseDate: String? = null,
    val releaseDateLock: Boolean = false,
    val authors: List<AuthorDto> = emptyList(),
    val authorsLock: Boolean = false,
    val tags: Set<String> = emptySet(),
    val tagsLock: Boolean = false,
    val isbn: String = "",
    val isbnLock: Boolean = false,
    val links: List<WebLinkDto> = emptyList(),
    val linksLock: Boolean = false,
)

@Serializable
data class BookDto(
    val id: String,
    val seriesId: String = "",
    val seriesTitle: String = "",
    val name: String,
    val number: Float = 0F,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String,
    val fileHash: String? = null,
    val sizeBytes: Long = 0,
    val size: String = "",
    val media: MediaDto = MediaDto(),
    val metadata: BookMetadataDto,
)

val MediaDto.isEpub: Boolean
    get() = mediaProfile == "EPUB"

val MediaDto.isDivinaCompatibleEpub: Boolean
    get() = isEpub && epubDivinaCompatible

val BookDto.isEpub: Boolean
    get() = media.isEpub

@Serializable
data class PageDto(
    val number: Int,
    val fileName: String = "",
    val mediaType: String = "",
)

@Serializable
data class ReadListDto(
    val id: String,
    val name: String,
    val summary: String = "",
    val bookIds: List<String> = emptyList(),
    val createdDate: String,
    val lastModifiedDate: String,
    val filtered: Boolean = false,
)

fun SeriesDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    title = metadata.title
    url = "$baseUrl/api/v1/series/$id"
    thumbnail_url = "$url/thumbnail"
    status = when {
        metadata.status == "ENDED" &&
            metadata.totalBookCount != null &&
            booksCount < metadata.totalBookCount -> SManga.PUBLISHING_FINISHED
        metadata.status == "ENDED" -> SManga.COMPLETED
        metadata.status == "ONGOING" -> SManga.ONGOING
        metadata.status == "ABANDONED" -> SManga.CANCELLED
        metadata.status == "HIATUS" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
    genre = (metadata.genres + metadata.tags + booksMetadata.tags).sorted().distinct().joinToString(", ")
    description = metadata.summary.ifBlank { booksMetadata.summary }
    booksMetadata.authors.groupBy({ it.role }, { it.name }).let { authors ->
        author = authors["writer"]?.distinct()?.joinToString()
        artist = authors["penciller"]?.distinct()?.joinToString()
    }
    memo = buildMemo(metadata, booksMetadata, booksCount)
}

fun BookDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    title = metadata.title
    url = "$baseUrl/api/v1/books/$id"
    thumbnail_url = "$url/thumbnail"
    status = SManga.UNKNOWN
    genre = metadata.tags.distinct().joinToString(", ")
    description = metadata.summary
    author = metadata.authors.joinToString { it.name }
    artist = author
}

fun BookDto.toChapterMemo(baseUrl: String): JsonObject = KomgaChapterMemo.buildMemo(baseUrl, this)

fun ReadListDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    title = name
    description = summary
    url = "$baseUrl/api/v1/readlists/$id"
    thumbnail_url = "$url/thumbnail"
    status = SManga.UNKNOWN
}

fun BookDto.formatChapterName(template: String, isFromReadList: Boolean): String {
    val values = mapOf(
        "title" to metadata.title,
        "seriesTitle" to seriesTitle,
        "number" to metadata.number,
        "createdDate" to created.orEmpty(),
        "releaseDate" to metadata.releaseDate.orEmpty(),
        "size" to size,
        "sizeBytes" to sizeBytes.toString(),
    )

    val formatted = values.entries.fold(template) { acc, (key, value) ->
        acc.replace("{$key}", value)
    }

    return buildString {
        if (isFromReadList) {
            append(seriesTitle)
            append(' ')
        }
        append(formatted)
    }
}

private fun buildMemo(
    metadata: SeriesMetadataDto,
    booksMetadata: BookMetadataAggregationDto = BookMetadataAggregationDto(),
    booksCount: Int = 0,
): JsonObject = buildJsonObject {
    if (metadata.readingDirection.isNotBlank()) {
        put("readingDirection", metadata.readingDirection)
    }
    if (metadata.publisher.isNotBlank()) {
        put("publisher", metadata.publisher)
    }
    if (metadata.ageRating != null) {
        put("ageRating", metadata.ageRating)
    }
    if (metadata.language.isNotBlank()) {
        put("language", metadata.language)
    }
    if (metadata.totalBookCount != null) {
        put("totalBookCount", metadata.totalBookCount)
    }
    if (booksCount > 0) {
        put("booksCount", booksCount)
    }
    if (!booksMetadata.releaseDate.isNullOrBlank()) {
        put("releaseDate", booksMetadata.releaseDate)
    }
}
