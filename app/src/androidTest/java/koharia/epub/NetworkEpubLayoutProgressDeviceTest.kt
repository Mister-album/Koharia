package koharia.epub

import android.os.Bundle
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.epub.service.EpubReaderSupportResolver
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryEntryOpenManager
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.source.local.defaultBookshelfId
import koharia.source.local.withInitialBookshelves
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StorageIdentity
import koharia.storage.StoragePendingProgress
import koharia.storage.StorageProgressRecord
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NetworkEpubLayoutProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fontChangeKeepsRemoteResourceLocatorIndependentOfVisualPages(): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val progress = Injekt.get<EpubProgressRepository>()
        val shared = Injekt.get<SharedAppPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val root = File(context.cacheDir, "network-layout-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val arguments = InstrumentationRegistry.getArguments()
        val mode = LibraryStorageMode.valueOf(arguments.getString("layoutStorageMode", "WEBDAV"))
        check(mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val base = checkNotNull(arguments.getString("layoutStorageAddress"))
        check(URI(base).host == "127.0.0.1")
        val username = arguments.getString("layoutStorageUser").orEmpty()
        val password = arguments.getString("layoutStoragePassword").orEmpty()
        val parent = NetworkStorageRuntime.backend(NetworkStorageConfiguration(mode, base), base, username, password)
        val folder = "layout-${UUID.randomUUID()}"
        var createdRemote = false
        val profile = profiles.add("local-folder", "Layout progress ${UUID.randomUUID()}")
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putLong("fixture_owned_profile", profile.id)
                putString("fixture_owned_root", root.name)
                putLong("fixture_previous_active", connections.activeConnectionId.get())
            },
        )
        try {
            replace(connections.activeConnectionId, ConnectionPreferences.LOCAL_CONNECTION_ID)
            replace(Injekt.get<eu.kanade.domain.base.BasePreferences>().shownOnboardingFlow, true)
            replace(shared.basePreferences().downloadedOnly, false)
            replace(shared.basePreferences().incognitoMode, false)
            replace(shared.epubReaderPreferences().syncRemoteProgression, true)
            replace(shared.epubReaderPreferences().correctRemoteServerTimestamps, false)
            replace(shared.epubReaderPreferences().persistReaderSettingsChanges, true)
            replace(shared.epubReaderPreferences().completionThresholdPercent, 100)
            replace(shared.downloadPreferences().removeAfterReadSlots, -1)
            val layout = shared.epubLayoutPreferences()
            replace(layout.readingMode, EpubLayoutPreferences.ReadingMode.PAGINATED)
            replace(layout.pageDirection, EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT)
            replace(layout.fontSize, 1.0f)
            replace(layout.publisherStyles, false)
            replace(
                layout.pageTransitionEffect,
                eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect.NONE.value,
            )
            val seed = File(root, "layout.epub")
            writeEpub(seed)
            val bytes = seed.readBytes()
            parent.createDirectory(folder)
            createdRemote = true
            val address = base.trimEnd('/') + "/$folder/"
            val initial = NetworkStorageConfiguration(mode = mode, address = address)
            val remote = NetworkStorageRuntime.backend(initial, address, username, password)
            val rootIdentity = try {
                val identity = StorageIdentity.ensure(remote)
                remote.write("book.epub", bytes.inputStream(), bytes.size.toLong())
                identity
            } finally {
                remote.close()
            }
            val config = initial.copy(rootIdentity = rootIdentity)
            val account = NetworkStoragePreferences.account(config, username)
            val uri = DocumentsContract.buildDocumentUri(
                NetworkStorageRuntime.authority(context),
                "${profile.id}|$account|",
            )
            val library = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            profiles.commitConfiguration(profile.id) { draft ->
                NetworkStoragePreferences(profile.id, draft).save(config, username, password)
                LocalLibraryPreferences(profile.id, Json, draft).setConfig(
                    library.copy(
                        setupCompleted = true,
                        roots = listOf(
                            LocalLibraryRootConfig(
                                id = "book-root",
                                treeUri = uri.toString(),
                                contentType = LocalLibraryContentType.BOOKS,
                                bookshelfId = library.defaultBookshelfId(LocalLibraryContentType.BOOKS),
                            ),
                        ),
                    ),
                )
            }
            val source = Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource
            source.refreshLibrary().getOrThrow()
            val manga = mangas.getMangaBySourceId(profile.id).single()
            val chapter = LocalLibraryEntryOpenManager(Injekt.get(), Injekt.get(), EpubReaderLauncher())
                .prepareChapter(source, manga)
            val runtime = NetworkStorageRuntime.get(context, profile.id)
            val entry = checkNotNull(runtime.cached("book.epub"))
            val resource = runtime.identities.identity(entry)
            val complete = runtime.cache.prepareComplete(entry)
            assertTrue(bytes.contentEquals(complete.readBytes()))
            assertEquals(rootIdentity, runtime.session.rootIdentity)
            assertEquals(account, runtime.session.account)
            suspend fun remoteProgress(): StorageProgressRecord {
                val files = runtime.backend.list(".koharia/progress").filter { !it.directory }
                val records = files.map { file ->
                    check(file.size in 1..1_048_576)
                    val encoded = runtime.backend.read(file, 0, file.size.toInt()).decodeToString()
                    Json.decodeFromString<StorageProgressRecord>(encoded)
                }
                assertEquals(1, records.size)
                return records.single().also {
                    assertEquals(resource, it.resource)
                    assertEquals(entry.version, it.version)
                    assertEquals(null, it.page)
                    assertEquals(0, it.total)
                    assertTrue(!it.completed && it.locator != null)
                }
            }
            val resolution = EpubReaderSupportResolver().resolve(manga.id, chapter.id)
            assertTrue(resolution.isNativeSupported)
            assertEquals(chapter.url, resolution.remoteBookUrl)
            assertTrue(source.localChapterFile(chapter.url) is com.hippo.unifile.RemoteStorageFile)
            FixtureActivityLauncher.launch<EpubReaderActivity>(
                EpubReaderActivity.newIntent(context, manga.id, chapter.id, source.id, resolution),
            ).use { scenario ->
                lateinit var activity: EpubReaderActivity
                lateinit var model: EpubReaderViewModel
                scenario.onActivity {
                    activity = it
                    model = ViewModelProvider(it)[EpubReaderViewModel::class.java]
                }
                awaitReady(model)
                withContext(Dispatchers.Main) {
                    val session = checkNotNull(Injekt.get<EpubReaderSessionRepository>().get(chapter.id))
                    val publication = session.publication
                    val target = checkNotNull(publication.locatorFromLink(publication.readingOrder[1]))
                        .copy(locations = Locator.Locations(progression = 0.0))
                    assertTrue(
                        activity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>()
                            .single().goTo(target),
                    )
                }
                awaitCondition("second-resource") { model.currentLocator()?.href?.toString() == "s2.xhtml" }
                suspend fun saveAndConfirm(): StorageProgressRecord {
                    withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                    val stored = checkNotNull(progress.getProgress(chapter.id))
                    val pendingState = runtime.records.get<StoragePendingProgress>("progress", resource)
                    fun field(name: String): Any? = EpubReaderViewModel::class.java.getDeclaredField(name)
                        .apply { isAccessible = true }.get(model)
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString(
                                "layout_save_state",
                                "bookOwner=${stored.bookUrl == chapter.url};pending=${pendingState?.pending};" +
                                    "writeAllowed=${field("remoteProgressWriteAllowed")};" +
                                    "readingRevision=${field("localReadingRevision")};" +
                                    "recordedRevision=${field("recordedReadingRevision")};" +
                                    "authoritative=${Injekt.get<EpubReaderSessionRepository>().get(chapter.id)
                                        ?.positionsController?.hasAuthoritativePositions}",
                            )
                        },
                    )
                    assertEquals(stored.updatedAt, stored.lastSyncedAt)
                    val pending = checkNotNull(runtime.records.get<StoragePendingProgress>("progress", resource))
                    assertTrue(!pending.pending && !pending.requiresConfirmation)
                    return remoteProgress().also { assertEquals(pending.record, it) }
                }
                val beforeRemote = saveAndConfirm()
                val originalLocator = JSONObject(checkNotNull(beforeRemote.locator))
                awaitReady(model)
                val before = model.state.value
                status(mode, "before", before.currentVisualPage, before.totalVisualPages, beforeRemote)
                withContext(Dispatchers.Main) {
                    EpubLayoutPreferences(model.readerSettingsStore(shared.store(), persistChanges = true))
                        .fontSize.set(2.0f)
                }
                awaitCondition("new-layout-generation") {
                    model.state.value.paginationGeneration > before.paginationGeneration
                }
                awaitReady(model)
                val afterRemote = saveAndConfirm()
                val after = model.state.value
                assertTrue(checkNotNull(after.totalVisualPages) > checkNotNull(before.totalVisualPages))
                assertLocationEquals(originalLocator, JSONObject(checkNotNull(afterRemote.locator)))
                assertEquals(beforeRemote, afterRemote)
                status(mode, "layout-kept-owned-locator", after.currentVisualPage, after.totalVisualPages, afterRemote)
                val previous = model.currentLocator()?.locations?.progression
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    instrumentation.sendKeySync(
                        KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON),
                    )
                }
                awaitCondition("forward-after-layout") {
                    model.currentLocator()?.href?.toString() == "s2.xhtml" &&
                        model.currentLocator()?.locations?.progression != previous
                }
                val finalRemote = saveAndConfirm()
                val finalLocator = JSONObject(checkNotNull(finalRemote.locator))
                assertTrue(finalRemote.sequence > beforeRemote.sequence)
                assertEquals(beforeRemote.device, finalRemote.device)
                assertTrue(
                    finalLocator.getJSONObject("locations").getDouble("progression") >
                        originalLocator.getJSONObject("locations").getDouble("progression"),
                )
                assertLocationEquals(checkNotNull(model.currentLocator()).toJSON(), finalLocator)
                assertEquals(chapter.id, model.state.value.chapterId)
                assertEquals(profile.id, model.state.value.sourceId)
                assertTrue(bytes.contentEquals(complete.readBytes()))
                assertTrue(bytes.contentEquals(runtime.backend.read(entry, 0, bytes.size)))
                status(
                    mode,
                    "forward-confirmed-owned-locator",
                    model.state.value.currentVisualPage,
                    after.totalVisualPages,
                    finalRemote,
                )
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitCondition("reader-closed") { activity.isDestroyed }
            }
            awaitCondition("session-released") { Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null }
        } finally {
            try {
                profiles.remove(profile.id).getOrThrow()
                mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
            } finally {
                try {
                    if (createdRemote) {
                        suspend fun removeOwned(path: String) {
                            check(path == folder || path.startsWith("$folder/"))
                            parent.list(path).forEach { child ->
                                check(child.path.startsWith("$folder/"))
                                if (child.directory) removeOwned(child.path) else parent.delete(child)
                            }
                            parent.delete(parent.stat(path))
                        }
                        removeOwned(folder)
                    }
                } finally {
                    parent.close()
                    restores.asReversed().forEach { it() }
                    check(root.parentFile == context.cacheDir.canonicalFile && root.deleteRecursively())
                }
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "layout_cleanup",
                        "$mode;owned-profile-cache-local-and-remote-root-removed;preferences-restored",
                    )
                },
            )
        }
    }

    private suspend fun awaitReady(model: EpubReaderViewModel) = awaitCondition("pagination-ready") {
        val state = model.state.value
        check(state.errorMessage == null) { state.errorMessage.orEmpty() }
        state.isReady && model.currentLocator() != null &&
            state.paginationPhase in setOf(EpubPaginationPhase.READY, EpubPaginationPhase.CACHED) &&
            (state.totalVisualPages ?: 0) > 0
    }

    private suspend fun awaitCondition(phase: String, condition: () -> Boolean) {
        instrumentation.sendStatus(2, Bundle().apply { putString("layout_wait", phase) })
        withTimeout(45_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(100)
        }
    }

    private fun assertLocationEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.getString("href"), actual.getString("href"))
        val first = expected.getJSONObject("locations")
        val second = actual.getJSONObject("locations")
        for (name in listOf("position", "progression", "totalProgression")) {
            assertEquals(first.getDouble(name), second.getDouble(name), 0.000001)
        }
    }

    private fun status(
        mode: LibraryStorageMode,
        phase: String,
        page: Int?,
        total: Int?,
        record: StorageProgressRecord,
    ) {
        val locator = JSONObject(checkNotNull(record.locator))
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "layout_progress",
                    "$mode;$phase;visual=$page/$total;sequence=${record.sequence};" +
                        "href=${locator.getString("href")};" +
                        "progression=${locator.getJSONObject("locations").getDouble("progression")}",
                )
            },
        )
    }

    private fun writeEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.encodeToByteArray()
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
                """.trimIndent(),
            )
            val manifest = (1..3).joinToString("") {
                """<item id="s$it" href="s$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = (1..3).joinToString("") { """<itemref idref="s$it"/>""" }
            entry(
                "book.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">layout-fixture</dc:identifier>
                <dc:title>Layout book</dc:title><dc:language>en</dc:language></metadata>
                <manifest>$manifest</manifest><spine>$spine</spine></package>
                """.trimIndent(),
            )
            for (section in 1..3) {
                val text = (1..40).joinToString("") { paragraph ->
                    "<p id='p$paragraph'>Section $section paragraph $paragraph. " +
                        (
                            "The reader keeps a stable publication location " +
                                "while typography changes the visual page count. "
                            ).repeat(5) + "</p>"
                }
                entry(
                    "s$section.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Section $section</title></head>
                    <body>$text</body></html>
                    """.trimIndent(),
                )
            }
        }
    }
}
