package koharia.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.MangaReadProgressDisplay
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class MangaDownloadStatus { NONE, PARTIAL, COMPLETE, UNKNOWN_TOTAL }

data class MangaDownloadState(val count: Long, val total: Long?) {
    val status get() = when {
        count <= 0 -> MangaDownloadStatus.NONE
        total == null || total <= 0 -> MangaDownloadStatus.UNKNOWN_TOTAL
        count >= total -> MangaDownloadStatus.COMPLETE
        else -> MangaDownloadStatus.PARTIAL
    }
}

data class ConnectionShelfEntryState(
    val downloaded: Long,
    val total: Long?,
    val read: Long,
) {
    val progress get() = total?.takeIf { it > 0 }?.let { MangaReadProgress(read, it) }
    fun downloads(expectedTotal: Long? = total): MangaDownloadState {
        // Retained offline chapters may outlive the server catalogue. Counts alone cannot prove completeness.
        val confirmedTotal = expectedTotal?.takeUnless {
            downloaded >= it && total != it
        }
        return MangaDownloadState(downloaded, confirmedTotal)
    }
}

fun resolveShelfReadProgress(
    local: ConnectionShelfEntryState?,
    remote: MangaReadProgress?,
    expectedTotal: Long?,
): MangaReadProgress? {
    if (remote != null && remote.display != MangaReadProgressDisplay.CHAPTERS) return remote
    val localProgress = local?.progress
    val total = expectedTotal ?: remote?.totalChapterCount
    val remoteProgress = if (expectedTotal != null) {
        remote?.takeIf { expectedTotal > 0 }?.copy(
            readCount = remote.readCount.coerceIn(0, expectedTotal),
            totalChapterCount = expectedTotal,
        )
    } else {
        remote
    }
    return if (total == null || total == localProgress?.totalChapterCount) {
        localProgress ?: remoteProgress
    } else {
        remoteProgress
    }
}

class ConnectionShelfEntryObserver(
    private val sourceId: Long,
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
    private val downloads: DownloadManager,
) {
    fun observe(visible: Flow<Set<String>>): Flow<Map<String, ConnectionShelfEntryState>> =
        combine(visible, mangas.getMangaBySourceIdAsFlow(sourceId)) { urls, local ->
            local.filter { it.url in urls }
        }.flatMapLatest { local ->
            if (local.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val observations: List<Flow<Pair<String, ConnectionShelfEntryState>>> = local.map { manga ->
                    combine(
                        chapters.getChapterByMangaIdAsFlow(manga.id),
                        downloads.cacheChanges.onStart { emit(Unit) },
                    ) { units, _ ->
                        val count = if (units.isEmpty()) {
                            downloads.getDownloadCount(manga).toLong()
                        } else {
                            units.count { chapter ->
                                downloads.isChapterDownloaded(
                                    chapter.name,
                                    chapter.scanlator,
                                    chapter.url,
                                    manga.title,
                                    manga.source,
                                )
                            }.toLong()
                        }
                        manga.url to ConnectionShelfEntryState(
                            count,
                            units.size.toLong().takeIf { it > 0 },
                            units.count { it.read }.toLong(),
                        )
                    }
                }
                combine(observations) { it.toMap() }
            }
        }.flowOn(Dispatchers.IO)
}

/** Observes loaded saved entries. No provider lookup or network request is made for a card. */
@Composable
fun rememberConnectionShelfEntries(
    source: Source?,
    entries: LazyPagingItems<StateFlow<Manga>>,
): Map<String, ConnectionShelfEntryState> {
    val states = remember(source, entries) {
        // Revoked download permission must not hide existing files or stop observing reading progress.
        if (source !is ConnectionSource ||
            (source !is HttpSource && (source as? ConnectionFileTransferAdapter)?.supportsFileTransfers != true)
        ) {
            flowOf(emptyMap())
        } else {
            val visible = snapshotFlow { entries.itemSnapshotList.items.map { it.value.url }.toSet() }
                .distinctUntilChanged()
            ConnectionShelfEntryObserver(source.id, Injekt.get(), Injekt.get(), Injekt.get()).observe(visible)
        }
    }
    return key(states) {
        val current by states.collectAsState(emptyMap())
        current
    }
}
