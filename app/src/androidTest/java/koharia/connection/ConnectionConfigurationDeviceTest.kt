package koharia.connection

import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryPreferences
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ConnectionConfigurationDeviceTest {
    @Test
    fun realPreferenceDraftsRebuildEveryRegisteredProviderAndStorageMode(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val prefix = "configuration-fixture-${System.nanoTime()}"
        val inventory = context.getSharedPreferences(prefix, 0)
        val journalStorage = context.getSharedPreferences("$prefix-journal", 0)
        check(inventory.all.isEmpty() && journalStorage.all.isEmpty())
        val registry = Injekt.get<ConnectionRegistry>()
        val connections = ConnectionPreferences(AndroidPreferenceStore(context, inventory), Json)
        val journal = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalStorage), Json)
        val start = System.nanoTime()
        val profiles = registry.availableProviders().mapIndexed { index, provider ->
            LibraryConnectionProfile(start + index, provider.id, "Configuration fixture ${provider.id}")
        } + listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB).mapIndexed { index, mode ->
            LibraryConnectionProfile(start + registry.availableProviders().size + index, "local-folder", mode.name)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var manager: AndroidSourceManager? = null
        fun draft(id: Long) = context.getSharedPreferences("$prefix-draft-$id", 0)
        try {
            profiles.forEach { profile ->
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(profile.id))
                check(sourcePreferences("source_${profile.id}").all.isEmpty())
                write(profile, sourcePreferences("source_${profile.id}"), "before")
            }
            connections.setProfiles(profiles)
            val sources = AndroidSourceManager(
                context,
                EmptyStubs,
                connections,
                registry,
                scope,
                // This test constructs real providers but must not activate autonomous networking.
                configurationPending = { true },
            )
            manager = sources
            val commit = ConnectionConfigurationCommit(
                journal,
                { AndroidSharedConfigStorage(sourcePreferences("source_$it")) },
                { AndroidSharedConfigStorage(draft(it)) },
            )
            for (profile in profiles) {
                val originals = profiles.associate { it.id to checkNotNull(sources.get(it.id)) }
                val kavita = originals[profile.id] as? koharia.source.kavita.KavitaSource
                val previousSession = kavita?.session()
                val previousAccount = kavita?.preferences?.accountKey
                val previousDirectory = kavita?.downloadDirectoryName()
                val previousIdentity = kavita?.preferences?.identity
                commit.commit(profile.id, replaceSource = { publish ->
                    sources.withSourceConfiguration(profile.id, publish)
                    assertTrue(journal.isPaused(profile.id))
                    assertNotSame(originals[profile.id], checkNotNull(sources.get(profile.id)))
                }) {
                    assertNull(sources.get(profile.id))
                    write(profile, draft(profile.id), "after")
                    assertEquals("before", sourcePreferences("source_${profile.id}").getString("fixture-phase", null))
                    assertTrue(journal.isPaused(profile.id))
                }
                assertFalse(journal.isPaused(profile.id))
                assertEquals("after", sourcePreferences("source_${profile.id}").getString("fixture-phase", null))
                assertTrue(draft(profile.id).all.isEmpty())
                profiles.filterNot { it.id == profile.id }.forEach { assertSame(originals[it.id], sources.get(it.id)) }
                if (kavita != null) {
                    val updated = sources.get(profile.id) as koharia.source.kavita.KavitaSource
                    assertTrue(
                        checkNotNull(previousIdentity).sameServerVerified(checkNotNull(updated.preferences.identity)),
                    )
                    assertEquals(previousAccount, updated.preferences.accountKey)
                    assertEquals(previousDirectory, updated.downloadDirectoryName())
                    assertEquals(17L, updated.preferences.mediaId)
                    assertEquals("http://127.0.0.1:2/", updated.preferences.address)
                    assertTrue(updated.preferences.key == "fixture-after")
                    assertTrue(
                        runCatching { checkNotNull(previousSession).checkActive() }.exceptionOrNull() is
                            kotlinx.coroutines.CancellationException,
                    )
                    updated.session().checkActive()
                }
            }
            val target = profiles.last()
            assertTrue(
                runCatching {
                    commit.commit(target.id, { block ->
                        sources.withSourceConfiguration(target.id, block)
                        error("Interrupted before publication acknowledgement")
                    }) { write(target, draft(target.id), "recovered") }
                }.isFailure,
            )
            val restoredJournal = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalStorage), Json)
            assertTrue(restoredJournal.isPaused(target.id))
            val recovery = ConnectionConfigurationCommit(
                restoredJournal,
                { AndroidSharedConfigStorage(sourcePreferences("source_$it")) },
                { AndroidSharedConfigStorage(draft(it)) },
            )
            recovery.recover(target.id)
            recovery.finishRecovery(target.id)
            assertFalse(restoredJournal.isPaused(target.id))
            assertEquals("recovered", sourcePreferences("source_${target.id}").getString("fixture-phase", null))
        } finally {
            manager?.withSourceUpdatesDeferred { connections.setProfiles(emptyList()) }
            scope.cancel()
            profiles.forEach {
                NetworkStorageRuntime.invalidate(it.id)
                context.deleteSharedPreferences("source_${it.id}")
                context.deleteSharedPreferences("$prefix-draft-${it.id}")
            }
            context.deleteSharedPreferences(prefix)
            context.deleteSharedPreferences("$prefix-journal")
        }
    }

    private fun write(profile: LibraryConnectionProfile, preferences: SharedPreferences, phase: String) {
        val address = "http://127.0.0.1:1"
        when (profile.providerId) {
            "komga" -> preferences.edit().putString("Koharia server profile name", phase).commit()
            "lanraragi" -> koharia.source.lanraragi.LanraragiPreferences(profile.id, preferences)
                .save(address, "fixture", groupCollections = phase == "after")
            "suwayomi" -> koharia.source.suwayomi.SuwayomiPreferences(profile.id, preferences).apply {
                save(address, "", koharia.suwayomi.SuwayomiAuthMode.NONE, "", "")
                visibleCategoryIds = if (phase == "after") setOf(1) else null
            }
            "smanga" -> koharia.source.smanga.SmangaPreferences(profile.id, preferences).apply {
                save(address, "fixture", "fixture", 1)
                order =
                    if (phase == "after") "mangaName desc" else koharia.source.smanga.SmangaPreferences.DEFAULT_ORDER
            }
            "kavita" -> koharia.source.kavita.KavitaPreferences(profile.id, preferences).apply {
                save(
                    if (phase == "before") address else "http://127.0.0.1:2/",
                    "fixture-$phase",
                    koharia.kavita.KavitaAccount(
                        1,
                        "fixture",
                        roles = listOf("Admin"),
                        kavitaVersion = "0.8.0",
                        createdUtc = "2025-01-01",
                        installId = "configuration-fixture-installation",
                    ),
                )
                if (phase == "before") mediaId = 17L
                chapterTitleTemplate = phase
            }
            "local-folder" -> {
                val mode =
                    LibraryStorageMode.entries.firstOrNull { it.name == profile.name } ?: LibraryStorageMode.LOCAL
                NetworkStoragePreferences(profile.id, preferences).save(
                    NetworkStorageConfiguration(
                        mode = mode,
                        address = if (mode == LibraryStorageMode.SMB) "smb://127.0.0.1/share" else address,
                        rootIdentity = "fixture-${profile.id}",
                        needsValidation = true,
                    ),
                    "fixture",
                    "fixture",
                )
                LocalLibraryPreferences(profile.id, Json, preferences).saveLibraryDraft(
                    LocalLibraryConfig(libraryId = "fixture-${profile.id}", setupCompleted = true),
                    emptyMap(),
                )
            }
            else -> error("Missing configuration fixture for registered provider")
        }
        check(preferences.edit().putString("fixture-phase", phase).commit())
    }

    private object EmptyStubs : StubSourceRepository {
        override fun subscribeAll() = flowOf(emptyList<StubSource>())
        override suspend fun getStubSource(id: Long): StubSource? = null
        override suspend fun upsertStubSource(id: Long, lang: String, name: String) = Unit
    }
}
