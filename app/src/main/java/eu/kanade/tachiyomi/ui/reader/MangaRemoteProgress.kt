package eu.kanade.tachiyomi.ui.reader

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

@Immutable
data class MangaRemoteProgressConflict(
    val chapterId: Long,
    val localPageIndex: Int,
    val localTotalPages: Int,
    val remotePageIndex: Int,
    val remoteTotalPages: Int,
    val remoteVersion: String,
    val migratesLegacyEpubProgress: Boolean = false,
    val remoteReadAt: Long? = null,
    val remoteCompleted: Boolean? = null,
    val requiresPageMappingConfirmation: Boolean = false,
    val canUseRemotePosition: Boolean = true,
) {
    val localPercent: Int
        get() = pageProgressPercent(localPageIndex, localTotalPages)

    val remotePercent: Int
        get() = pageProgressPercent(remotePageIndex, remoteTotalPages)
}

internal fun pageProgressPercent(pageIndex: Int, totalPages: Int): Int {
    if (totalPages <= 0) return 0
    return (((pageIndex + 1).toDouble() / totalPages) * 100)
        .roundToInt()
        .coerceIn(0, 100)
}

internal fun hasPublicationChanged(oldVersion: String?, newVersion: String?): Boolean {
    return oldVersion != null && newVersion != null && oldVersion != newVersion
}
