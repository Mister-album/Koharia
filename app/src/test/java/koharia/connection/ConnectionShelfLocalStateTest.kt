package koharia.connection

import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import koharia.source.kavita.KavitaSource
import koharia.source.komga.KomgaSource
import koharia.source.smanga.SmangaSource
import koharia.source.suwayomi.SuwayomiSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class ConnectionShelfLocalStateTest {
    @Test
    fun `production paged adapters preserve local state remote metadata and each memo contract`() {
        val sources = listOf(
            source<KomgaSource>(),
            source<KavitaSource>(),
            source<SmangaSource>(),
            source<SuwayomiSource>(),
        )
        val remote = Manga.create().copy(
            id = -1,
            source = 42,
            url = "book",
            title = "Remote title",
            author = "Remote author",
            memo = JsonObject(mapOf("remote" to JsonPrimitive(true))),
        )
        val local = remote.copy(
            id = 7,
            title = "Old title",
            favorite = true,
            notes = "Local notes",
            chapterFlags = 123,
            viewerFlags = 456,
            coverLastModified = 99,
            memo = JsonObject(mapOf("local" to JsonPrimitive(true))),
        )
        fun state(manga: Manga) = ConnectionShelfEntryState(0, 1, 0, manga)
        for (source in sources) {
            val merged = resolveShelfManga(source, remote, state(local))
            assertEquals(local.id, merged.id)
            assertEquals(local.favorite, merged.favorite)
            assertEquals(local.notes, merged.notes)
            assertEquals(local.chapterFlags, merged.chapterFlags)
            assertEquals(local.viewerFlags, merged.viewerFlags)
            assertEquals(local.coverLastModified, merged.coverLastModified)
            assertEquals(remote.title, merged.title)
            assertEquals(remote.author, merged.author)
            val memo = when (source) {
                is KomgaSource -> remote.memo
                is SuwayomiSource -> local.memo
                else -> JsonObject(local.memo + remote.memo)
            }
            assertEquals(memo, merged.memo)
            assertEquals(remote, resolveShelfManga(source, remote, null))
            assertEquals(remote, resolveShelfManga(source, remote, state(local.copy(source = 43))))
            assertEquals(remote, resolveShelfManga(source, remote, state(local.copy(url = "other"))))
            val other = remote.copy(source = 43)
            assertSame(other, resolveShelfManga(source, other, null))
        }
    }

    private inline fun <reified T : Source> source(): T = mockk<T>().also { source ->
        every { source.id } returns 42L
        val adapter = source as ConnectionShelfLocalStateAdapter
        every { adapter.mergeShelfLocalState(any(), any()) } answers { callOriginal() }
    }
}
