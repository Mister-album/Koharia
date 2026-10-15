package koharia.kavita

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.connection.DevReadOnlyValidation
import koharia.domain.kavita.KavitaAnnotationEntry
import koharia.domain.kavita.KavitaOperation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Run both normally and with -PdevReadOnlyValidation=true; no device or server is used. */
class KavitaPendingValidationTest {
    private val json = Json { encodeDefaults = true }
    private val repository = MemoryKavitaRepository()
    private val account = KavitaAccount(id = 1, roles = listOf("Login", "Bookmark"), kavitaVersion = "0.9.1.4")
    private val api = mockk<KavitaApiClient>().also {
        every { it.json } returns json
        coEvery { it.getAccount(true) } returns account
        every { it.capabilities(account) } returns KavitaCapabilities(KavitaVersion(0, 9, 1), account.roles.toSet())
    }
    private val catalog = mockk<KavitaCatalog>().also { every { it.api } returns api }
    private val ref = KavitaChapterRef(1, 2, 3, 4, 1)
    private val readOnly = DevReadOnlyValidation.enabled

    @ParameterizedTest
    @CsvSource("false, 5", "true, 5", "false, 9", "true, 9")
    fun `progress reconciliation respects the build validation boundary`(retry: Boolean, remotePage: Int) = runTest {
        val progress = KavitaProgress(1, 2, 3, 4, 5, null, "2026-01-01T00:00:00Z")
        val state = KavitaReadingState(ref, progress, 10)
        val pending = KavitaOperation("progress/4", json.encodeToString(state), 1, true)
        repository.putOperation(17, "fixture", pending)
        coEvery { api.progress(4) } returns progress.copy(pageNum = remotePage)
        val reading = KavitaReadingCoordinator(17, "fixture", repository, api, this, {}, {})
        if (retry) {
            // Registration and connectivity recovery both enter through retryPending.
            reading.retryPending()
            advanceUntilIdle()
        } else {
            reading.flush()
        }
        val expected = when {
            readOnly -> pending
            remotePage == progress.pageNum -> pending.copy(pending = false)
            else -> pending.copy(payload = json.encodeToString(state.copy(conflict = true)))
        }
        assertEquals(expected, repository.operation(17, "fixture", pending.key))
        coVerify(exactly = if (readOnly) 0 else 1) { api.progress(4) }
        coVerify(exactly = 0) { api.saveProgress(any()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `matching bookmark remains pending during read only validation`(retry: Boolean) = runTest {
        val state = KavitaBookmarkState(ref, KavitaBookmarkKind.IMAGE, 2)
        val pending = KavitaOperation(state.key, json.encodeToString(state), 3, true)
        repository.putOperation(17, "fixture", pending)
        coEvery { catalog.resource(state.path, true) } returns JsonArray(listOf(state.body()))
        val bookmarks = KavitaBookmarks(17, "fixture", repository, catalog, this, {}, { true })
        if (retry) {
            bookmarks.retryPending()
            advanceUntilIdle()
        } else {
            bookmarks.flush()
        }
        assertEquals(pending.copy(pending = readOnly), repository.operation(17, "fixture", state.key))
        coVerify(exactly = if (readOnly) 0 else 2) { catalog.resource(state.path, true) }
        coVerify(exactly = if (readOnly) 0 else 1) { api.getAccount(true) }
        coVerify(exactly = 0) { api.mutate(any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `uncertain annotation creation remains untouched during read only validation`(retry: Boolean) = runTest {
        val annotation = KavitaAnnotation(
            chapterId = 4,
            seriesId = 2,
            volumeId = 3,
            libraryId = 1,
            ownerUserId = 1,
            xPath = "//body/p[1]",
            selectedText = "Fixture text",
        )
        val state = KavitaAnnotationState(annotation, submittedCreate = annotation)
        val pending = KavitaAnnotationEntry("fixture-note", 4, null, json.encodeToString(state), 3, true)
        repository.putAnnotation(17, "fixture", pending)
        val remote = annotation.copy(id = 10)
        coEvery { api.execute("Annotation/all?chapterId=4") } returns json.parseToJsonElement(
            json.encodeToString(listOf(remote)),
        )
        val annotations = KavitaAnnotationCoordinator(17, "fixture", 1, repository, catalog, this, {}, {}, { true })
        if (retry) {
            annotations.retryPending()
            advanceUntilIdle()
        } else {
            annotations.flush()
        }
        val expected = if (readOnly) {
            pending
        } else {
            pending.copy(
                remoteId = 10,
                payload = json.encodeToString(
                    state.copy(annotation = remote, baseline = remote, submittedCreate = null),
                ),
                pending = false,
            )
        }
        assertEquals(listOf(expected), repository.annotations(17, "fixture"))
        coVerify(exactly = if (readOnly) 0 else 1) { api.execute("Annotation/all?chapterId=4") }
        coVerify(exactly = if (readOnly) 0 else 1) { api.getAccount(true) }
        coVerify(exactly = 0) { api.execute(any(), "POST", any(), any()) }
    }
}
