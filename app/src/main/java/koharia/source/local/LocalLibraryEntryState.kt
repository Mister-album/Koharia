package koharia.source.local

import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter

internal data class LocalLibraryEntryState(
    val unread: Boolean,
    val started: Boolean,
    val bookmarked: Boolean,
    val available: Boolean,
    val scanlators: Set<String>,
    val progress: MangaReadProgress?,
)

internal fun localLibraryEntryIsUnread(
    total: Int,
    chapters: List<Chapter>,
    individualFile: Boolean,
    epubProgression: Double?,
): Boolean = when {
    total <= 0 -> false
    individualFile -> chapters.none(Chapter::read) && (epubProgression ?: 0.0) < 1.0
    else -> chapters.count(Chapter::read) < total
}

internal fun LocalLibraryFilters.matches(manga: Manga, entry: LocalLibraryEntryState?): Boolean =
    applyFilter(downloaded) { entry?.available == true } &&
        applyFilter(unread) { entry?.unread == true } &&
        applyFilter(started) { entry?.started == true } &&
        applyFilter(bookmarked) { entry?.bookmarked == true } &&
        applyFilter(completed) { manga.status == SManga.COMPLETED.toLong() } &&
        (entry?.scanlators.orEmpty().let { it.isEmpty() || it.any { scanlator -> scanlator !in excludedScanlators } })

internal fun aggregateLocalFolderEntryState(
    total: Int,
    descendants: List<LocalLibraryEntryState>,
    progress: MangaReadProgress?,
): LocalLibraryEntryState = LocalLibraryEntryState(
    unread = total > 0 && (descendants.size < total || descendants.any { it.unread }),
    started = descendants.any { it.started },
    bookmarked = descendants.any { it.bookmarked },
    available = descendants.any { it.available },
    scanlators = descendants.flatMapTo(mutableSetOf()) { it.scanlators },
    progress = progress,
)
