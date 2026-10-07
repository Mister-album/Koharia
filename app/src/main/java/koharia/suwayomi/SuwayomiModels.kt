package koharia.suwayomi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

enum class SuwayomiAuthMode { NONE, BASIC_AUTH, SIMPLE_LOGIN, UI_LOGIN }

class SuwayomiException(val reason: Reason, val status: Int? = null) :
    IOException("Suwayomi: ${reason.name}"),
    koharia.connection.ConnectionAddressRouter.NonRoutingFailure,
    koharia.connection.ConnectionValidationError {
    override val validationStatus get() = status
    override val validationReason get() = when (reason) {
        Reason.AUTH -> if (status == 403) {
            koharia.connection.ConnectionAddressVerification.Reason.PERMISSION
        } else if (status == null || status == 401) {
            koharia.connection.ConnectionAddressVerification.Reason.AUTHENTICATION
        } else {
            koharia.connection.ConnectionAddressVerification.Reason.UNAVAILABLE
        }
        Reason.SERVER -> koharia.connection.ConnectionAddressVerification.Reason.UNAVAILABLE
        Reason.PROTOCOL -> koharia.connection.ConnectionAddressVerification.Reason.RESPONSE
        else -> null
    }
    enum class Reason { ADDRESS, AUTH, VERSION, PROTOCOL, NOT_FOUND, SOURCE, PAGES, SERVER, IMAGE }
}

/** GraphQL Long scalars are strings on the stable server; caches also accept numeric JSON. */
object SuwayomiLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("SuwayomiLong", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Long =
        if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.long else decoder.decodeString().toLong()
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
}

@Serializable
data class SuwayomiPageInfo(val hasNextPage: Boolean = false, val endCursor: String? = null)

/** `fetchSourceManga` accepts a fixed source listing mode; the server rejects LATEST when unsupported. */
enum class SuwayomiSourceMangaType { POPULAR, LATEST, SEARCH }

@Serializable
data class SuwayomiSourcePage(val mangas: List<SuwayomiManga>, val hasNextPage: Boolean)

@Serializable
data class SuwayomiNodes<T>(
    val nodes: List<T> = emptyList(),
    val pageInfo: SuwayomiPageInfo = SuwayomiPageInfo(),
    val totalCount: Int? = null,
)

@Serializable
data class SuwayomiCategory(val id: Int, val name: String, val order: Int = 0)

@Serializable
data class SuwayomiSourceInfo(
    @Serializable(with = SuwayomiLongSerializer::class) val id: Long,
    val name: String = "",
    val lang: String = "",
    val iconUrl: String = "",
    val contentWarning: String? = null,
    val supportsLatest: Boolean = false,
    /** True when the extension exposes a preference screen the server can read and write. */
    val isConfigurable: Boolean = false,
    val extension: SuwayomiExtensionState = SuwayomiExtensionState(),
    /** Server-side source metadata; `webUI_isPinned` is the pin WebUI and Tsumiru both write. */
    val meta: List<SuwayomiSourceMeta> = emptyList(),
) {
    val isPinned: Boolean get() = meta.any { it.key == PINNED_META_KEY && it.value == "true" }

    /** Obsolete or uninstalled on the server, so its library entries can no longer update. */
    val isObsolete: Boolean get() = extension.isObsolete || !extension.isInstalled

    companion object {
        const val PINNED_META_KEY = "webUI_isPinned"
    }
}

@Serializable
data class SuwayomiSourceMeta(val key: String, val value: String)

/** `SourceType.extension` state; an obsolete or removed extension means its library cannot update. */
@Serializable
data class SuwayomiExtensionState(val isInstalled: Boolean = true, val isObsolete: Boolean = false)

@Serializable
data class SuwayomiMangaMeta(val key: String, val value: String)

/**
 * Source filter tree as returned by `source(id){filters}`. Unknown members surface as
 * [SuwayomiUnknownFilter] so a newer server filter type cannot break browsing.
 */
sealed interface SuwayomiSourceFilter {
    val name: String
}

data class SuwayomiHeaderFilter(override val name: String) : SuwayomiSourceFilter

data class SuwayomiSeparatorFilter(override val name: String) : SuwayomiSourceFilter

data class SuwayomiSelectFilter(
    override val name: String,
    val values: List<String> = emptyList(),
    val default: Int = 0,
) : SuwayomiSourceFilter

data class SuwayomiTextFilter(
    override val name: String,
    val default: String = "",
) : SuwayomiSourceFilter

data class SuwayomiCheckBoxFilter(
    override val name: String,
    val default: Boolean = false,
) : SuwayomiSourceFilter

data class SuwayomiTriStateFilter(
    override val name: String,
    val default: String = "IGNORE",
) : SuwayomiSourceFilter

data class SuwayomiSortFilter(
    override val name: String,
    val values: List<String> = emptyList(),
    val default: SuwayomiSortSelection? = null,
) : SuwayomiSourceFilter

data class SuwayomiSortSelection(val index: Int = 0, val ascending: Boolean = true)

data class SuwayomiGroupFilter(
    override val name: String,
    val filters: List<SuwayomiSourceFilter> = emptyList(),
) : SuwayomiSourceFilter

data class SuwayomiUnknownFilter(override val name: String = "") : SuwayomiSourceFilter

/** `FilterChangeInput`; `position` is the filter's index inside its own list. */
data class SuwayomiFilterChange(
    val position: Int,
    val selectState: Int? = null,
    val textState: String? = null,
    val checkBoxState: Boolean? = null,
    val triState: String? = null,
    val sortState: SuwayomiSortSelection? = null,
    val groupChange: SuwayomiFilterChange? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("position", position)
        selectState?.let { put("selectState", it) }
        textState?.let { put("textState", it) }
        checkBoxState?.let { put("checkBoxState", it) }
        triState?.let { put("triState", it) }
        sortState?.let { selection ->
            put(
                "sortState",
                buildJsonObject {
                    put("index", selection.index)
                    put("ascending", selection.ascending)
                },
            )
        }
        groupChange?.let { put("groupChange", it.toJson()) }
    }
}

@Serializable
data class SuwayomiExtensionStore(val indexUrl: String = "")

@Serializable
data class SuwayomiExtension(
    val storeIndexUrl: String? = null,
    val apkName: String? = null,
    val iconUrl: String = "",
    val name: String = "",
    val pkgName: String = "",
    val apkUrl: String? = null,
    val jarUrl: String? = null,
    val extensionLib: String? = null,
    val versionName: String = "",
    @Serializable(with = SuwayomiLongSerializer::class) val versionCodeLong: Long = 0,
    val lang: String = "",
    val contentWarning: String? = null,
    val isInstalled: Boolean = false,
    val hasUpdate: Boolean = false,
    val isObsolete: Boolean = false,
)

/** `updateExtension` patch fields; reinstalling an installed extension uses install again. */
enum class SuwayomiExtensionAction(val mutationField: String) {
    INSTALL("install"),
    UPDATE("update"),
    UNINSTALL("uninstall"),
}

@Serializable
data class SuwayomiDownloadChapter(val id: Int, val mangaId: Int = 0, val name: String = "")

@Serializable
data class SuwayomiDownloadItem(
    val chapter: SuwayomiDownloadChapter? = null,
    val manga: SuwayomiManga? = null,
    val state: String = "QUEUED",
    val progress: Float = 0f,
    val tries: Int = 0,
    val position: Int = 0,
) {
    val chapterId: Int get() = chapter?.id ?: 0
    val mangaId: Int get() = chapter?.mangaId ?: manga?.id ?: 0
}

@Serializable
data class SuwayomiDownloadStatus(
    val state: String = "STOPPED",
    val queue: List<SuwayomiDownloadItem> = emptyList(),
)

@Serializable
data class SuwayomiManga(
    val id: Int,
    val title: String,
    @Serializable(with = SuwayomiLongSerializer::class) val sourceId: Long = 0,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val status: String = "UNKNOWN",
    val thumbnailUrl: String? = null,
    val inLibrary: Boolean = true,
    @Serializable(with = SuwayomiLongSerializer::class) val inLibraryAt: Long = 0,
    val categories: SuwayomiNodes<SuwayomiCategory> = SuwayomiNodes(),
    val chapters: SuwayomiNodes<SuwayomiChapter> = SuwayomiNodes(),
    val unreadCount: Int = 0,
    val downloadCount: Int = 0,
    val bookmarkCount: Int = 0,
    /** Present once the series has been read; the shelf filters use it as the "started" signal. */
    val lastReadChapter: SuwayomiChapter? = null,
    /** Per-series metadata the server stores; migration carries the reader/display keys. */
    val meta: List<SuwayomiMangaMeta> = emptyList(),
) {
    /** The filterable facts about this entry, so the shelf filters stay a pure function. */
    val libraryFacts: SuwayomiLibraryFacts
        get() = SuwayomiLibraryFacts(
            title = title,
            author = author,
            artist = artist,
            genres = genre,
            status = status,
            unreadCount = unreadCount,
            totalChapters = chapters.totalCount ?: 0,
            downloadedCount = downloadCount,
            bookmarkedCount = bookmarkCount,
            hasProgress = lastReadChapter != null || (chapters.totalCount?.let { unreadCount < it } == true),
        )
}

@Serializable
data class SuwayomiChapter(
    val id: Int,
    /**
     * Absent when the server returns a chapter nested under a manga with only a few fields selected,
     * such as the shelf's `lastReadChapter`; the owning manga is implied by that parent.
     */
    val mangaId: Int = 0,
    val name: String = "",
    val chapterNumber: Float = -1f,
    val sourceOrder: Int = 0,
    val scanlator: String? = null,
    @Serializable(with = SuwayomiLongSerializer::class) val uploadDate: Long = 0,
    val isRead: Boolean = false,
    val lastPageRead: Int = 0,
    @Serializable(with = SuwayomiLongSerializer::class) val lastReadAt: Long = 0,
    val pageCount: Int = 0,
    val isDownloaded: Boolean = false,
)

@Serializable
data class SuwayomiSyncConflict(val deviceName: String, val remotePage: Int)

@Serializable
data class SuwayomiPages(
    val pages: List<String>,
    val chapter: SuwayomiChapter,
    val syncConflict: SuwayomiSyncConflict? = null,
) {
    val version get() = pages.joinToString("\n").encodeUtf8().sha256().hex()
}

@Serializable
data class SuwayomiShelf(val mangas: List<SuwayomiManga>, val categories: List<SuwayomiCategory>)

interface SuwayomiService {
    suspend fun categories(): List<SuwayomiCategory>
    suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga>
    suspend fun manga(id: Int): SuwayomiManga
    suspend fun chapters(mangaId: Int): List<SuwayomiChapter>
    suspend fun chapter(id: Int): SuwayomiChapter
    suspend fun pages(chapterId: Int): SuwayomiPages
    suspend fun updateChapter(id: Int, pageIndex: Int?, read: Boolean?): SuwayomiChapter
    suspend fun extensions(refresh: Boolean = false): List<SuwayomiExtension> = unsupported()
    suspend fun sources(): List<SuwayomiSourceInfo> = unsupported()
    suspend fun source(id: Long): SuwayomiSourceInfo = unsupported()
    suspend fun sourceFilters(id: Long): List<SuwayomiSourceFilter> = unsupported()
    suspend fun sourcePreferences(id: Long): List<SuwayomiSourcePreference> = unsupported()
    suspend fun updateSourcePreference(
        sourceId: Long,
        position: Int,
        change: SuwayomiPreferenceChange,
    ): List<SuwayomiSourcePreference> = unsupported()
    suspend fun setSourceMeta(id: Long, key: String, value: String): SuwayomiSourceMeta = unsupported()
    suspend fun mangaMeta(id: Int): List<SuwayomiMangaMeta> = unsupported()
    suspend fun setMangaMeta(id: Int, key: String, value: String): SuwayomiMangaMeta = unsupported()
    suspend fun updateChapters(ids: List<Int>, read: Boolean?, lastPageRead: Int?): List<SuwayomiChapter> =
        unsupported()
    suspend fun sourceMangaPage(
        sourceId: Long,
        after: String?,
        query: String?,
    ): SuwayomiNodes<SuwayomiManga> = unsupported()
    suspend fun discoverSourceManga(
        sourceId: Long,
        page: Int,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange> = emptyList(),
    ): SuwayomiSourcePage = unsupported()
    suspend fun downloadStatus(): SuwayomiDownloadStatus = unsupported()
    suspend fun enqueueDownloads(ids: List<Int>): SuwayomiDownloadStatus = unsupported()
    suspend fun dequeueDownload(id: Int): SuwayomiDownloadStatus = unsupported()
    suspend fun startDownloader(): SuwayomiDownloadStatus = unsupported()
    suspend fun stopDownloader(): SuwayomiDownloadStatus = unsupported()
    suspend fun clearDownloader(): SuwayomiDownloadStatus = unsupported()
    suspend fun reorderDownload(chapterId: Int, position: Int): SuwayomiDownloadStatus = unsupported()
    suspend fun createCategory(name: String): SuwayomiCategory = unsupported()
    suspend fun updateCategory(id: Int, name: String): SuwayomiCategory = unsupported()
    suspend fun deleteCategory(id: Int): List<SuwayomiCategory> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Suwayomi operation is not supported")
}
