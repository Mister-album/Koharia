package koharia.domain.epub.model

import java.util.Date

data class EpubProgress(
    val chapterId: Long,
    val mangaId: Long,
    val bookUrl: String?,
    val locatorJson: String,
    val progression: Double?,
    val positionIndex: Long?,
    val updatedAt: Date,
    val lastSyncedAt: Date?,
)
