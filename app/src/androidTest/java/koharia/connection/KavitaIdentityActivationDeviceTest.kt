package koharia.connection

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.domain.kavita.KavitaAnnotationEntry
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaAnnotation
import koharia.kavita.KavitaAnnotationState
import koharia.kavita.KavitaBookmarkKind
import koharia.kavita.KavitaBookmarkState
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaProgress
import koharia.kavita.KavitaReadingState
import koharia.kavita.quarantineForActivation
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaIdentityActivationDeviceTest {
    @Test fun verifiedOriginalIdentityRetainsDataAndDoesNotReplayAnyLegacyQueue(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        val prefix = "kavita-activation-$id"
        val names = listOf(prefix, "$prefix-journal", "$prefix-draft", "source_$id")
        names.forEach { check(context.getSharedPreferences(it, 0).all.isEmpty()) }
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val inventory = context.getSharedPreferences(prefix, 0)
        val journalPrefs = context.getSharedPreferences("$prefix-journal", 0)
        val journal = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), Json)
        val connections = ConnectionPreferences(AndroidPreferenceStore(context, inventory), Json)
        val profile = LibraryConnectionProfile(id, "kavita", prefix)
        val account =
            KavitaAccount(7, "fixture", roles = listOf("Login"), kavitaVersion = "0.9.1.4", installId = prefix)
        val settings = KavitaPreferences(id)
        val repository = Injekt.get<KavitaRepository>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var sources: AndroidSourceManager? = null
        try {
            settings.save("http://127.0.0.1:1/", "fixture", account)
            settings.mediaId = 19
            val owner = settings.accountKey
            val ref = KavitaChapterRef(1, 2, 3, 4, 3)
            val state = KavitaReadingState(ref, KavitaProgress(chapterId = 4, pageNum = 3), 10)
            val progress = KavitaOperation("progress/4", Json.encodeToString(state), 2, true)
            val bookmark = KavitaBookmarkState(ref, KavitaBookmarkKind.IMAGE, 3)
            val annotation = KavitaAnnotationState(
                KavitaAnnotation(
                    chapterId = 4,
                    seriesId = 2,
                    volumeId = 3,
                    libraryId = 1,
                    ownerUserId = 7,
                    xPath = "//p",
                ),
            )
            repository.putOperation(id, owner, progress)
            repository.putOperation(id, "other", progress)
            repository.putOperation(id, owner, KavitaOperation(bookmark.key, Json.encodeToString(bookmark), 3, true))
            repository.putAnnotation(
                id,
                owner,
                KavitaAnnotationEntry("annotation", 4, null, Json.encodeToString(annotation), 4, true),
            )
            journal.prepareImport(listOf(profile))
            journal.readyImported(setOf(id))
            journal.published(id)
            connections.setProfiles(listOf(profile))
            val manager = AndroidSourceManager(
                context,
                EmptyStubs,
                connections,
                Injekt.get(),
                scope,
                configurationPending = { true },
            )
            sources = manager
            val original = manager.get(id) as KavitaSource
            val oldSession = original.session()
            assertTrue(runCatching { original.prepareVerifiedActivation(account.copy(id = 8), journal) }.isFailure)
            oldSession.checkActive()
            val quarantine = original.prepareVerifiedActivation(account, journal)
            val draft = context.getSharedPreferences("$prefix-draft", 0)
            val commit = ConnectionConfigurationCommit(
                journal,
                { AndroidSharedConfigStorage(sourcePreferences("source_$it")) },
                { AndroidSharedConfigStorage(draft) },
            )
            commit.commit(id, { publish -> manager.withSourceConfiguration(id, publish) }) {
                quarantine()
                KavitaPreferences(id, draft).save("http://127.0.0.1:2/", "replacement-fixture", account)
            }
            // A crash between configuration publication and explicit activation must remain paused.
            assertTrue(ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), Json).isPaused(id))
            assertTrue(runCatching { oldSession.checkActive() }.isFailure)
            val updated = manager.get(id) as KavitaSource
            assertNotSame(original, updated)
            assertEquals(owner, updated.preferences.accountKey)
            assertEquals(19L, updated.preferences.mediaId)
            assertEquals(listOf(profile), connections.getProfiles())
            journal.activateVerified(id)
            assertFalse(ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), Json).isPaused(id))
            val pending = repository.pendingOperations(id, owner)
            assertEquals(2, pending.size)
            assertTrue(
                Json.decodeFromString<KavitaReadingState>(
                    pending.first {
                        it.key == "progress/4"
                    }.payload,
                ).conflict,
            )
            assertTrue(
                Json.decodeFromString<KavitaBookmarkState>(
                    pending.first {
                        it.key == bookmark.key
                    }.payload,
                ).needsConfirmation,
            )
            assertTrue(
                Json.decodeFromString<KavitaAnnotationState>(
                    repository.annotations(id, owner).single().payload,
                ).needsConfirmation,
            )
            assertEquals(progress, repository.operation(id, "other", "progress/4"))
            val session = updated.session()
            // Unreachable endpoints make any accidental HTTP attempt fail; all three queues must stay held.
            session.reading.flush()
            session.bookmarks.flush()
            session.annotations.flush()
            assertEquals(pending, repository.pendingOperations(id, owner))
            assertTrue(session.reading.record(ref.copy(chapterId = 5), 1, 10, 100))
            assertFalse(
                Json.decodeFromString<KavitaReadingState>(
                    repository.operation(id, owner, "progress/5")!!.payload,
                ).conflict,
            )
        } finally {
            scope.cancel()
            sources?.get(id)?.let { (it as AutoCloseable).close() }
            repository.removeConnection(id)
            names.forEach(context::deleteSharedPreferences)
        }
    }

    @Test fun quarantineRollsBackEveryQueueIfValidationFailsBeforeCommit(): Unit = runBlocking(Dispatchers.IO) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val repository = Injekt.get<KavitaRepository>()
        val id = -System.nanoTime()
        val state = KavitaReadingState(KavitaChapterRef(1, 2, 3, 4, 3), KavitaProgress(chapterId = 4), 10)
        val operation = KavitaOperation("progress/4", Json.encodeToString(state), 1, true)
        try {
            repository.putOperation(id, "a", operation)
            var checks = 0
            assertTrue(
                runCatching {
                    repository.quarantineForActivation(id, "a") { check(++checks < 2) }
                }.isFailure,
            )
            assertEquals(operation, repository.operation(id, "a", operation.key))
            repository.putOperation(id, "a", KavitaOperation("unknown", "{}", 1, true))
            assertTrue(runCatching { repository.quarantineForActivation(id, "a") {} }.isFailure)
            assertEquals(operation, repository.operation(id, "a", operation.key))
        } finally {
            repository.removeConnection(id)
        }
    }

    private object EmptyStubs : StubSourceRepository {
        override fun subscribeAll() = flowOf(emptyList<StubSource>())
        override suspend fun getStubSource(id: Long): StubSource? = null
        override suspend fun upsertStubSource(id: Long, lang: String, name: String) = Unit
    }
}
