package koharia.connection

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import koharia.source.local.LocalBookshelf
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryOrganizationMode
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeLocalActiveRestoreDeviceTest {
    @Test
    fun restoredRootsRetireOldOwnerAndKeepEachLibraryIsolated(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "native-active-restore-").toFile().canonicalFile
        val prefix = "Native active restore ${System.nanoTime()}"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val manga = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val inventory = profiles.profiles()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val owned = mutableSetOf<Long>()
        val backup = File(root, "native.kohariabk")
        suspend fun source(id: Long): LocalFolderSource = withTimeout(10_000) {
            while (sources.get(id) !is LocalFolderSource) delay(25)
            sources.get(id) as LocalFolderSource
        }
        val dirs = listOf("old", "new", "third").associateWith { role ->
            File(root, role).apply { mkdirs() }.also { directory ->
                val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
                val png = ByteArrayOutputStream().apply {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
                }.toByteArray()
                bitmap.recycle()
                ZipOutputStream(File(directory, "$role.cbz").outputStream()).use {
                    it.putNextEntry(ZipEntry("001.png"))
                    it.write(png)
                    it.closeEntry()
                }
            }
        }
        val bytes = dirs.mapValues { (role, dir) -> File(dir, "$role.cbz").readBytes() }
        fun config(uri: String) = LocalLibraryConfig(
            libraryId = UUID.randomUUID().toString(),
            setupCompleted = true,
            bookshelves = listOf(
                LocalBookshelf(
                    "folders",
                    "Folders",
                    LocalLibraryContentType.COMICS,
                    LocalLibraryOrganizationMode.FOLDER,
                ),
            ),
            roots = listOf(
                LocalLibraryRootConfig(
                    "root",
                    uri,
                    contentType = LocalLibraryContentType.COMICS,
                    bookshelfId = "folders",
                ),
            ),
        )
        fun settings(id: Long, value: LocalLibraryConfig) = BackupSourcePreferences(
            "source_$id",
            listOf(BackupPreference("local_library_config", StringPreferenceValue(Json.encodeToString(value)))),
        )
        try {
            val old = profiles.add("local-folder", prefix)
            owned += old.id
            profiles.commitConfiguration(old.id) { draft ->
                LocalLibraryPreferences(old.id, Json, draft).setConfig(config(dirs.getValue("old").toURI().toString()))
            }
            val oldSource = source(old.id)
            oldSource.refreshLibrary().getOrThrow()
            val oldBook = oldSource.browseIndexedLibrary("").single()
            assertEquals("old", oldBook.title)
            Injekt.get<SyncChaptersWithSource>().fetchAndSync(oldBook, oldSource, ChapterFetchOrigin.OPEN)
            val oldChapter = chapters.getChapterByMangaId(oldBook.id).single()
            chapters.update(ChapterUpdate(id = oldChapter.id, read = true, lastPageRead = 1))
            val chapterBefore = chapters.getChapterByMangaId(oldBook.id)
            val mangaBefore = manga.getMangaBySourceId(old.id)
            val localPreferences = LocalLibraryPreferences(old.id, Json)
            val configBefore = localPreferences.getConfig()
            val indexBefore = localPreferences.getIndex()
            val owner = oldSource.captureChapterOwner()
            owner.checkCurrent()
            val unused = System.currentTimeMillis() + 100_000
            check(!preferences.isKnownConnectionId(unused))
            val incoming = config("file:///owned-backup-root/new")
            val additional = config("file:///owned-backup-root/third")
            writeBackup(
                backup,
                Backup(
                    emptyList(),
                    backupConnections = listOf(
                        BackupConnection(old.id, "local-folder", prefix),
                        BackupConnection(unused, "local-folder", "$prefix added"),
                    ),
                    backupSourcePreferences = listOf(settings(old.id, incoming), settings(unused, additional)),
                ),
            )
            BackupRestorer(context, BackupNotifier(context), false).restore(
                Uri.fromFile(backup),
                RestoreOptions(libraryEntries = false, categories = false, appSettings = false),
                directoryBindings = mapOf(
                    incoming.roots.single().treeUri to dirs.getValue("new").toURI().toString(),
                    additional.roots.single().treeUri to dirs.getValue("third").toURI().toString(),
                ),
            )
            val imported = profiles.profiles().filter { it.id != old.id && it.name.startsWith(prefix) }
            owned += imported.map { it.id }
            assertEquals(2, imported.size)
            assertTrue(imported.none { it.id == unused })
            assertNotSame(oldSource, source(old.id))
            assertTrue(runCatching { owner.checkCurrent() }.exceptionOrNull() is CancellationException)
            assertEquals(configBefore, localPreferences.getConfig())
            assertEquals(indexBefore, localPreferences.getIndex())
            assertEquals(mangaBefore, manga.getMangaBySourceId(old.id))
            assertEquals(chapterBefore, chapters.getChapterByMangaId(oldBook.id))
            val identities = mutableSetOf<String>()
            for (profile in listOf(old) + imported) {
                val current = source(profile.id)
                val currentConfig = LocalLibraryPreferences(profile.id, Json).getConfig()
                val role = when (currentConfig.libraryId) {
                    configBefore.libraryId -> "old"
                    incoming.libraryId -> "new"
                    additional.libraryId -> "third"
                    else -> error("Unexpected library identity")
                }
                assertTrue(identities.add(currentConfig.libraryId))
                assertEquals(dirs.getValue(role).toURI().toString(), currentConfig.roots.single().treeUri)
                current.captureChapterOwner().checkCurrent()
                current.refreshLibrary().getOrThrow()
                val book = current.browseIndexedLibrary("").single()
                assertEquals(role, book.title)
                assertEquals(profile.id, book.source)
                val unit = current.getChapterList(book.toSManga()).single()
                val file = checkNotNull(current.prepareChapterFile(unit.url))
                val actual = context.contentResolver.openInputStream(file.uri)!!.use { it.readBytes() }
                assertTrue(bytes.getValue(role).contentEquals(actual))
                assertEquals(listOf(role), manga.getMangaBySourceId(profile.id).map { it.title })
            }
            assertEquals(3, identities.size)
            assertEquals(chapterBefore, chapters.getChapterByMangaId(oldBook.id))
            dirs.forEach { (role, directory) ->
                assertTrue(bytes.getValue(role).contentEquals(File(directory, "$role.cbz").readBytes()))
            }
            assertTrue(profiles.profiles().containsAll(inventory))
            assertFalse(ConnectionRestoreState.isRestoring)
        } finally {
            profiles.profiles().filter { it.name.startsWith(prefix) }.forEach { owned += it.id }
            for (id in owned) {
                check(profiles.profiles().singleOrNull { it.id == id }?.name?.startsWith(prefix) != false)
                profiles.remove(id).getOrThrow()
            }
            if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
            check(root.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
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
}
