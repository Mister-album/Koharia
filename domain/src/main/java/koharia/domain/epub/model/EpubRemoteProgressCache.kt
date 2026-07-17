package koharia.domain.epub.model

import java.util.Date

data class EpubRemoteProgressCache(
    val chapterId: Long,
    val mangaId: Long,
    val bookUrl: String,
    val locatorJson: String?,
    val progression: Double?,
    val positionIndex: Long?,
    val modifiedAt: Date?,
    val checkedAt: Date,
    val serverDate: Date?,
)
