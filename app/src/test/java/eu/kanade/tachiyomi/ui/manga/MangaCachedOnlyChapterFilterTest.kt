package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class MangaCachedOnlyChapterFilterTest {

    @Test
    fun `cached only keeps downloads and complete epub caches`() {
        val state = successState(
            manga = Manga.create(),
            cachedOnly = true,
        )

        assertEquals(setOf(1L, 2L), state.processedChapters.map { it.id }.toSet())
    }

    @Test
    fun `download filter does not treat complete epub cache as manual download`() {
        val state = successState(
            manga = Manga.create().copy(chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED),
            cachedOnly = false,
        )

        assertEquals(setOf(1L), state.processedChapters.map { it.id }.toSet())
    }

    @Test
    fun `not downloaded filter retains cached epub chapter`() {
        val state = successState(
            manga = Manga.create().copy(chapterFlags = Manga.CHAPTER_SHOW_NOT_DOWNLOADED),
            cachedOnly = false,
        )

        assertEquals(setOf(2L, 3L), state.processedChapters.map { it.id }.toSet())
    }

    private fun successState(
        manga: Manga,
        cachedOnly: Boolean,
    ) = MangaScreenModel.State.Success(
        manga = manga,
        source = TestSource,
        isFromSource = false,
        chapters = listOf(
            chapterItem(id = 1L, downloadState = Download.State.DOWNLOADED),
            chapterItem(id = 2L, isCompleteEpubCached = true),
            chapterItem(id = 3L),
        ),
        availableScanlators = emptySet(),
        excludedScanlators = emptySet(),
        cachedOnly = cachedOnly,
    )

    private fun chapterItem(
        id: Long,
        downloadState: Download.State = Download.State.NOT_DOWNLOADED,
        isCompleteEpubCached: Boolean = false,
    ) = ChapterList.Item(
        chapter = Chapter.create().copy(
            id = id,
            chapterNumber = id.toDouble(),
            sourceOrder = id,
            memo = JsonObject(emptyMap()),
        ),
        downloadState = downloadState,
        downloadProgress = 0,
        isCompleteEpubCached = isCompleteEpubCached,
    )

    private data object TestSource : Source {
        override val id = 1L
        override val name = "Test"
    }
}
