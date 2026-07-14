package koharia.domain.epub.model

import java.util.Date

data class EpubPaginationCache(
    val chapterId: Long,
    val publicationKey: String,
    val layoutKey: String,
    val layoutSnapshotJson: String,
    val resourcePageCountsJson: String,
    val currentLocatorJson: String?,
    val currentVisualPage: Long?,
    val totalVisualPages: Long?,
    val isComplete: Boolean,
    val measuredResourceCount: Long,
    val updatedAt: Date,
)
