package tachiyomi.domain.chapter.model

/** A source diff computed from one local snapshot; reading fields are only set on new rows. */
data class ChapterSourceUpdate(
    val mangaId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val expected: List<Chapter>,
    val added: List<Chapter>,
    val updated: List<ChapterUpdate>,
    val removed: List<Long>,
)
