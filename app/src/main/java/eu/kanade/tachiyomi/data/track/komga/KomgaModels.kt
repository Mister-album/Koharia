package eu.kanade.tachiyomi.data.track.komga

import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val created: String?,
    val lastModified: String?,
    val fileLastModified: String,
    val booksCount: Int,
    val booksReadCount: Int,
    val booksUnreadCount: Int,
    val booksInProgressCount: Int,
    val metadata: SeriesMetadataDto,
    val booksMetadata: BookMetadataAggregationDto,
)

@Serializable
data class SeriesMetadataDto(
    val status: String,
    val created: String?,
    val lastModified: String?,
    val title: String,
    val titleSort: String,
    val summary: String,
    val summaryLock: Boolean,
    val readingDirection: String,
    val readingDirectionLock: Boolean,
    val publisher: String,
    val publisherLock: Boolean,
    val ageRating: Int?,
    val ageRatingLock: Boolean,
    val language: String,
    val languageLock: Boolean,
    val genres: Set<String>,
    val genresLock: Boolean,
    val tags: Set<String>,
    val tagsLock: Boolean,
)

@Serializable
data class BookMetadataAggregationDto(
    val authors: List<AuthorDto> = emptyList(),
    val releaseDate: String?,
    val summary: String,
    val summaryNumber: String,

    val created: String,
    val lastModified: String,
)

@Serializable
data class AuthorDto(
    val name: String,
    val role: String,
)

@Serializable
data class ReadProgressUpdateDto(
    val lastBookRead: Int,
)

@Serializable
data class ReadProgressUpdateV2Dto(
    val lastBookNumberSortRead: Double,
)

@Serializable
data class ReadListDto(
    val id: String,
    val name: String,
    val bookIds: List<String>,
    val createdDate: String,
    val lastModifiedDate: String,
    val filtered: Boolean,
)

@Serializable
data class ReadProgressDto(
    val booksCount: Int,
    val booksReadCount: Int,
    val booksUnreadCount: Int,
    val booksInProgressCount: Int,
    val lastReadContinuousIndex: Int,
) {
    fun toV2() = ReadProgressV2Dto(
        booksCount,
        booksReadCount,
        booksUnreadCount,
        booksInProgressCount,
        lastReadContinuousIndex.toDouble(),
        booksCount.toFloat(),
    )
}

@Serializable
data class ReadProgressV2Dto(
    val booksCount: Int,
    val booksReadCount: Int,
    val booksUnreadCount: Int,
    val booksInProgressCount: Int,
    val lastReadContinuousNumberSort: Double,
    val maxNumberSort: Float,
)

@Serializable
data class PageWrapperDto<T>(
    val content: List<T> = emptyList(),
)

@Serializable
data class BookMediaDto(
    val mediaProfile: String = "",
    val pagesCount: Int = 0,
    val epubDivinaCompatible: Boolean = false,
) {
    val isDivinaCompatibleEpub: Boolean
        get() = mediaProfile == "EPUB" && epubDivinaCompatible && pagesCount > 0
}

@Serializable
data class BookDto(
    val id: String,
    val seriesId: String = "",
    val name: String = "",
    val fileLastModified: String? = null,
    val fileHash: String? = null,
    val sizeBytes: Long = 0L,
    val readProgress: BookReadProgressDto? = null,
    val media: BookMediaDto? = null,
)

@Serializable
data class BookReadProgressDto(
    val completed: Boolean = false,
    val page: Int? = null,
    val readDate: String? = null,
)

@Serializable
data class BookReadProgressUpdateDto(
    val completed: Boolean? = null,
    val page: Int? = null,
)
