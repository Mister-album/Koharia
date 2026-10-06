package koharia.suwayomi

import koharia.source.suwayomi.SuwayomiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Executes one library-entry migration on the server. A Suwayomi manga cannot change its source
 * (the server's `UpdateMangaPatchInput` only accepts `inLibrary`), so a migration copies the
 * reader's state onto the same series found in another source, then stops tracking the old entry.
 *
 * Every step that could lose state is a hard failure: when chapter state or metadata cannot be
 * carried the old entry stays in the library so nothing is silently dropped.
 */
class SuwayomiMigrationRunner(private val source: SuwayomiSource) {

    suspend fun migrate(
        from: SuwayomiManga,
        to: SuwayomiManga,
        options: SuwayomiMigrationOptions,
        onProgress: (SuwayomiMigrationPhase) -> Unit = {},
    ): SuwayomiMigrationOutcome {
        val session = source.session()
        val warnings = mutableListOf<String>()
        var hardFailure: Throwable? = null
        var migratedChapters = 0
        var migratedCategories = 0
        var migratedMeta = 0
        var queuedDownloads = 0
        var unmatchedState = 0

        try {
            onProgress(SuwayomiMigrationPhase.COPYING)
            session.checkActive()
            // Discovery is an optimisation, not a prerequisite: the source of an obsolete extension
            // can no longer be fetched, and its stored chapters are exactly what has to move.
            bestEffortDiscovery(from)
            bestEffortDiscovery(to)
            session.checkActive()

            // Locally recorded reading that has not reached the server yet counts as source state.
            val pendingState = session.reading.localChapterStates(from.id)
            val sourceChapters = mergePendingState(chaptersFor(from), pendingState)
            val targetChapters = chaptersFor(to)
            session.checkActive()
            logcat(LogPriority.INFO) {
                "Suwayomi migration: source chapters=${sourceChapters.size} target chapters=${targetChapters.size}"
            }

            // Join the target library first: if anything later fails the reader keeps the entry.
            if (!to.inLibrary) {
                logcat(LogPriority.INFO) { "Suwayomi migration: joining target ${to.id} to the library" }
                session.api.setLibraryMembership(to.id, true)
                session.checkActive()
                session.catalog.invalidateListings()
            }

            if (options.migrateCategories && from.inLibrary) {
                val categoryIds = from.categories.nodes.map { it.id }.toSet()
                if (categoryIds.isNotEmpty()) {
                    logcat(LogPriority.INFO) { "Suwayomi migration: categories -> ${categoryIds.size}" }
                    try {
                        session.api.updateMangaCategories(to.id, categoryIds, emptySet())
                        session.checkActive()
                        session.catalog.invalidateListings()
                        migratedCategories = categoryIds.size
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        warnings += error.message.orEmpty()
                        hardFailure = error
                    }
                }
            }

            if ((options.migrateTracking || options.migrateReaderSettings) && hardFailure == null) {
                // Rating and tags describe the story; the reader flags describe how it is read.
                val meta = session.api.mangaMeta(from.id)
                val carried = buildMap {
                    if (options.migrateTracking) putAll(metaOf(meta, TRACKING_META_KEYS))
                    if (options.migrateReaderSettings) putAll(metaOf(meta, READER_META_KEYS))
                }
                logcat(LogPriority.INFO) { "Suwayomi migration: meta -> ${carried.size}" }
                carried.forEach { (key, value) ->
                    session.api.setMangaMeta(to.id, key, value)
                    migratedMeta++
                    session.checkActive()
                }
            }

            if (options.migrateChapters && hardFailure == null) {
                val plan = planSuwayomiChapterMigration(source = sourceChapters, target = targetChapters)
                logcat(LogPriority.INFO) {
                    "Suwayomi migration: chapter patches=${plan.patches.size} unmatched=${plan.unmatchedStateCount}"
                }
                plan.patches.forEach { patch ->
                    migrationWrite("chapter ${patch.targetChapterId}") {
                        session.api.updateChapters(
                            ids = listOf(patch.targetChapterId),
                            read = patch.read,
                            lastPageRead = patch.lastPageRead,
                        )
                    }
                    migratedChapters++
                    session.checkActive()
                }
                if (plan.unmatchedStateCount > 0) {
                    // Read progress on chapters the target lacks cannot be carried across, so it is
                    // reported rather than silently dropped — but it does not fail the migration.
                    unmatchedState = plan.unmatchedStateCount
                    warnings += "$unmatchedState chapter(s) with read state had no match on the target"
                }
            }

            if (options.migrateDownloads && hardFailure == null) {
                val downloads = planSuwayomiDownloadMigration(sourceChapters, targetChapters)
                if (downloads.isNotEmpty()) {
                    migrationWrite("downloads") { session.api.enqueueDownloads(downloads) }
                    queuedDownloads = downloads.size
                    session.checkActive()
                }
            }

            var removed = false
            if (
                canRemoveMigratedSource(
                    requested = options.removeSource,
                    sourceInLibrary = from.inLibrary,
                    hardFailure = hardFailure,
                )
            ) {
                migrationWrite("remove source") { session.api.setLibraryMembership(from.id, false) }
                session.checkActive()
                val stillTracked = runCatching { session.api.manga(from.id).inLibrary }.getOrNull()
                removed = stillTracked != true
                // The persisted shelf snapshot still lists the entry, so drop it before the next read.
                if (removed) {
                    session.catalog.invalidateShelf()
                    session.catalog.invalidateListings()
                }
                logcat(LogPriority.INFO) {
                    "Suwayomi migration: removal of ${from.id} confirmed=$removed stillTracked=$stillTracked"
                }
            }

            onProgress(if (hardFailure == null) SuwayomiMigrationPhase.DONE else SuwayomiMigrationPhase.FAILED)
            return SuwayomiMigrationOutcome(
                targetMangaId = to.id,
                targetTitle = to.title,
                migratedChapters = migratedChapters,
                migratedCategories = migratedCategories,
                migratedMeta = migratedMeta,
                queuedDownloads = queuedDownloads,
                removedSource = removed,
                warnings = warnings,
                failure = hardFailure,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Suwayomi migration aborted" }
            onProgress(SuwayomiMigrationPhase.FAILED)
            return SuwayomiMigrationOutcome(
                targetMangaId = to.id,
                targetTitle = to.title,
                migratedChapters = migratedChapters,
                migratedCategories = migratedCategories,
                migratedMeta = migratedMeta,
                queuedDownloads = queuedDownloads,
                removedSource = false,
                warnings = warnings,
                failure = error,
            )
        }
    }

    /**
     * Reports a failure rather than an empty result, so a dead source is abandoned after its first
     * failed query instead of retrying the whole query ladder against it.
     */
    private class SearchUnavailable : Exception()

    /**
     * Resolves the target for one candidate by searching the configured sources in priority order
     * and picking the closest title. Returns a null target when nothing matches.
     */
    suspend fun findTarget(
        title: String,
        targetSourceIds: List<Long>,
        extraSearchQuery: String?,
        knownSources: Map<Long, SuwayomiSourceInfo>? = null,
    ): SuwayomiMigrationMatch {
        val session = source.session()
        var best: SuwayomiMigrationMatch? = null
        val sources = knownSources ?: session.catalog.sources().associateBy { it.id }
        for (sourceId in targetSourceIds) {
            val info = sources[sourceId] ?: continue
            for (query in suwayomiMigrationQueries(title, extraSearchQuery)) {
                session.checkActive()
                val results = try {
                    searchOnce(sourceId, query, info.name, title)
                } catch (_: SearchUnavailable) {
                    break
                }
                if (results.isEmpty()) continue
                val candidate = bestSuwayomiMatch(title, results) ?: continue
                val confidence = suwayomiTitleSimilarity(title, candidate.title)
                if (best == null || confidence > best.confidence) {
                    best = SuwayomiMigrationMatch(candidate, info.name, confidence)
                }
                if (confidence >= 0.95) return best
            }
        }
        return best ?: SuwayomiMigrationMatch(null, null, 0.0)
    }

    /**
     * One search attempt, retried once: these sources drop TLS handshakes and time out often enough
     * that a single failure would leave a perfectly migratable entry unmatched. Throws
     * [SearchUnavailable] when the source stays unreachable, which ends its query ladder.
     */
    private suspend fun searchOnce(
        sourceId: Long,
        query: String,
        sourceName: String,
        title: String,
    ): List<SuwayomiManga> {
        repeat(SEARCH_ATTEMPTS) { attempt ->
            val result = runCatching {
                source.sourceListing(
                    sourceInfoId = sourceId,
                    page = 1,
                    query = query,
                    type = SuwayomiSourceMangaType.SEARCH,
                    filters = emptyList(),
                ).mangas
            }
            result.getOrNull()?.let { mangas ->
                logcat(LogPriority.INFO) {
                    "Suwayomi migration search source=$sourceName query='$query' -> ${mangas.size} " +
                        "best=${mangas.maxOfOrNull { suwayomiTitleSimilarity(title, it.title) }}"
                }
                return mangas
            }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            // A dead or Cloudflare-blocked target must not stop the remaining fallbacks.
            logcat(LogPriority.WARN, failure) {
                "Suwayomi migration search failed source=$sourceId query='$query' attempt=${attempt + 1}"
            }
            if (attempt < SEARCH_ATTEMPTS - 1) delay(SEARCH_RETRY_DELAY_MILLIS)
        }
        throw SearchUnavailable()
    }

    /** Runs one write, logging the failing step before the failure aborts the migration. */
    private suspend fun <T> migrationWrite(step: String, block: suspend () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        logcat(LogPriority.WARN, error) { "Suwayomi migration: $step write failed" }
        throw error
    }

    /** Refresh the server's stored chapters when its source still answers; a failure is not fatal. */
    private suspend fun bestEffortDiscovery(manga: SuwayomiManga) {
        runCatching { source.fetchSourceDetails(manga.id) }
            .onFailure {
                logcat(LogPriority.INFO) { "Suwayomi migration: discovery skipped for ${manga.id}" }
            }
    }

    /**
     * Stored chapters, refreshed when the server can still reach the source. A refresh failure
     * falls back to the cached list because that cache is the state a migration has to carry.
     */
    private suspend fun chaptersFor(manga: SuwayomiManga): List<SuwayomiChapter> = runCatching {
        source.session().catalog.chapters(manga.id, refresh = true)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        logcat(LogPriority.WARN, error) {
            "Suwayomi migration: using cached chapters for ${manga.id}"
        }
        source.session().catalog.chapters(manga.id, refresh = false)
    }

    private fun mergePendingState(
        chapters: List<SuwayomiChapter>,
        pending: Map<Int, SuwayomiReadState>,
    ): List<SuwayomiChapter> {
        if (pending.isEmpty()) return chapters
        return chapters.map { chapter ->
            val state = pending[chapter.id] ?: return@map chapter
            chapter.copy(
                isRead = state.read,
                lastPageRead = maxOf(chapter.lastPageRead, state.page),
            )
        }
    }

    /** The server metadata selected by one migration option, keyed for the target entry. */
    private fun metaOf(meta: List<SuwayomiMangaMeta>, keys: Set<String>): Map<String, String> = meta
        .filter { it.key in keys }
        .associate { it.key to it.value }

    companion object {
        private const val SEARCH_ATTEMPTS = 2
        private const val SEARCH_RETRY_DELAY_MILLIS = 800L
        const val RATING_META_KEY = "flutter_rating"
        const val TAGS_META_KEY = "flutter_tags"

        /** Rating and tags describe the story, so the tracking option owns them. */
        val TRACKING_META_KEYS = setOf(
            RATING_META_KEY,
            TAGS_META_KEY,
        )

        val READER_META_KEYS = setOf(
            "flutter_readerMode",
            "flutter_readerNavigationLayout",
            "flutter_readerPadding",
            "flutter_readerOrientation",
            "flutter_readerTapInvert",
            "flutter_chapterListMode",
        )
    }
}
