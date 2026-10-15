package koharia.kavita

import koharia.domain.kavita.KavitaOperation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KavitaIdentityActivationTest {
    @Test fun legacyPendingProgressAndBookmarksAreHeldIdempotentlyWithoutChangingTheirIntent() = runBlocking {
        val repository = MemoryKavitaRepository()
        val ref = KavitaChapterRef(1, 2, 3, 4, 3)
        val progress = KavitaReadingState(ref, KavitaProgress(chapterId = 4, pageNum = 3), 10)
        val bookmark = KavitaBookmarkState(ref, KavitaBookmarkKind.IMAGE, 3, desired = false)
        val operations = listOf(
            KavitaOperation("progress/4", Json.encodeToString(progress), 1, true),
            KavitaOperation(bookmark.key, Json.encodeToString(bookmark), 2, true),
        )
        operations.forEach {
            repository.putOperation(1, "a", it)
            repository.putOperation(1, "other", it)
        }
        repository.quarantineForActivation(1, "a") {}
        val held = repository.operations(1, "a")
        assertTrue(held.all { it.pending })
        assertEquals(progress.copy(conflict = true), Json.decodeFromString<KavitaReadingState>(held[0].payload))
        assertEquals(
            bookmark.copy(needsConfirmation = true),
            Json.decodeFromString<KavitaBookmarkState>(held[1].payload),
        )
        assertEquals(operations, repository.operations(1, "other"))
        repository.quarantineForActivation(1, "a") {}
        assertEquals(held, repository.operations(1, "a"))
    }

    @Test fun unknownOrMalformedPendingOperationsBlockActivation() {
        for ((key, payload) in listOf("unknown" to "{}", "progress/1" to "{}", "bookmark/test" to "invalid")) {
            assertTrue(runCatching { holdKavitaOperation(KavitaOperation(key, payload, 1, true)) }.isFailure)
        }
    }
}
