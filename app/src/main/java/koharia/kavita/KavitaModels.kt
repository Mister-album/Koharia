package koharia.kavita

import koharia.connection.LibraryContentScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Serializable
data class KavitaAccount(
    val id: Long = 0,
    val username: String = "",
    val token: String = "",
    val roles: List<String> = emptyList(),
    val kavitaVersion: String = "",
    val createdUtc: String = "",
    val installId: String = "",
) {
    val principal get() = username.lowercase(java.util.Locale.ROOT)
    val identity get() = KavitaAccountIdentity(id, principal, createdUtc, installId)
}

@Serializable
data class KavitaAccountIdentity(
    val userId: Long = 0,
    val username: String = "",
    val createdUtc: String = "",
    val installId: String = "",
) {
    fun matches(other: KavitaAccountIdentity): Boolean =
        (if (userId > 0 && other.userId > 0) userId == other.userId else username == other.username) &&
            (createdUtc.isBlank() || other.createdUtc.isBlank() || createdUtc == other.createdUtc) &&
            (installId.isBlank() || other.installId.isBlank() || installId == other.installId)

    fun sameServerVerified(other: KavitaAccountIdentity): Boolean = matches(other) &&
        (
            (installId.isNotBlank() && other.installId.isNotBlank()) ||
                (createdUtc.isNotBlank() && other.createdUtc.isNotBlank())
            )

    fun retainingKnownFields(previous: KavitaAccountIdentity?) = copy(
        userId = userId.takeIf { it > 0 } ?: previous?.userId ?: 0,
        createdUtc = createdUtc.ifBlank { previous?.createdUtc.orEmpty() },
        installId = installId.ifBlank { previous?.installId.orEmpty() },
    )
}

@Serializable
internal data class KavitaServerInfo(val installId: String = "")

@Serializable
data class KavitaLibrary(val id: Long, val name: String = "", val type: Int = 0)

@Serializable
data class KavitaSeries(
    val id: Long,
    val name: String = "",
    val localizedName: String = "",
    val libraryId: Long = 0,
    val format: Int = 2,
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val coverImage: String = "",
)

@Serializable
data class KavitaPerson(val id: Long = 0, val name: String = "")

@Serializable
data class KavitaTag(val id: Long = 0, val title: String = "")

@Serializable
data class KavitaMetadata(
    val summary: String = "",
    val writers: List<KavitaPerson> = emptyList(),
    val coverArtists: List<KavitaPerson> = emptyList(),
    val genres: List<KavitaTag> = emptyList(),
    val tags: List<KavitaTag> = emptyList(),
    val publicationStatus: Int = 0,
)

@Serializable
data class KavitaFile(
    val id: Long = 0,
    val filePath: String = "",
    val pages: Int = 0,
    val bytes: Long = 0,
    val format: Int = 2,
    val extension: String = "",
    val created: String = "",
    val koreaderHash: String = "",
)

@Serializable
data class KavitaChapter(
    val id: Long,
    val volumeId: Long = 0,
    val title: String = "",
    val titleName: String = "",
    val range: String = "",
    val number: String = "",
    val sortOrder: Double = 0.0,
    val minNumber: Double = 0.0,
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val isSpecial: Boolean = false,
    val files: List<KavitaFile> = emptyList(),
    val format: Int = 2,
    val createdUtc: String = "",
    val releaseDate: String = "",
    val lastModifiedUtc: String = "",
)

@Serializable
data class KavitaVolume(
    val id: Long,
    val minNumber: Double = 0.0,
    val name: String = "",
    val chapters: List<KavitaChapter> = emptyList(),
)

@Serializable
data class KavitaChapterRef(
    val libraryId: Long,
    val seriesId: Long,
    val volumeId: Long,
    val chapterId: Long,
    val format: Int,
) {
    val extension get() = when (format) {
        3 -> "epub"
        4 -> "pdf"
        else -> "pages"
    }
}

@Serializable
data class KavitaProgress(
    val libraryId: Long = 0,
    val seriesId: Long = 0,
    val volumeId: Long = 0,
    val chapterId: Long = 0,
    val pageNum: Int = 0,
    val bookScrollId: String? = null,
    val lastModifiedUtc: String = "",
)

@Serializable
data class KavitaReadingState(
    val ref: KavitaChapterRef,
    val progress: KavitaProgress,
    val totalPages: Int,
    val baseline: KavitaProgress? = null,
    val explicit: Boolean = false,
    val conflict: Boolean = false,
)

@Serializable
data class KavitaPagination(val currentPage: Int = 1, val totalPages: Int = 1, val totalCount: Int = 0)

@Serializable
data class KavitaSeriesPage(val items: List<KavitaSeries>, val hasNext: Boolean)

@Serializable
data class KavitaFilterStatement(val field: Int, val comparison: Int, val value: String)

@Serializable
data class KavitaSort(val sortField: Int = 1, val isAscending: Boolean = true)

@Serializable
data class KavitaFilter(
    val statements: List<KavitaFilterStatement> = emptyList(),
    val combination: Int = 1,
    val sortOptions: KavitaSort = KavitaSort(),
    val limitTo: Int = 0,
)

fun kavitaShelfFilter(media: List<Long>, query: String, order: String) = KavitaFilter(
    statements = buildList {
        if (media.isNotEmpty()) add(KavitaFilterStatement(19, 5, media.joinToString(",")))
        if (query.isNotBlank()) add(KavitaFilterStatement(1, 7, query))
    },
    sortOptions = KavitaSort(order.substringBefore(' ').toIntOrNull() ?: 1, !order.endsWith("desc")),
)

/** The server's flat statement list cannot express AND around an OR group. */
internal fun combineKavitaFilters(
    base: KavitaFilter,
    saved: KavitaFilter?,
    unrestricted: Boolean,
): KavitaFilter {
    if (saved == null) return base
    if (saved.combination == 0 && saved.statements.size > 1) {
        if (!unrestricted) throw KavitaException(KavitaException.Reason.FILTER)
        return saved.copy(sortOptions = base.sortOptions)
    }
    return saved.copy(statements = base.statements + saved.statements, combination = 1, sortOptions = base.sortOptions)
}

@Serializable
data class KavitaBookInfo(val pages: Int = 0, val bookTitle: String = "")

@Serializable
data class KavitaBookPart(
    val title: String = "",
    val part: String = "",
    val page: Int = 0,
    val children: List<KavitaBookPart> = emptyList(),
)

@Serializable
data class KavitaList(
    val id: Long,
    val title: String = "",
    val name: String = "",
    val summary: String = "",
    val ownerUserName: String = "",
)

@Serializable
data class KavitaListItem(
    val id: Long = 0,
    val order: Int = 0,
    val chapterId: Long = 0,
    val seriesId: Long = 0,
    val volumeId: Long = 0,
    val libraryId: Long = 0,
    val seriesFormat: Int = 2,
    val title: String = "",
    val seriesName: String = "",
)

@Serializable
data class KavitaAnnotation(
    val id: Long = 0,
    val chapterId: Long,
    val seriesId: Long,
    val volumeId: Long,
    val libraryId: Long,
    val ownerUserId: Long,
    val xPath: String,
    val endingXPath: String? = null,
    val selectedText: String = "",
    val comment: String = "",
    val commentHtml: String = "",
    val commentPlainText: String = "",
    val context: String = "",
    val highlightCount: Int = 1,
    val pageNumber: Int = 0,
    val selectedSlotIndex: Int = 0,
    val containsSpoiler: Boolean = false,
    val lastModifiedUtc: String = "0001-01-01T00:00:00Z",
    val createdUtc: String = "0001-01-01T00:00:00Z",
    val likes: List<Long> = emptyList(),
    val ownerUsername: String = "",
    val seriesName: String = "",
    val chapterTitle: String = "",
)

@Serializable
data class KavitaAnnotationPage(val items: List<KavitaAnnotation>, val hasNext: Boolean)

@Serializable
data class KavitaMutation(
    val method: String,
    val path: String,
    val body: JsonElement = JsonNull,
    val remoteId: Long? = null,
    val uncertain: Boolean = false,
)

fun kavitaTimestamp(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }
    .getOrElse { runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0) }

internal fun kavitaLibraryMatchesScope(type: Int, scope: LibraryContentScope): Boolean = when (scope) {
    LibraryContentScope.ALL -> true
    LibraryContentScope.BOOK -> type == 2 || type == 4
    LibraryContentScope.COMIC -> type in setOf(0, 1, 3, 5)
}
