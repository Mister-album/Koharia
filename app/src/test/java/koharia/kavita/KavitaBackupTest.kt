package koharia.kavita

import eu.kanade.tachiyomi.data.backup.providers.KavitaStateBackupAdapter
import io.mockk.coEvery
import io.mockk.mockk
import koharia.domain.kavita.KavitaAnnotationEntry
import koharia.domain.kavita.KavitaOperation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class KavitaBackupTest {
    private val repository = MemoryKavitaRepository()
    private val identity = KavitaIdentity(42, "account")
    private val ref = KavitaChapterRef(1, 2, 3, 4, 3)
    private val manga = Manga.create().copy(id = 7, source = 42, url = identity.series(2))
    private val chapter = Chapter.create().copy(id = 8, mangaId = 7, url = identity.chapter(ref))
    private val mangas = mockk<MangaRepository> { coEvery { getMangaById(7) } returns manga }
    private val chapters = mockk<ChapterRepository> { coEvery { getChapterByMangaId(7) } returns listOf(chapter) }
    private val adapter = KavitaStateBackupAdapter(repository, mangas, chapters)

    @Test fun bookmarkBackupsRequireConfirmationAndCannotOverwriteCurrentWork() = runTest {
        val state = KavitaBookmarkState(ref, KavitaBookmarkKind.TOC, 2, title = "Section", desired = false)
        repository.putOperation(42, "account", KavitaOperation(state.key, Json.encodeToString(state), 3, true))
        val backup = adapter.capture(7, mapOf(8L to chapter.url))
        assertEquals(state.key, backup.single().operationKey)
        repository.removeConnection(42)
        adapter.restore(7, mapOf(chapter.url to 8L), backup)
        val restored = repository.operations(42, "account").single()
        assertTrue(Json.decodeFromString<KavitaBookmarkState>(restored.payload).needsConfirmation)
        repository.putOperation(42, "account", restored.copy(payload = Json.encodeToString(state.copy(desired = true))))
        adapter.restore(7, mapOf(chapter.url to 8L), backup)
        assertTrue(
            Json.decodeFromString<KavitaBookmarkState>(repository.operations(42, "account").single().payload).desired,
        )
        assertTrue(adapter.capture(7, mapOf(8L to KavitaIdentity(42, "another").chapter(ref))).isEmpty())
    }

    @Test fun restoredAnnotationRemoteIdCollisionPreservesBothLocalEdits() = runTest {
        val annotation = KavitaAnnotation(10, 4, 2, 3, 1, 5, "//body/p[1]", selectedText = "Text")
        val state = KavitaAnnotationState(annotation.withPlainComment("Backup note"), annotation)
        repository.putAnnotation(
            42,
            "account",
            KavitaAnnotationEntry("backup", 4, 10, Json.encodeToString(state), 3, true),
        )
        val backup = adapter.captureAnnotations(7, mapOf(8L to chapter.url))
        repository.removeConnection(42)
        repository.putAnnotation(
            42,
            "account",
            KavitaAnnotationEntry(
                "current",
                4,
                10,
                Json.encodeToString(state.copy(annotation = annotation.withPlainComment("New work"))),
                5,
                true,
            ),
        )
        adapter.restoreAnnotations(7, mapOf(chapter.url to 8L), backup)
        val restored = repository.annotations(42, "account")
        assertEquals(2, restored.size)
        assertEquals(1, restored.count { it.remoteId == 10L })
        val conflict = Json.decodeFromString<KavitaAnnotationState>(restored.single { it.key == "backup" }.payload)
        assertTrue(conflict.conflict)
        assertEquals("Backup note", conflict.annotation.commentPlainText)
        assertEquals(0L, conflict.annotation.id)
    }
}
