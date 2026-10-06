package koharia.suwayomi

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.providers.BackupSuwayomiState
import eu.kanade.tachiyomi.data.backup.providers.SuwayomiStateBackupAdapter
import io.mockk.coEvery
import io.mockk.mockk
import koharia.domain.suwayomi.SuwayomiOperation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class SuwayomiBackupTest {
    private val json = Json { encodeDefaults = true }
    private val repository = MemorySuwayomiRepository()
    private val identity = SuwayomiIdentity(4, "a".repeat(64))
    private val manga = Manga.create().copy(id = 20, source = 4, url = identity.mangaUrl(3), title = "Fixture")
    private val chapter = Chapter.create().copy(id = 30, mangaId = 20, url = identity.chapterUrl(3, 11))
    private val mangas = mockk<MangaRepository> {
        coEvery { getMangaById(20) } returns manga
    }
    private val chapters = mockk<ChapterRepository> {
        coEvery { getChapterByMangaId(20) } returns listOf(chapter)
    }
    private val adapter = SuwayomiStateBackupAdapter(repository, mangas, chapters)

    @Test
    fun `restored pending state requires negotiation and rejects mismatched identity`() = runTest {
        val state = SuwayomiReadState(3, 11, 2, 10, false, 100)
        val entries = listOf(
            BackupSuwayomiState(chapter.url, json.encodeToString(state), 1, true),
            BackupSuwayomiState(identity.copy(connectionId = 9).chapterUrl(3, 11), json.encodeToString(state), 2, true),
        )
        adapter.restore(20, mapOf(chapter.url to 30L), entries)
        val operations = repository.operations(4, identity.account)
        assertEquals(1, operations.size)
        assertTrue(operations.single().pending)
        assertTrue(json.decodeFromString<SuwayomiReadState>(operations.single().payload).conflict)
        assertEquals(1, adapter.capture(20, mapOf(30L to chapter.url)).size)
    }

    @Test
    fun `restore preserves newer pending work and corrupt payload cannot replace it`() = runTest {
        val current = SuwayomiReadState(3, 11, 7, 10, false, 500)
        repository.putOperation(4, identity.account, SuwayomiOperation("11", json.encodeToString(current), 5, true))
        val backup = BackupSuwayomiState(
            chapter.url,
            json.encodeToString(current.copy(page = 2, readAt = 100)),
            1,
            true,
        )
        adapter.restore(20, mapOf(chapter.url to 30L), listOf(backup))
        val operation = repository.operations(4, identity.account).single()
        assertEquals(7, json.decodeFromString<SuwayomiReadState>(operation.payload).page)
        adapter.restore(20, mapOf(chapter.url to 30L), listOf(backup.copy(payload = "invalid")))
        assertEquals(operation, repository.operations(4, identity.account).single())
    }

    @Test
    fun `backup protobuf retains provider state and old backups default to empty`() {
        val backup = BackupManga(source = 4, url = manga.url)
        assertTrue(
            ProtoBuf.decodeFromByteArray(
                BackupManga.serializer(),
                ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup),
            ).suwayomiState.isEmpty(),
        )
        backup.suwayomiState = listOf(BackupSuwayomiState(chapter.url, "{}", 1, true))
        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup),
        )
        assertEquals(backup.suwayomiState, decoded.suwayomiState)
    }
}
