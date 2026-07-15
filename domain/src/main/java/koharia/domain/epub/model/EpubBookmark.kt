package koharia.domain.epub.model

import java.util.Date

data class EpubBookmark(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val locatorJson: String,
    val sectionTitle: String?,
    val progression: Double?,
    val note: String?,
    val createdAt: Date,
)
