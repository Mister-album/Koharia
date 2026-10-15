package koharia.connection

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupEpubBookmark
import eu.kanade.tachiyomi.data.backup.models.BackupEpubProgress
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTtsProgress
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.providers.BackupKavitaAnnotation
import eu.kanade.tachiyomi.data.backup.providers.BackupKavitaState
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.KavitaAnnotation
import koharia.kavita.KavitaAnnotationState
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaIdentity
import koharia.kavita.KavitaProgress
import koharia.kavita.KavitaReadingState
import koharia.source.local.LocalFolderConnectionProvider
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class ConnectionSourceRestoreDeviceTest {
    @Test
    fun connectionOnlyBackupRecreatesEveryRegisteredProviderIncludingNetworkStorage(): Unit =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("app.koharia.dev.devicefixture", context.packageName)
            val registry = Injekt.get<ConnectionRegistry>()
            val json = Injekt.get<Json>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val prefix = "restore-runtime-${System.nanoTime()}"
            val inventory = context.getSharedPreferences(prefix, 0)
            check(inventory.all.isEmpty())
            val preferences = ConnectionPreferences(AndroidPreferenceStore(context, inventory), json) {
                inventory.edit().commit()
            }
            val journalStore = context.getSharedPreferences("$prefix-journal", 0)
            check(journalStore.all.isEmpty())
            val journal = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalStore), json)
            var interruptFlush = true
            val profileManager = ConnectionProfileManager(preferences, registry, Injekt.get(), journal) { id ->
                check(!interruptFlush) { "Injected interruption after committed reading data" }
                sourcePreferences("source_$id").edit().commit()
            }
            val startId = System.currentTimeMillis()
            val profiles = registry.availableProviders().mapIndexed { index, provider ->
                LibraryConnectionProfile(startId + index, provider.id, "Restore fixture ${provider.id}")
            } + listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB).mapIndexed { index, mode ->
                LibraryConnectionProfile(
                    startId + registry.availableProviders().size + index,
                    LocalFolderConnectionProvider.ID,
                    "Restore fixture $mode",
                )
            }
            val ownedPreferences = mutableListOf<String>()
            val file = File(context.cacheDir, "$prefix.kohariabk")
            val database = Injekt.get<Database>()
            val getManga = Injekt.get<GetMangaByUrlAndSourceId>()
            val kavita = Injekt.get<KavitaRepository>()
            val readStatus = Injekt.get<koharia.domain.komga.KomgaReadStatusRepository>()
            val pageEvents = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
            val ref = KavitaChapterRef(1, 2, 3, 4, 3)
            val account = "restore-fixture"
            val mangas = profiles.map { profile ->
                val identity = KavitaIdentity(profile.id, account)
                val url = if (profile.providerId == "kavita") identity.series(2) else "/fixture/$prefix"
                val chapterUrl = if (profile.providerId == "kavita") identity.chapter(ref) else "$url/chapter"
                val locator = """{"href":"$chapterUrl","type":"application/xhtml+xml"}"""
                BackupManga(
                    source = profile.id,
                    url = url,
                    title = prefix,
                    chapters = listOf(BackupChapter(chapterUrl, "Fixture", read = true, lastPageRead = 8)),
                    history = listOf(BackupHistory(chapterUrl, 1000, 400)),
                    epubProgress = listOf(BackupEpubProgress(chapterUrl, chapterUrl, locator, 0.4, 8, 1000)),
                    epubBookmarks = listOf(
                        BackupEpubBookmark(chapterUrl, locator, note = "Keep note", createdAt = 1000),
                    ),
                    ttsProgress = listOf(BackupTtsProgress(chapterUrl, 5, 1000)),
                ).apply {
                    if (profile.providerId == "kavita") {
                        kavitaState = listOf(
                            BackupKavitaState(
                                chapterUrl,
                                json.encodeToString(KavitaReadingState(ref, KavitaProgress(pageNum = 8), 20)),
                                5,
                                true,
                            ),
                        )
                        kavitaAnnotations = listOf(
                            BackupKavitaAnnotation(
                                chapterUrl,
                                "fixture-annotation",
                                json.encodeToString(
                                    KavitaAnnotationState(
                                        KavitaAnnotation(9, 4, 2, 3, 1, 1, "/p[1]", comment = "Keep annotation"),
                                        locatorJson = locator,
                                    ),
                                ),
                                5,
                                true,
                            ),
                        )
                    }
                }
            }
            var manager: AndroidSourceManager? = null
            try {
                val restoredPreferences = profiles.map { profile ->
                    val name = "source_${profile.id}"
                    check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(profile.id))
                    check(sourcePreferences(name).all.isEmpty())
                    ownedPreferences += name
                    sourcePreferences(name).edit().putString("restore_fixture_revision", "before").commit()
                    val mode = when (profile.name) {
                        "Restore fixture WEBDAV" -> LibraryStorageMode.WEBDAV
                        "Restore fixture SMB" -> LibraryStorageMode.SMB
                        else -> LibraryStorageMode.LOCAL
                    }
                    val entries = mutableListOf(
                        BackupPreference("restore_fixture_revision", StringPreferenceValue("after")),
                    )
                    if (mode != LibraryStorageMode.LOCAL) {
                        val config = NetworkStorageConfiguration(
                            mode = mode,
                            address = if (mode == LibraryStorageMode.WEBDAV) {
                                "http://127.0.0.1:9/fixture/"
                            } else {
                                "smb://127.0.0.1/fixture/"
                            },
                            rootIdentity = "$prefix-${mode.name}",
                            needsValidation = true,
                        )
                        NetworkStoragePreferences(profile.id).save(config, "fixture", "fixture")
                        entries += BackupPreference(
                            "network_storage_configuration",
                            StringPreferenceValue(json.encodeToString(config.copy(verifiedInternal = true))),
                        )
                    }
                    BackupSourcePreferences(name, entries)
                }
                preferences.setProfiles(profiles)
                val sources = AndroidSourceManager(context, EmptyStubs, preferences, registry, scope)
                manager = sources
                val before = profiles.associate { it.id to sources.get(it.id) }
                assertTrue(before.values.all { it != null })
                mangas.forEach { manga ->
                    MangaRestorer().restore(
                        BackupManga(
                            manga.source,
                            manga.url,
                            title = prefix,
                            chapters = listOf(BackupChapter(manga.chapters.single().url, "Original", lastPageRead = 1)),
                        ),
                        emptyList(),
                    )
                }
                writeBackup(
                    file,
                    Backup(
                        mangas,
                        backupSourcePreferences = restoredPreferences,
                        backupConnections = profiles.map { BackupConnection(it.id, it.providerId, it.name) },
                        backupKomgaReadStatus = listOf(
                            eu.kanade.tachiyomi.data.backup.models.BackupKomgaReadStatus(
                                profiles.single { it.providerId == "komga" }.id,
                                0,
                                "unindexed-book",
                                2,
                                false,
                                100,
                            ),
                            eu.kanade.tachiyomi.data.backup.models.BackupKomgaReadStatus(
                                profiles.single { it.providerId == "komga" }.id,
                                1,
                                "unindexed-book",
                                4,
                                true,
                                110,
                            ),
                        ),
                        backupKomgaPages = listOf(
                            eu.kanade.tachiyomi.data.backup.models.BackupKomgaPageEvent(
                                profiles.single { it.providerId == "komga" }.id,
                                0,
                                "page-book",
                                3,
                                0,
                                5,
                                10,
                                101,
                            ),
                        ),
                        backupKomgaPageAttempts = listOf(
                            eu.kanade.tachiyomi.data.backup.models.BackupKomgaPageEvent(
                                profiles.single { it.providerId == "komga" }.id,
                                0,
                                "page-book",
                                2,
                                0,
                                3,
                                10,
                                100,
                            ),
                        ),
                        backupKomgaReadStatusAttempts = listOf(
                            eu.kanade.tachiyomi.data.backup.models.BackupKomgaReadStatus(
                                profiles.single { it.providerId == "komga" }.id,
                                0,
                                "unindexed-book",
                                1,
                                true,
                                99,
                            ),
                        ),
                    ),
                )
                fun restorer() = BackupRestorer(
                    context,
                    BackupNotifier(context),
                    isSync = false,
                    sourceManager = sources,
                    connectionProfileManager = profileManager,
                )
                val options = RestoreOptions(categories = false, appSettings = false)
                val interrupted = runCatching { restorer().restore(Uri.fromFile(file), options) }
                assertTrue("Expected interruption at settings flush", interrupted.isFailure)
                assertEquals(profiles, preferences.getProfiles())
                val reserved = journal.reservedProfiles()
                assertEquals(profiles.size, reserved.size)
                val committed = database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList()
                    .filter { row -> reserved.any { it.id == row.source } }
                assertEquals(profiles.size, committed.size)
                val committedIntents = readStatus.unsettled().filter { row ->
                    reserved.any {
                        it.id ==
                            row.key.connectionId
                    }
                }
                assertEquals(2, committedIntents.size)
                assertTrue(committedIntents.all { it.phase == koharia.domain.komga.KomgaReadStatusPhase.HELD })
                val restoredKomga = reserved.single { it.providerId == "komga" }.id
                val committedPages = pageEvents.unsettledStates().filter {
                    it.event?.key?.connectionId == restoredKomga
                }
                val committedRequests = readStatus.allAttempts().filter { it.intent.key.connectionId == restoredKomga }
                assertEquals(1, committedPages.size)
                assertEquals(3L, committedPages.single().event!!.generation)
                assertEquals(2L, committedPages.single().attempt!!.event.generation)
                assertEquals(1, committedRequests.size)
                val committedIds = committed.map { getManga.await(it.url, it.source)!!.id }.toSet()
                interruptFlush = false
                restorer().restore(
                    Uri.fromFile(file),
                    options,
                )
                for (profile in profiles) {
                    assertNotNull(profile.name, sources.get(profile.id))
                    assertNotSame(profile.name, before[profile.id], sources.get(profile.id))
                    assertEquals(
                        "before",
                        sourcePreferences("source_${profile.id}")
                            .getString("restore_fixture_revision", null),
                    )
                    val imported = preferences.getProfiles().single { it.name == "${profile.name} (2)" }
                    ownedPreferences += "source_${imported.id}"
                    assertEquals(profile.providerId, imported.providerId)
                    assertNotNull(sources.get(imported.id))
                    assertTrue(journal.isPaused(imported.id))
                    assertFalse(journal.isPaused(profile.id))
                    if (profile.providerId == "komga") {
                        assertEquals(
                            committedPages,
                            pageEvents.unsettledStates().filter {
                                it.event?.key?.connectionId ==
                                    imported.id
                            },
                        )
                        assertEquals(
                            committedRequests,
                            readStatus.allAttempts().filter {
                                it.intent.key.connectionId ==
                                    imported.id
                            },
                        )
                        val restoredIntents = readStatus.unsettled().filter { it.key.connectionId == imported.id }
                        assertEquals(committedIntents, restoredIntents)
                        assertEquals(2, restoredIntents.map { it.key.ownerKey }.distinct().size)
                        assertTrue(
                            restoredIntents.all {
                                it.key.ownerKey.startsWith("restored:") && it.baseline == null
                            },
                        )
                        restoredIntents.forEach {
                            assertTrue(readStatus.pending(imported.id, it.key.ownerKey).isEmpty())
                        }
                        assertTrue(readStatus.unsettled().none { it.key.connectionId == profile.id })
                    }
                    val original = mangas.single { it.source == profile.id }
                    val oldManga = getManga.await(original.url, profile.id)!!
                    val oldChapter = database.chaptersQueries.getChaptersByMangaId(
                        oldManga.id,
                        0,
                    ).awaitAsList().single()
                    assertFalse(oldChapter.read)
                    assertEquals(1L, oldChapter.last_page_read)
                    val importedUrl = if (profile.providerId == "kavita") {
                        KavitaIdentity(imported.id, account).series(2)
                    } else {
                        original.url
                    }
                    val restoredManga = getManga.await(importedUrl, imported.id)!!
                    assertTrue(restoredManga.id in committedIds)
                    val chapter = database.chaptersQueries.getChaptersByMangaId(
                        restoredManga.id,
                        0,
                    ).awaitAsList().single()
                    assertTrue(chapter.read)
                    assertEquals(8L, chapter.last_page_read)
                    assertEquals(
                        400L,
                        database.historyQueries.getHistoryByMangaId(restoredManga.id).awaitAsList().single().time_read,
                    )
                    val progress = database.epub_progressQueries.getByChapterId(chapter._id).awaitAsList().single()
                    assertEquals(chapter.url, progress.book_url)
                    assertTrue(progress.locator_json.contains(chapter.url))
                    val bookmark = database.epub_bookmarkQueries.getByMangaId(restoredManga.id).awaitAsList().single()
                    assertEquals("Keep note", bookmark.note)
                    assertEquals(
                        5L,
                        database.tts_progressQueries.getByMangaId(
                            restoredManga.id,
                        ).awaitAsList().single().sentence_index,
                    )
                    if (profile.providerId == "kavita") {
                        val operation = kavita.operations(imported.id, account).single()
                        assertTrue(operation.pending)
                        assertTrue(json.decodeFromString<KavitaReadingState>(operation.payload).conflict)
                        val annotation = kavita.annotations(imported.id, account).single()
                        assertTrue(annotation.pending)
                        val state = json.decodeFromString<KavitaAnnotationState>(annotation.payload)
                        assertTrue(state.conflict)
                        assertEquals("Keep annotation", state.annotation.comment)
                        assertTrue(state.locatorJson!!.contains(chapter.url))
                        assertTrue(kavita.operations(profile.id, account).isEmpty())
                    }
                    assertEquals(
                        "after",
                        sourcePreferences("source_${imported.id}")
                            .getString("restore_fixture_revision", null),
                    )
                    val network = NetworkStoragePreferences(imported.id).configuration
                    if (network.mode != LibraryStorageMode.LOCAL) {
                        assertTrue(network.needsValidation)
                        assertFalse(network.verifiedInternal)
                    }
                }
                assertEquals(profiles.size * 2, preferences.getProfiles().size)
                assertTrue(preferences.getProfiles().containsAll(profiles))
                assertFalse(ConnectionRestoreState.isRestoring)
            } finally {
                val ownedIds = (profiles + journal.reservedProfiles()).mapTo(mutableSetOf()) { it.id }
                database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().filter { it.source in ownedIds }
                    .forEach { row ->
                        getManga.await(row.url, row.source)?.takeIf { it.title == prefix }?.let {
                            database.mangasQueries.deleteMangaById(it.id)
                        }
                    }
                ownedIds.forEach { kavita.removeConnection(it) }
                ownedIds.forEach {
                    readStatus.removeConnection(it)
                    pageEvents.removeConnection(it)
                }
                journal.reservedProfiles().forEach { ownedPreferences += "source_${it.id}" }
                preferences.getProfiles().filter { it.id !in profiles.map { profile -> profile.id } }.forEach {
                    ownedPreferences += "source_${it.id}"
                }
                manager?.withSourceUpdatesDeferred { preferences.setProfiles(emptyList()) }
                scope.cancel()
                ownedPreferences.forEach { name ->
                    NetworkStorageRuntime.invalidate(name.removePrefix("source_").toLong())
                    context.deleteSharedPreferences(name)
                }
                context.deleteSharedPreferences(prefix)
                context.deleteSharedPreferences("$prefix-journal")
                file.delete()
            }
        }

    private fun writeBackup(file: File, backup: Backup) {
        val parser = Injekt.get<ProtoBuf>()
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(parser.encodeToByteArray(Backup.serializer(), backup)) }
        }.toByteArray()
        val envelope = KohariaBackupEnvelope(
            magic = KohariaBackupEnvelope.MAGIC,
            formatVersion = KohariaBackupEnvelope.FORMAT_VERSION,
            schemaVersion = KohariaBackupEnvelope.SCHEMA_VERSION,
            createdAt = System.currentTimeMillis(),
            applicationId = "app.koharia.dev.devicefixture",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(compressed)
                .joinToString("") { "%02x".format(it) },
            compressedPayload = compressed,
        )
        file.writeBytes(parser.encodeToByteArray(KohariaBackupEnvelope.serializer(), envelope))
    }

    private object EmptyStubs : StubSourceRepository {
        override fun subscribeAll() = flowOf(emptyList<StubSource>())
        override suspend fun getStubSource(id: Long): StubSource? = null
        override suspend fun upsertStubSource(id: Long, lang: String, name: String) = Unit
    }
}
