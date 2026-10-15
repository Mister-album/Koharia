package koharia.komga.progress

import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Server evidence, never a comparison between a device clock and a server clock. */
@Serializable
internal data class KomgaProgressBaseline(
    val version: Int,
    val bookId: String,
    val fileHash: String?,
    val fileLastModified: String?,
    val size: Long,
    val total: Int,
    val epub: Boolean,
    val divina: Boolean,
    val progressPresent: Boolean,
    val page: Int?,
    val completed: Boolean,
    val readDate: String?,
    val progressModified: String?,
) {
    val valid: Boolean get() = version == 1 && bookId.isNotBlank() && size >= 0 && total >= 0 &&
        (!fileHash.isNullOrBlank() || !fileLastModified.isNullOrBlank()) &&
        (page == null || page in 1..total) &&
        (!progressPresent || !progressModified.isNullOrBlank()) &&
        (progressPresent || (page == null && !completed && readDate == null && progressModified == null))

    fun samePublication(other: KomgaProgressBaseline): Boolean = valid && other.valid &&
        bookId == other.bookId && fileHash == other.fileHash && fileLastModified == other.fileLastModified &&
        size == other.size && total == other.total && epub == other.epub && divina == other.divina

    fun matchesPageTarget(before: KomgaProgressBaseline, pageIndex: Long, pageTotal: Long): Boolean =
        samePublication(before) && (!epub || divina) && total.toLong() == pageTotal &&
            pageIndex in 0 until pageTotal && progressPresent && page?.toLong() == pageIndex + 1 &&
            completed == (before.completed || pageIndex + 1 == pageTotal)

    fun matchesReadStatusTarget(before: KomgaProgressBaseline, read: Boolean): Boolean =
        samePublication(before) && if (read) progressPresent && completed else !progressPresent

    fun encode(): String {
        require(valid)
        return codec.encodeToString(this)
    }

    companion object {
        private val codec = Json

        fun decode(value: String?): KomgaProgressBaseline? = value?.let {
            runCatching { codec.decodeFromString<KomgaProgressBaseline>(it) }.getOrNull()?.takeIf { it.valid }
        }

        fun from(remote: KomgaApi.BookProgressSnapshot) = KomgaProgressBaseline(
            version = 1,
            bookId = KomgaProgressOrdering.bookId(remote.url),
            fileHash = remote.fileHash,
            fileLastModified = remote.fileLastModified,
            size = remote.sizeBytes,
            total = remote.totalPages,
            epub = remote.isEpub,
            divina = remote.isDivinaCompatibleEpub,
            progressPresent = remote.readProgress != null,
            page = remote.readProgress?.page,
            completed = remote.readProgress?.completed ?: false,
            readDate = remote.readProgress?.readDate,
            progressModified = remote.readProgress?.lastModified,
        )
    }
}
