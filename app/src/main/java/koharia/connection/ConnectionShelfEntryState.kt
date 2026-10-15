package koharia.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.MangaReadProgressDisplay
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
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
    val manga: Manga? = null,
    val downloadedChapterUrls: Set<String> = emptySet(),
    val catalogueTotal: Long? = null,
) {
    val progress get() = total?.takeIf { it > 0 }?.let { MangaReadProgress(read, it) }
    fun downloads(expectedTotal: Long? = total): MangaDownloadState {
        // Retained offline chapters may outlive the server catalogue. Counts alone cannot prove completeness.
        val confirmedTotal = (catalogueTotal ?: expectedTotal)?.takeUnless {
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
    private val unitCounts: ConnectionShelfUnitCountAdapter? = null,
) {
    private sealed interface Update {
        data class Rows(val mangas: List<Manga>) : Update
        data class Totals(val counts: Map<String, Long>) : Update
        data class Units(val ids: Set<Long>, val chapters: List<tachiyomi.domain.chapter.model.Chapter>) : Update
        data class Downloads(val change: eu.kanade.tachiyomi.data.download.DownloadCacheChange) : Update
    }

    fun observe(visible: Flow<Set<String>>): Flow<Map<String, ConnectionShelfEntryState>> = visible
        .distinctUntilChanged()
        .flatMapLatest { urls ->
            if (urls.isEmpty()) flowOf(emptyMap()) else observeWindow(urls)
        }
        .buffer(0)
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private fun observeWindow(urls: Set<String>): Flow<Map<String, ConnectionShelfEntryState>> = flow {
        coroutineScope {
            val rows = mangas.getMangaByUrlsAndSourceIdAsFlow(urls, sourceId)
                .map { it.distinctBy(Manga::url) }.distinctUntilChanged()
                .shareIn(this, SharingStarted.Lazily, replay = 1)
            var local: List<Manga>? = null
            var latestUnits: Update.Units? = null
            var catalogueTotals = emptyMap<String, Long>()
            var previousUnits = emptyMap<Long, List<tachiyomi.domain.chapter.model.Chapter>>()
            var previousRows = emptyMap<Long, Manga>()
            val states = mutableMapOf<String, ConnectionShelfEntryState>()
            val dirtyDownloads = mutableSetOf<String>()
            // Merge preserves different-resource events. One chapter batch follows only identity changes.
            merge(
                rows.map { Update.Rows(it) },
                rows.map { it.mapTo(linkedSetOf(), Manga::id) }.distinctUntilChanged().flatMapLatest { ids ->
                    chapters.getChaptersByMangaIdsAsFlow(ids.toList()).map { Update.Units(ids, it) }
                },
                downloads.cacheUpdates.map { Update.Downloads(it) },
                unitCounts?.observeShelfUnitCounts(urls)?.map { Update.Totals(it) } ?: emptyFlow(),
            ).collect { update ->
                when (update) {
                    is Update.Rows -> local = update.mangas
                    is Update.Totals -> catalogueTotals = update.counts
                    is Update.Units -> latestUnits = update
                    is Update.Downloads -> local.orEmpty().forEach { manga ->
                        if (downloads.isAffectedByCacheUpdate(manga, update.change)) dirtyDownloads.add(manga.url)
                    }
                }
                val currentRows = local ?: return@collect
                val ids = currentRows.mapTo(hashSetOf(), Manga::id)
                if (ids.isNotEmpty() && latestUnits?.ids != ids) return@collect
                val units = latestUnits?.chapters.orEmpty().groupBy { it.mangaId }
                states.keys.retainAll(currentRows.mapTo(hashSetOf(), Manga::url))
                currentRows.forEach { manga ->
                    val current = units[manga.id].orEmpty()
                    val old = previousUnits[manga.id].orEmpty()
                    val previous = states[manga.url]
                    val oldManga = previousRows[manga.id]
                    val recount = previous == null || manga.url in dirtyDownloads ||
                        oldManga == null || oldManga.title != manga.title ||
                        oldManga.source != manga.source || oldManga.url != manga.url || when (update) {
                            is Update.Downloads -> false
                            is Update.Totals -> false
                            is Update.Rows -> false
                            is Update.Units -> current.size != old.size || current.indices.any {
                                current[it].url != old[it].url || current[it].name != old[it].name ||
                                    current[it].scanlator != old[it].scanlator
                            }
                        }
                    val downloadedUnits = if (recount) {
                        current.filter { chapter ->
                            downloads.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val count = if (!recount) {
                        checkNotNull(previous).downloaded
                    } else if (current.isEmpty()) {
                        downloads.getDownloadCount(manga).toLong()
                    } else {
                        downloadedUnits.size.toLong()
                    }
                    states[manga.url] = ConnectionShelfEntryState(
                        count,
                        current.size.toLong().takeIf { it > 0 },
                        current.count { it.read }.toLong(),
                        manga,
                        if (recount) {
                            downloadedUnits.mapTo(hashSetOf()) {
                                it.url
                            }
                        } else {
                            previous.downloadedChapterUrls
                        },
                        if (unitCounts == null) null else catalogueTotals[manga.url] ?: 0L,
                    )
                }
                previousUnits = units
                previousRows = currentRows.associateBy(Manga::id)
                dirtyDownloads.clear()
                emit(states.toMap())
            }
        }
    }
}

/** Non-paged organization rows share the shelf's bounded SQL and download observation. */
@Composable
fun rememberConnectionShelfEntries(
    source: Source?,
    entryUrls: List<String>,
    leadingItems: Int = 0,
    visibleIndices: () -> List<Int>,
): Map<String, ConnectionShelfEntryState> {
    val currentUrls by rememberUpdatedState(entryUrls)
    val currentLeadingItems by rememberUpdatedState(leadingItems)
    val currentIndices by rememberUpdatedState(visibleIndices)
    val states = remember(source) {
        if (source !is ConnectionSource ||
            (source !is HttpSource && (source as? ConnectionFileTransferAdapter)?.supportsFileTransfers != true)
        ) {
            flowOf(emptyMap())
        } else {
            val visible = snapshotFlow {
                shelfObservationWindow(currentIndices(), currentLeadingItems, currentUrls.size)
                    .map { currentUrls[it] }.toSet()
            }
            ConnectionShelfEntryObserver(
                source.id,
                Injekt.get(),
                Injekt.get(),
                Injekt.get(),
                source as? ConnectionShelfUnitCountAdapter,
            ).observe(visible)
        }
    }
    return key(states) {
        val current by states.collectAsState(emptyMap())
        current
    }
}

internal fun shelfObservationWindow(visibleIndices: List<Int>, leadingItems: Int, itemCount: Int): IntRange {
    val items = visibleIndices.map { it - leadingItems }.filter { it in 0 until itemCount }
    if (items.isEmpty()) return IntRange.EMPTY
    val first = items.min()
    val last = items.max()
    val screen = last - first + 1
    return (first - screen).coerceAtLeast(0)..(last.toLong() + screen).coerceAtMost(itemCount.toLong() - 1).toInt()
}

/** Observe the actual viewport and one screen on each side without requesting paging loads. */
@Composable
fun rememberConnectionShelfEntries(
    source: Source?,
    entries: LazyPagingItems<StateFlow<Manga>>,
    visibleIndices: () -> List<Int>,
    leadingItems: Int,
): Map<String, ConnectionShelfEntryState> {
    val currentIndices by rememberUpdatedState(visibleIndices)
    val currentLeadingItems by rememberUpdatedState(leadingItems)
    val states = remember(source, entries) {
        // Revoked download permission must not hide existing files or stop observing reading progress.
        if (source !is ConnectionSource ||
            (source !is HttpSource && (source as? ConnectionFileTransferAdapter)?.supportsFileTransfers != true)
        ) {
            flowOf(emptyMap())
        } else {
            val visible = snapshotFlow {
                shelfObservationWindow(currentIndices(), currentLeadingItems, entries.itemCount)
                    .mapNotNull { entries.peek(it)?.value?.url }.toSet()
            }
                .distinctUntilChanged()
            ConnectionShelfEntryObserver(
                source.id,
                Injekt.get(),
                Injekt.get(),
                Injekt.get(),
                source as? ConnectionShelfUnitCountAdapter,
            ).observe(visible)
        }
    }
    return key(states) {
        val current by states.collectAsState(emptyMap())
        current
    }
}
