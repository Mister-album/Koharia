package koharia.connection

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.restore.BackupConnectionInventory
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ui.SharedConfigSelection
import koharia.source.komga.KomgaConnectionMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class LegacyBackupRestoreDeviceTest {
    @Test
    fun v040PlainInventorySettingsAndReadingData() = restoreFrozen(
        "v0.4.0-plain.kohariabk",
        "f6cdaa394e4d35e4a85cec7d02ae04fccff1c5a0cbeda6bc51b8a5252ffd8b00",
        1,
        setOf("komga"),
    )

    @Test
    fun v060EncryptedInventorySettingsAndReadingData() = restoreFrozen(
        "v0.6.0-encrypted.kohariabk",
        "216e527a22f44aa2c4f7a3aea93d6f64419648bb68b796986c70737cc4f03c2c",
        2,
        setOf("komga", "local-folder", "lanraragi", "smanga"),
        "fixture-password".toCharArray(),
    )

    private fun restoreFrozen(
        asset: String,
        hash: String,
        version: Int,
        providers: Set<String>,
        password: CharArray? = null,
    ): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val prefix = "legacy-restore-${System.nanoTime()}"
        val file = File(context.cacheDir, "$prefix.kohariabk")
        val exportedFile = File(context.cacheDir, "$prefix-export.kohariabk")
        val prefs = context.getSharedPreferences(prefix, 0)
        val journalPrefs = context.getSharedPreferences("$prefix-journal", 0)
        check(prefs.all.isEmpty() && journalPrefs.all.isEmpty())
        val store = AndroidPreferenceStore(context, prefs)
        val json = Injekt.get<Json>()
        val connections = ConnectionPreferences(store, json) { prefs.edit().commit() }
        val journal = ConnectionIdentityChanges(AndroidSharedConfigStorage(journalPrefs), json)
        val registry = Injekt.get<ConnectionRegistry>()
        val profileManager = ConnectionProfileManager(
            connections,
            registry,
            ConnectionConfigManager(store),
            journal,
        ) { sourcePreferences("source_$it").edit().commit() }
        val defaults = mapOf<String, Any>(
            "pref_double_tap_anim_speed" to 500,
            "pref_show_page_number_key" to true,
        )
        fun migration() = SharedConfigMigration(
            AndroidSharedConfigStorage(prefs),
            defaults,
            connections::getProfiles,
            json,
        )
        val shared = migration()
        assertTrue(shared.initialize())
        store.getInt("connection_shared::pref_double_tap_anim_speed").set(777)
        val globalPreferences = Injekt.get<PreferenceStore>().getAll().toMap()
        val globalInventory = Injekt.get<ConnectionPreferences>().getProfiles()
        val database = Injekt.get<Database>()
        val getManga = Injekt.get<GetMangaByUrlAndSourceId>()
        val originalIds = mutableSetOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var sources: AndroidSourceManager? = null
        var categoryName: String? = null
        try {
            val bytes = instrumentation.context.assets.open("legacy-backup/$asset").use { it.readBytes() }
            assertEquals(hash, sha256(bytes))
            file.writeBytes(bytes)
            val decoder = BackupDecoder(context)
            val envelope = decoder.inspect(Uri.fromFile(file))
            assertEquals(version, envelope.formatVersion)
            assertEquals(version, envelope.schemaVersion)
            assertEquals(version == 2, envelope.encryptedPayload.isNotEmpty())
            val backup = decoder.decode(Uri.fromFile(file), password)
            assertTrue(backup.backupConnections.isEmpty())
            val profiles = BackupConnectionInventory.read(backup).map {
                LibraryConnectionProfile(it.id, it.providerId, it.name)
            }
            assertEquals(providers, profiles.map { it.providerId }.toSet())
            val incomingCategoryName = backup.backupCategories.single().name
            check(database.categoriesQueries.getCategories().awaitAsList().none { it.name == incomingCategoryName })
            categoryName = incomingCategoryName
            profiles.forEach { profile ->
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(profile.id))
                check(sourcePreferences("source_${profile.id}").all.isEmpty())
                check(database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().none { it.source == profile.id })
                originalIds += profile.id
                sourcePreferences("source_${profile.id}").edit().putString("legacy_fixture_marker", "existing").commit()
            }
            connections.setProfiles(profiles)
            sources = AndroidSourceManager(context, EmptyStubs, connections, registry, scope)
            backup.backupManga.forEach {
                MangaRestorer().restore(
                    BackupManga(
                        it.source,
                        it.url,
                        title = it.title,
                        chapters = listOf(BackupChapter(it.chapters.single().url, "Existing", lastPageRead = 1)),
                    ),
                    emptyList(),
                )
            }
            BackupRestorer(
                context,
                BackupNotifier(context),
                false,
                categoriesRestorer = CategoriesRestorer(libraryPreferences = LibraryPreferences(store)),
                preferenceRestorer = PreferenceRestorer(
                    context,
                    preferenceStore = store,
                    komgaConnectionMigration = KomgaConnectionMigration(context, store, connections, json),
                    connectionPreferences = connections,
                    sharedConfigMigration = shared,
                ),
                sourceManager = sources,
                connectionProfileManager = profileManager,
            ).restore(Uri.fromFile(file), RestoreOptions(), password)
            assertFalse(ConnectionRestoreState.isRestoring)
            assertTrue(shared.isPending())
            assertEquals(777, store.getInt("connection_shared::pref_double_tap_anim_speed").get())
            val imported = connections.getProfiles().filter { it.id !in originalIds }
            assertEquals(profiles.size * 2, connections.getProfiles().size)
            assertTrue(connections.getProfiles().containsAll(profiles))
            assertEquals(imported.toSet(), shared.candidates().toSet())
            val selected = imported.single { it.providerId == "komga" }
            assertEquals(selected.id, shared.defaultConnectionId())
            val restored = profiles.map { old ->
                val target = imported.single { it.providerId == old.providerId }
                assertNotEquals(old.id, target.id)
                assertTrue(journal.isPaused(target.id))
                assertFalse(journal.isPaused(old.id))
                assertNotNull(sources.get(target.id))
                assertEquals("existing", sourcePreferences("source_${old.id}").getString("legacy_fixture_marker", null))
                val tag = if (version == 1) "v0.4.0" else "v0.6.0"
                assertEquals(
                    "$tag:${old.providerId}",
                    sourcePreferences("source_${target.id}").getString("legacy_fixture_marker", null),
                )
                val input = backup.backupManga.single { it.source == old.id }
                val before = getManga.await(input.url, old.id)!!
                val beforeChapter = database.chaptersQueries.getChaptersByMangaId(before.id, 0).awaitAsList().single()
                assertEquals(1L, beforeChapter.last_page_read)
                assertFalse(beforeChapter.read)
                // Independent expected identities, not the remapper under test.
                val expectedUrl = input.url.replace(old.id.toString(), target.id.toString())
                val manga = getManga.await(expectedUrl, target.id)!!
                assertEquals(input.title, manga.title)
                assertEquals("Frozen note", manga.notes)
                val chapter = database.chaptersQueries.getChaptersByMangaId(manga.id, 0).awaitAsList().single()
                assertEquals(input.chapters.single().url.replace(old.id.toString(), target.id.toString()), chapter.url)
                assertTrue(chapter.read && chapter.bookmark)
                assertEquals(8L, chapter.last_page_read)
                assertEquals(
                    400L,
                    database.historyQueries.getHistoryByMangaId(manga.id).awaitAsList().single().time_read,
                )
                assertEquals(
                    categoryName,
                    database.categoriesQueries.getCategoriesByMangaId(manga.id).awaitAsList().single().name,
                )
                val progress = database.epub_progressQueries.getByChapterId(chapter._id).awaitAsList()
                if (version == 2 && old.providerId in setOf("komga", "local-folder")) {
                    assertEquals(chapter.url, progress.single().book_url)
                    assertTrue(progress.single().locator_json.contains(chapter.url))
                    assertEquals(0.4, progress.single().progression!!, 0.0001)
                    assertEquals(
                        "Frozen bookmark",
                        database.epub_bookmarkQueries.getByMangaId(manga.id).awaitAsList().single().note,
                    )
                    assertEquals(
                        5L,
                        database.tts_progressQueries.getByMangaId(manga.id).awaitAsList().single().sentence_index,
                    )
                } else {
                    assertTrue(progress.isEmpty())
                }
                manga
            }
            val reopened = migration()
            assertTrue(reopened.isPending())
            assertEquals(selected.id, reopened.defaultConnectionId())
            val confirmed = AtomicBoolean(false)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SharedConfigSelection(reopened) { confirmed.set(true) } } }
                withTimeout(10_000) {
                    while (!confirmed.get()) {
                        var node =
                            find(
                                instrumentation.uiAutomation.rootInActiveWindow,
                                context.stringResource(MR.strings.action_ok),
                            )
                        while (node != null && !node.isClickable) node = node.parent
                        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(50)
                    }
                }
            }
            assertFalse(reopened.isPending())
            assertEquals(640, store.getInt("connection_shared::pref_double_tap_anim_speed").get())
            assertFalse(store.getBoolean("connection_shared::pref_show_page_number_key", true).get())
            assertEquals(imported.toSet() + profiles, connections.getProfiles().toSet())
            val exported = MangaBackupCreator()(restored, BackupOptions())
            val exportedCategories = database.categoriesQueries.getCategories().awaitAsList()
                .filter { it.name == categoryName }
                .map { BackupCategory(it.name, it.order, it.id, it.flags) }
            writeCurrentBackup(
                exportedFile,
                Backup(
                    exported,
                    backupCategories = exportedCategories,
                    backupConnections = imported.map { BackupConnection(it.id, it.providerId, it.name) },
                ),
            )
            // The current creator reads actual restored SQL data, then the decoder reads a current container.
            val roundtrip = decoder.decode(Uri.fromFile(exportedFile)).backupManga
            assertEquals(restored.map { it.source }.toSet(), roundtrip.map { it.source }.toSet())
            roundtrip.forEach { manga ->
                assertEquals(8L, manga.chapters.single().lastPageRead)
                assertTrue(manga.chapters.single().read)
                assertEquals(400L, manga.history.single().readDuration)
                assertEquals(1, manga.categories.size)
                val provider = imported.single { it.id == manga.source }.providerId
                if (version == 2 && provider in setOf("komga", "local-folder")) {
                    assertEquals("Frozen bookmark", manga.epubBookmarks.single().note)
                    assertEquals(manga.chapters.single().url, manga.epubProgress.single().bookUrl)
                }
            }
            assertTrue("Global inventory changed", globalInventory == Injekt.get<ConnectionPreferences>().getProfiles())
            assertTrue("Global preferences changed", globalPreferences == Injekt.get<PreferenceStore>().getAll())
        } finally {
            val ownedIds =
                originalIds + journal.reservedProfiles().map { it.id } + connections.getProfiles().map { it.id }
            sources?.withSourceUpdatesDeferred { connections.setProfiles(emptyList()) }
            scope.cancel()
            database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().filter { it.source in ownedIds }.forEach {
                getManga.await(it.url, it.source)?.let { manga -> database.mangasQueries.deleteMangaById(manga.id) }
            }
            database.categoriesQueries.getCategories().awaitAsList().filter { it.name == categoryName }.forEach {
                database.categoriesQueries.delete(it.id)
            }
            ownedIds.forEach {
                koharia.storage.NetworkStorageRuntime.invalidate(it)
                context.deleteSharedPreferences("source_$it")
            }
            context.deleteSharedPreferences(prefix)
            context.deleteSharedPreferences("$prefix-journal")
            file.delete()
            exportedFile.delete()
            password?.fill('\u0000')
        }
    }

    private fun writeCurrentBackup(file: File, backup: Backup) {
        val parser = Injekt.get<ProtoBuf>()
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(parser.encodeToByteArray(Backup.serializer(), backup)) }
        }.toByteArray()
        file.writeBytes(
            parser.encodeToByteArray(
                KohariaBackupEnvelope.serializer(),
                KohariaBackupEnvelope(
                    KohariaBackupEnvelope.MAGIC,
                    KohariaBackupEnvelope.FORMAT_VERSION,
                    KohariaBackupEnvelope.SCHEMA_VERSION,
                    1700000000000,
                    "app.koharia.dev.devicefixture",
                    sha256(compressed),
                    compressed,
                ),
            ),
        )
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }

    private object EmptyStubs : StubSourceRepository {
        override fun subscribeAll() = flowOf(emptyList<StubSource>())
        override suspend fun getStubSource(id: Long): StubSource? = null
        override suspend fun upsertStubSource(id: Long, lang: String, name: String) = Unit
    }
}
