package koharia.pdf

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
import koharia.epub.EpubPaginationPhase
import koharia.epub.EpubReaderActivity
import koharia.epub.EpubReaderFragment
import koharia.epub.EpubReaderLauncher
import koharia.epub.EpubReaderViewModel
import koharia.epub.service.EpubReaderSupportResolver
import koharia.epub.service.PdfReflowPublicationService
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.pdf.cache.PdfReflowArtifact
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
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
class NetworkPdfProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun originalPagesUseCapturedNetworkProgressAndLayoutDoesNotWrite() = exercise(conflict = false)

    @Test
    fun pendingOriginalPageRequiresActualRemoteChoice() = exercise(conflict = true)

    @Test
    fun offlineCachedReflowPreservesOriginalPageAndPendingAcrossReopen() = exercise(conflict = false, offline = true)

    private fun exercise(conflict: Boolean, offline: Boolean = false): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val progress = Injekt.get<EpubProgressRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        var artifact: PdfReflowArtifact? = null
        val shared = Injekt.get<SharedAppPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val root = File(context.cacheDir, "network-pdf-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val arguments = InstrumentationRegistry.getArguments()
        val mode = LibraryStorageMode.valueOf(arguments.getString("layoutStorageMode", "WEBDAV"))
        check(mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val base = checkNotNull(arguments.getString("layoutStorageAddress"))
        check(URI(base).host == "127.0.0.1")
        val username = arguments.getString("layoutStorageUser").orEmpty()
        val password = arguments.getString("layoutStoragePassword").orEmpty()
        fun checkpoint(phase: String): Int {
            val control = checkNotNull(arguments.getString("layoutControlAddress"))
            check(URI(control).host == "127.0.0.1")
            val request = URI("$control/checkpoint?mode=$mode&phase=$phase").toURL().openConnection()
                as java.net.HttpURLConnection
            return try {
                request.connectTimeout = 5_000
                request.readTimeout = 5_000
                check(request.responseCode == 200)
                request.inputStream.use { JSONObject(it.readBytes().decodeToString()).getInt("mutations") }
            } finally {
                request.disconnect()
            }
        }
        val parent = NetworkStorageRuntime.backend(NetworkStorageConfiguration(mode, base), base, username, password)
        val folder = "layout-${UUID.randomUUID()}"
        var createdRemote = false
        val profile = profiles.add("local-folder", "PDF progress ${UUID.randomUUID()}")
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
            val seed = File(root, "layout.pdf")
            seed.writeBytes(reflowTextPdfFixture())
            val bytes = seed.readBytes()
            parent.createDirectory(folder)
            createdRemote = true
            val address = base.trimEnd('/') + "/$folder/"
            val initial = NetworkStorageConfiguration(mode = mode, address = address)
            val remote = NetworkStorageRuntime.backend(initial, address, username, password)
            val rootIdentity = try {
                val identity = StorageIdentity.ensure(remote)
                remote.write("book.pdf", bytes.inputStream(), bytes.size.toLong())
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
            val entry = checkNotNull(runtime.cached("book.pdf"))
            val resource = runtime.identities.identity(entry)
            val complete = runtime.cache.prepareComplete(entry)
            assertTrue(bytes.contentEquals(complete.readBytes()))
            assertEquals(rootIdentity, runtime.session.rootIdentity)
            assertEquals(account, runtime.session.account)
            suspend fun remoteRecords(): List<StorageProgressRecord> {
                val files = try {
                    runtime.backend.list(".koharia/progress").filter { !it.directory }
                } catch (error: koharia.storage.StorageFailure) {
                    if (error.reason != koharia.storage.StorageFailure.Reason.NOT_FOUND) throw error
                    emptyList()
                }
                return files.map { file ->
                    check(file.size in 1..1_048_576)
                    Json.decodeFromString<StorageProgressRecord>(
                        runtime.backend.read(file, 0, file.size.toInt()).decodeToString(),
                    )
                }.onEach {
                    assertEquals(resource, it.resource)
                    assertEquals(entry.version, it.version)
                    assertNull(it.locator)
                    assertEquals(4, it.total)
                }
            }
            val prepared = PdfReflowPublicationService().prepare(source, manga, chapter, false)
            artifact = prepared
            awaitCondition("pdf-prepared") { prepared.manifest.isComplete }
            val manifest = prepared.manifest
            assertEquals(4, manifest.pageCount)
            val derivedBytes = prepared.epub.readBytes()
            val captured = checkNotNull(source.captureReflowProgressSession(chapter))
            val empty = checkNotNull(captured.adapter.pullPageProgress(chapter.url, chapter.memo))
            assertNull(empty.pageIndex)
            assertEquals(0, empty.totalPages)
            assertNull(runtime.progress.cached(resource))
            assertTrue(remoteRecords().isEmpty())
            val storage = Injekt.get<koharia.domain.storage.LibraryStorageRepository>()
            suspend fun pendingPayload() = checkNotNull(
                storage.get(profile.id, account, rootIdentity, "progress", resource),
            )
            val remoteSeed = if (conflict) {
                val remote = runtime.progress.record(resource, entry.version, 1_000_000_000_000L, 0, 4)
                runtime.progress.flush()
                assertEquals(remote, remoteRecords().single())
                runtime.progress.record(resource, entry.version, 999_999_999_000L, 1, 4)
                val locator = checkNotNull(koharia.pdf.reflow.PdfProgressMapper.initial(manifest, null, 1))
                progress.upsertProgress(
                    koharia.domain.epub.model.EpubProgress(
                        chapter.id,
                        manga.id,
                        null,
                        locator.toJSON().toString(),
                        0.0,
                        null,
                        java.util.Date(999_999_999_000L),
                        null,
                    ),
                )
                remote
            } else {
                null
            }
            val pendingBefore = if (conflict) pendingPayload() else null
            val mutationsBefore = if (conflict) checkpoint("choice-before") else null
            suspend fun verifyRemoteChoice(activity: EpubReaderActivity, model: EpubReaderViewModel) {
                awaitCondition("actual-pending-choice-visible") { model.state.value.remoteProgressConflict != null }
                assertEquals(pendingBefore, pendingPayload())
                assertTrue(Json.decodeFromString<StoragePendingProgress>(pendingPayload()).pending)
                assertNull(checkNotNull(progress.getProgress(chapter.id)).lastSyncedAt)
                assertEquals(remoteSeed, remoteRecords().single())
                assertEquals(mutationsBefore, checkpoint("choice-visible"))
                clickLabel(context.stringResource(tachiyomi.i18n.MR.strings.epub_reader_jump_remote_progress))
                awaitCondition("actual-remote-choice-accepted") {
                    model.state.value.remoteProgressConflict == null && !model.state.value.isResolvingRemoteProgress
                }
                awaitReady(model)
                assertEquals(
                    StoragePendingProgress(checkNotNull(remoteSeed), false),
                    Json.decodeFromString<StoragePendingProgress>(pendingPayload()),
                )
                val local = checkNotNull(progress.getProgress(chapter.id))
                assertEquals(java.util.Date(remoteSeed.modifiedAt), local.updatedAt)
                assertEquals(local.updatedAt, local.lastSyncedAt)
                val acceptedLocator = checkNotNull(Locator.fromJSON(JSONObject(local.locatorJson)))
                assertEquals(
                    0,
                    koharia.pdf.reflow.PdfProgressMapper.block(manifest, acceptedLocator, null)?.source?.page,
                )
                assertEquals(0, model.currentOriginalPdfPage())
                assertEquals(profile.id, model.state.value.sourceId)
                assertEquals(chapter.id, model.state.value.chapterId)
                assertEquals(remoteSeed, remoteRecords().single())
                assertEquals(mutationsBefore, checkpoint("choice-accepted"))
                assertTrue(bytes.contentEquals(complete.readBytes()))
                assertTrue(bytes.contentEquals(runtime.backend.read(entry, 0, bytes.size)))
                assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                status(
                    mode,
                    "actual-choice-confirmed-no-writes",
                    model.state.value.currentVisualPage,
                    model.state.value.totalVisualPages,
                    remoteSeed,
                )
                awaitCondition("reader-window-focused") { activity.hasWindowFocus() }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitCondition("reader-closed") { activity.isDestroyed }
            }
            suspend fun verifyOffline(activity: EpubReaderActivity, model: EpubReaderViewModel) {
                val before = checkNotNull(progress.getProgress(chapter.id))
                val beforePending = pendingPayload()
                val beforeRecord = checkNotNull(runtime.progress.cached(resource))
                assertEquals(1, beforeRecord.page)
                val totalBefore = checkNotNull(model.state.value.totalVisualPages)
                val mutationsBefore = checkpoint("fail")
                val generation = model.state.value.paginationGeneration
                withContext(Dispatchers.Main) {
                    EpubLayoutPreferences(model.readerSettingsStore(shared.store(), persistChanges = true))
                        .fontSize.set(2.0f)
                }
                awaitCondition("offline-layout-generation") {
                    model.state.value.paginationGeneration > generation
                }
                awaitReady(model)
                awaitCondition("offline-rendered-original-page") {
                    val fragment = activity.supportFragmentManager.fragments
                        .filterIsInstance<EpubReaderFragment>().single()
                    fragment.capturePdfSourceAnchor()
                    val frame = EpubReaderFragment::class.java.getDeclaredField("currentTransitionPageIndex")
                        .apply { isAccessible = true }.getInt(fragment)
                    model.currentOriginalPdfPage() == 1 && frame + 1 == model.state.value.currentVisualPage
                }
                withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                assertTrue(checkNotNull(model.state.value.totalVisualPages) > totalBefore)
                assertEquals(before.updatedAt, checkNotNull(progress.getProgress(chapter.id)).updatedAt)
                assertEquals(beforePending, pendingPayload())
                assertEquals(mutationsBefore, checkpoint("offline-layout"))
                navigateToOriginal(activity, model, 2)
                withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                withTimeout(45_000) {
                    while (Json.decodeFromString<StoragePendingProgress>(pendingPayload()).let {
                            it.pending && it.record.page == 2 && it.record.total == 4
                        }.not()
                    ) {
                        delay(100)
                    }
                }
                val pending = Json.decodeFromString<StoragePendingProgress>(pendingPayload())
                assertEquals(beforeRecord.device, pending.record.device)
                assertTrue(pending.record.sequence > beforeRecord.sequence)
                assertEquals(resource, pending.record.resource)
                assertEquals(entry.version, pending.record.version)
                val saved = checkNotNull(progress.getProgress(chapter.id))
                assertEquals(2, JSONObject(saved.locatorJson).getJSONObject("kohariaPdfSource").getInt("page"))
                assertEquals(2L, checkNotNull(chapters.getChapterById(chapter.id)).lastPageRead)
                assertEquals(saved.updatedAt.time, pending.record.modifiedAt)
                assertTrue(saved.updatedAt != saved.lastSyncedAt)
                assertTrue(saved.updatedAt > before.updatedAt)
                assertEquals(mutationsBefore, checkpoint("offline-pending"))
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "pdf_offline",
                            "$mode;layout-kept-original-1;next-original-2-pending;remote-unchanged",
                        )
                    },
                )
                awaitCondition("reader-window-focused") { activity.hasWindowFocus() }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitCondition("reader-closed") { activity.isDestroyed }
                awaitCondition("session-released") { Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null }
                val pendingBeforeReopen = pendingPayload()
                FixtureActivityLauncher.launch<EpubReaderActivity>(
                    EpubReaderActivity.newPdfReflowIntent(
                        context,
                        manga.id,
                        chapter.id,
                        source.id,
                        resolution = koharia.epub.service.EpubReaderSupportResolution(
                            manga.id,
                            chapter.id,
                            source.id,
                            pdfReflowRevision = manifest.revision,
                        ),
                    ),
                ).use { scenario ->
                    lateinit var reopenedActivity: EpubReaderActivity
                    lateinit var reopenedModel: EpubReaderViewModel
                    scenario.onActivity {
                        reopenedActivity = it
                        reopenedModel = ViewModelProvider(it)[EpubReaderViewModel::class.java]
                    }
                    awaitReady(reopenedModel)
                    var observed = ""
                    awaitCondition("offline-reopened-original-page-2") {
                        reopenedActivity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>()
                            .single().capturePdfSourceAnchor()
                        val state = "original=${reopenedModel.currentOriginalPdfPage()};" +
                            "visual=${reopenedModel.state.value.currentVisualPage};" +
                            "focus=${reopenedActivity.hasWindowFocus()}"
                        if (state != observed) {
                            observed = state
                            instrumentation.sendStatus(2, Bundle().apply { putString("offline_reopen_state", state) })
                        }
                        reopenedModel.currentOriginalPdfPage() == 2 && reopenedActivity.hasWindowFocus()
                    }
                    assertEquals(profile.id, reopenedModel.state.value.sourceId)
                    assertEquals(chapter.id, reopenedModel.state.value.chapterId)
                    assertEquals(pendingBeforeReopen, pendingPayload())
                    assertEquals(mutationsBefore, checkpoint("offline-reopened"))
                    assertTrue(bytes.contentEquals(complete.readBytes()))
                    assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString("pdf_offline", "$mode;reopened-cached-original-2;owner-pending-bytes-preserved")
                        },
                    )
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitCondition("offline-reopened-reader-closed") { reopenedActivity.isDestroyed }
                }
                awaitCondition("offline-session-released") {
                    Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null
                }
                assertEquals(pendingBeforeReopen, pendingPayload())
                assertEquals(mutationsBefore, checkpoint("offline-closed"))
            }
            FixtureActivityLauncher.launch<EpubReaderActivity>(
                EpubReaderActivity.newPdfReflowIntent(
                    context,
                    manga.id,
                    chapter.id,
                    source.id,
                    0,
                    koharia.epub.service.EpubReaderSupportResolution(
                        manga.id,
                        chapter.id,
                        source.id,
                        pdfReflowRevision = manifest.revision,
                    ),
                ),
            ).use reader@{ scenario ->
                lateinit var activity: EpubReaderActivity
                lateinit var model: EpubReaderViewModel
                scenario.onActivity {
                    activity = it
                    model = ViewModelProvider(it)[EpubReaderViewModel::class.java]
                }
                awaitReady(model)
                if (conflict) {
                    verifyRemoteChoice(activity, model)
                    return@reader
                }
                delay(300)
                assertNull(runtime.progress.cached(resource))
                assertTrue(remoteRecords().isEmpty())
                assertEquals(0, model.currentOriginalPdfPage())
                suspend fun saveAndConfirm(page: Int): StorageProgressRecord {
                    withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                    withTimeout(45_000) {
                        while (runtime.progress.cached(resource)?.let {
                                it.page == page && runtime.progress.isPageConfirmed(
                                    resource,
                                    entry.version,
                                    page,
                                    4,
                                    it.modifiedAt,
                                )
                            } != true
                        ) {
                            delay(100)
                        }
                    }
                    withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                    val stored = checkNotNull(progress.getProgress(chapter.id))
                    val mapped = JSONObject(stored.locatorJson).getJSONObject("kohariaPdfSource")
                    assertEquals(page, mapped.getInt("page"))
                    assertEquals(manifest.sourceHash, mapped.getString("sourceHash"))
                    assertEquals(page.toLong(), checkNotNull(chapters.getChapterById(chapter.id)).lastPageRead)
                    val remoteRecord = remoteRecords().single()
                    assertEquals(page, remoteRecord.page)
                    assertEquals(stored.updatedAt.time, remoteRecord.modifiedAt)
                    assertEquals(stored.updatedAt, stored.lastSyncedAt)
                    assertTrue(captured.adapter.isPageProgressConfirmed(chapter.url, page, 4, remoteRecord.modifiedAt))
                    status(
                        mode,
                        "confirmed",
                        model.state.value.currentVisualPage,
                        model.state.value.totalVisualPages,
                        remoteRecord,
                    )
                    return remoteRecord
                }
                navigateToOriginal(activity, model, 1)
                val before = saveAndConfirm(1)
                if (offline) {
                    verifyOffline(activity, model)
                    return@reader
                }
                val layoutBefore = model.state.value
                assertTrue(checkNotNull(layoutBefore.totalVisualPages) > manifest.pageCount)
                withContext(Dispatchers.Main) {
                    EpubLayoutPreferences(model.readerSettingsStore(shared.store(), persistChanges = true))
                        .fontSize.set(2.0f)
                }
                awaitCondition("new-layout-generation") {
                    model.state.value.paginationGeneration > layoutBefore.paginationGeneration
                }
                awaitReady(model)
                assertEquals(1, model.currentOriginalPdfPage())
                val after = saveAndConfirm(1)
                assertEquals(before, after)
                assertTrue(
                    checkNotNull(model.state.value.totalVisualPages) > checkNotNull(layoutBefore.totalVisualPages),
                )
                navigateToOriginal(activity, model, 2)
                val finalRecord = saveAndConfirm(2)
                assertTrue(finalRecord.sequence > before.sequence)
                assertEquals(before.device, finalRecord.device)
                assertTrue(bytes.contentEquals(complete.readBytes()))
                assertTrue(bytes.contentEquals(runtime.backend.read(entry, 0, bytes.size)))
                assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                assertEquals(profile.id, model.state.value.sourceId)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitCondition("reader-closed") { activity.isDestroyed }
            }
            awaitCondition("session-released") { Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null }
            if (conflict) {
                assertEquals(remoteSeed, remoteRecords().single())
                assertEquals(mutationsBefore, checkpoint("choice-closed"))
            } else if (!offline) {
                val adapter = captured.adapter
                val beforeChoice = checkNotNull(adapter.reviewPageProgress(chapter.url, chapter.memo))
                val remoteBefore = remoteRecords().single()
                adapter.acceptRemotePageProgress(chapter.url, 2, 4, remoteBefore.modifiedAt)
                assertEquals(remoteBefore, remoteRecords().single())
                assertEquals(remoteBefore, runtime.progress.cached(resource))
                adapter.reviewPageProgress(chapter.url, chapter.memo)
                adapter.recordLocalPageProgress(chapter.url, 1, 4, remoteBefore.modifiedAt + 1)
                val newer = runtime.progress.cached(resource)
                assertTrue(
                    runCatching {
                        adapter.acceptRemotePageProgress(chapter.url, 2, 4, remoteBefore.modifiedAt)
                    }.isFailure,
                )
                assertEquals(newer, runtime.progress.cached(resource))
                assertEquals(remoteBefore, remoteRecords().single())
                adapter.reviewPageProgress(chapter.url, chapter.memo)
                adapter.confirmLocalPageProgress(chapter.url, 1, 4, remoteBefore.modifiedAt + 2)
                adapter.pushPageProgress(chapter.url, 1, 4)
                assertTrue(adapter.isPageProgressConfirmed(chapter.url, 1, 4, remoteBefore.modifiedAt + 2))
                assertEquals(2, beforeChoice.pageIndex)
                val originalState = runtime.progress.cached(resource)
                NetworkStoragePreferences(profile.id).save(config.copy(persistentIdentity = false), username, password)
                assertNull(source.captureReflowProgressSession(chapter))
                NetworkStoragePreferences(profile.id).save(config, username, password + "-changed")
                assertTrue(runCatching { adapter.recordLocalPageProgress(chapter.url, 2, 4, 100) }.isFailure)
                val persisted = Injekt.get<koharia.domain.storage.LibraryStorageRepository>()
                    .get(profile.id, account, rootIdentity, "progress", resource)
                assertEquals(
                    originalState,
                    Json.decodeFromString<StoragePendingProgress>(checkNotNull(persisted)).record,
                )
            }
        } finally {
            try {
                profiles.remove(profile.id).getOrThrow()
                mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
                artifact?.progressive?.stopAndJoin()
                val ownedCache = File(context.cacheDir, "pdf-reflow/${profile.id}").canonicalFile
                check(ownedCache.parentFile == File(context.cacheDir, "pdf-reflow").canonicalFile)
                check(!ownedCache.exists() || ownedCache.deleteRecursively())
            } finally {
                try {
                    if (offline) checkpoint("allow")
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
                    connections.flush()
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

    private suspend fun navigateToOriginal(activity: EpubReaderActivity, model: EpubReaderViewModel, page: Int) {
        repeat(12) {
            if (model.currentOriginalPdfPage() == page) return
            val previous = model.state.value.currentVisualPage
            awaitCondition("focused") { activity.hasWindowFocus() }
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                instrumentation.sendKeySync(
                    KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON),
                )
            }
            awaitCondition("next-visual-page") { model.state.value.currentVisualPage != previous }
            withContext(Dispatchers.Main) {
                activity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>()
                    .single().capturePdfSourceAnchor()
            }
        }
        assertEquals(page, model.currentOriginalPdfPage())
    }

    private suspend fun clickLabel(label: String) = withTimeout(10_000) {
        fun find(
            node: android.view.accessibility.AccessibilityNodeInfo?,
        ): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) break
            delay(100)
        }
    }

    private suspend fun awaitReady(model: EpubReaderViewModel) = awaitCondition("pagination-ready") {
        val state = model.state.value
        check(state.errorMessage == null) { state.errorMessage.orEmpty() }
        state.isReady && model.currentLocator() != null &&
            state.paginationPhase in setOf(EpubPaginationPhase.READY, EpubPaginationPhase.CACHED) &&
            (state.totalVisualPages ?: 0) > 0
    }

    private suspend fun awaitCondition(phase: String, condition: suspend () -> Boolean) {
        instrumentation.sendStatus(2, Bundle().apply { putString("layout_wait", phase) })
        withTimeout(45_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(100)
        }
    }

    private fun status(
        mode: LibraryStorageMode,
        phase: String,
        page: Int?,
        total: Int?,
        record: StorageProgressRecord,
    ) {
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "network_pdf_progress",
                    "$mode;$phase;original=${record.page}/${record.total};visual=$page/$total;" +
                        "sequence=${record.sequence};confirmed=true",
                )
            },
        )
    }
}
