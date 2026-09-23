package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupEpubBookmark
import eu.kanade.tachiyomi.data.backup.models.BackupEpubProgress
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTtsProgress
import eu.kanade.tachiyomi.data.backup.providers.BackupLanraragiState
import eu.kanade.tachiyomi.data.backup.providers.BackupSmangaHistoryEvent
import eu.kanade.tachiyomi.data.backup.providers.BackupSmangaReadState
import eu.kanade.tachiyomi.data.backup.providers.BackupSmangaState
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupReadingStateTest {
    @Test
    fun `reader and provider states retain chapter identities and pending data`() {
        val manga = BackupManga(source = 42, url = "book/1").apply {
            epubProgress = listOf(BackupEpubProgress("chapter/1", "book/1", "locator", 0.42, 5, 100, 90))
            epubBookmarks = listOf(BackupEpubBookmark("chapter/1", "bookmark", "Section", 0.25, "note", 80))
            ttsProgress = listOf(BackupTtsProgress("chapter/1", 12, 70))
            lanraragiState = listOf(BackupLanraragiState("chapter/1", 4, 12, 60, pending = true))
            smangaState = listOf(
                BackupSmangaState(
                    "chapter/1",
                    BackupSmangaReadState(4, 12, false, 60, 2, true),
                    listOf(BackupSmangaHistoryEvent("event-1", 7, 60)),
                    confirmationPayload = "{\"required\":true}",
                ),
            )
        }

        val restored = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), manga),
        )

        assertEquals(manga.epubProgress, restored.epubProgress)
        assertEquals(manga.epubBookmarks, restored.epubBookmarks)
        assertEquals(manga.ttsProgress, restored.ttsProgress)
        assertEquals(manga.lanraragiState, restored.lanraragiState)
        assertEquals(manga.smangaState, restored.smangaState)
    }
}
