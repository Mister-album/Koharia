package koharia.epub

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.epub.interactor.GetEpubBookmarks
import koharia.domain.epub.model.EpubBookmark
import koharia.domain.epub.repository.EpubBookmarkRepository
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.epub.session.EpubReaderSessionRepository
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryEntryOpenManager
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.source.local.defaultBookshelfId
import koharia.source.local.withInitialBookshelves
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubProgressReaderDeviceTest {
    @Test
    fun readerSavesProgressButDoesNotUndoAnExternalReset(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val active = connections.activeConnectionId.get()
        val profile = profiles.add("local-folder", "EPUB progress fixture")
        val sources = Injekt.get<SourceManager>()
        val mangas = Injekt.get<MangaRepository>()
        val progress = Injekt.get<EpubProgressRepository>()
        val directory = File(context.cacheDir, "epub-progress-${UUID.randomUUID()}").apply { mkdirs() }
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val old = preference.get()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        try {
            val shared = Injekt.get<SharedAppPreferences>()
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(shared.basePreferences().incognitoMode, false)
            replace(shared.epubReaderPreferences().syncRemoteProgression, false)
            writeBook(File(directory, "book.epub"))
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            LocalLibraryPreferences(profile.id, Json).setConfig(
                config.copy(
                    setupCompleted = true,
                    roots = listOf(
                        LocalLibraryRootConfig(
                            id = UUID.randomUUID().toString(),
                            treeUri = directory.toURI().toString(),
                            contentType = LocalLibraryContentType.BOOKS,
                            bookshelfId = config.defaultBookshelfId(LocalLibraryContentType.BOOKS),
                        ),
                    ),
                ),
            )
            withTimeout(10_000) { while (sources.get(profile.id) == null) delay(25) }
            val source = sources.get(profile.id) as LocalFolderSource
            source.refreshLibrary().getOrThrow()
            val manga = mangas.getMangaBySourceId(profile.id).single()
            val chapter = LocalLibraryEntryOpenManager(Injekt.get(), Injekt.get(), EpubReaderLauncher())
                .prepareChapter(source, manga)
            InitializationEnd.entries.forEach { end ->
                verifyOverlappingInitialization(manga.id, chapter.id, source.id, end)
            }
            repeat(2) { pass ->
                FixtureActivityLauncher.launch<EpubReaderActivity>(
                    EpubReaderLauncher().resolveIntent(context, manga.id, chapter.id),
                ).use { scenario ->
                    lateinit var model: EpubReaderViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[EpubReaderViewModel::class.java] }
                    withTimeout(25_000) {
                        while (!model.state.value.isReady || model.currentLocator() == null) {
                            check(model.state.value.errorMessage == null) {
                                checkNotNull(model.state.value.errorMessage)
                            }
                            delay(50)
                        }
                    }
                    val openedToken = model.state.value.sessionToken
                    scenario.onActivity { activity ->
                        assertTrue(activity.isCurrentReaderSession(chapter.id, source.id, openedToken))
                        assertFalse(activity.isCurrentReaderSession(chapter.id + 1, source.id, openedToken))
                        assertFalse(activity.isCurrentReaderSession(chapter.id, source.id + 1, openedToken))
                        assertFalse(activity.isCurrentReaderSession(chapter.id, source.id, openedToken - 1))
                    }
                    if (pass == 0) {
                        withContext(Dispatchers.Main) {
                            model.init(manga.id, chapter.id, preserveLocalProgressAfterLayoutChange = true).getOrThrow()
                        }
                        scenario.onActivity { activity ->
                            assertFalse(activity.isCurrentReaderSession(chapter.id, source.id, openedToken))
                            assertTrue(
                                activity.isCurrentReaderSession(
                                    chapter.id,
                                    source.id,
                                    model.state.value.sessionToken,
                                ),
                            )
                        }
                    }
                    withContext(Dispatchers.Main) { model.onFirstContentDisplayed() }
                    val positions = EpubReaderViewModel::class.java.getDeclaredMethod(
                        "authoritativePublicationPositions",
                    )
                        .apply { isAccessible = true }
                    withTimeout(10_000) {
                        while (withContext(Dispatchers.Main) { positions.invoke(model) == null }) delay(25)
                    }
                    withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                    var saved = checkNotNull(progress.getProgress(chapter.id))
                    assertEquals(manga.id, saved.mangaId)
                    if (pass == 0) {
                        verifyUploadWaitsForAcknowledgement(model, progress, saved)
                        verifyRemoteChoiceRejectsChangedRemote(model, progress, saved)
                        saved = verifyRemoteChoiceWaitsForCommit(model, progress, saved)
                        val newer = saved.copy(updatedAt = Date(saved.updatedAt.time + 30_000))
                        progress.upsertProgress(newer)
                        verifyRemoteChoiceRejectsChangedRow(model, progress, newer)
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        assertEquals(newer, progress.getProgress(chapter.id))
                        progress.deleteProgress(chapter.id)
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        assertNull(progress.getProgress(chapter.id))
                    } else {
                        assertNotNull(saved.locatorJson)
                        val localPreferences = LocalLibraryPreferences(profile.id, Json)
                        val openedConfig = localPreferences.getConfig()
                        localPreferences.setConfig(
                            openedConfig.copy(roots = openedConfig.roots.map { it.copy(relativePath = "replacement") }),
                        )
                        val rejected = runCatching { withContext(Dispatchers.Main) { model.saveCurrentProgress() } }
                        assertTrue(rejected.exceptionOrNull() is CancellationException)
                        assertEquals(saved, progress.getProgress(chapter.id))
                    }
                }
                // releaseSession saves asynchronously; the old reader must not resurrect the reset row.
                delay(1_000)
                if (pass == 0) assertNull(progress.getProgress(chapter.id))
            }
        } finally {
            try {
                profiles.remove(profile.id).getOrThrow()
                mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
            } finally {
                connections.activeConnectionId.set(active)
                restores.asReversed().forEach { it() }
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }

    private enum class InitializationEnd { REINITIALIZE, RELEASE, CLEAR }

    private suspend fun verifyUploadWaitsForAcknowledgement(
        model: EpubReaderViewModel,
        repository: EpubProgressRepository,
        saved: koharia.domain.epub.model.EpubProgress,
    ) {
        fun field(name: String) = EpubReaderViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        val adapterField = field("currentEpubProgressAdapter")
        val progressField = field("currentProgress")
        val urlField = field("currentBookUrl")
        val allowedField = field("remoteProgressWriteAllowed")
        val baselineField = field("remoteProgressWriteBaseline")
        val original = adapterField.get(model) as koharia.connection.ConnectionEpubProgressAdapter
        val local = original as koharia.connection.ConnectionLocalEpubProgressAdapter
        val originalUrl = urlField.get(model)
        val originalAllowed = allowedField.getBoolean(model)
        val originalBaseline = baselineField.get(model)
        val state = conflictState(model)
        val conflict =
            EpubRemoteProgressConflict(checkNotNull(saved.locatorJson), 0, null, saved.updatedAt.time + 1_000)
        val acknowledged = AtomicBoolean(false)
        val uploads = java.util.concurrent.atomic.AtomicInteger()
        val adapter = object :
            koharia.connection.ConnectionEpubProgressAdapter by original,
            koharia.connection.ConnectionLocalEpubProgressAdapter by local {
            override suspend fun reviewEpubProgress(resourceId: String) = conflict.remote()
            override suspend fun pushEpubProgress(
                resourceId: String,
                locator: org.readium.r2.shared.publication.Locator,
                positions: List<org.readium.r2.shared.publication.Locator>,
                modifiedAt: Date,
            ) {
                check(resourceId == "https://fixture.invalid/book")
                uploads.incrementAndGet()
            }
            override suspend fun isEpubProgressConfirmed(
                resourceId: String,
                locator: org.readium.r2.shared.publication.Locator,
                modifiedAt: Date,
            ) = acknowledged.get()
        }
        val sync = Injekt.get<SharedAppPreferences>().epubReaderPreferences().syncRemoteProgression
        val originalSync = sync.get()
        try {
            withContext(Dispatchers.Main) {
                adapterField.set(model, adapter)
                sync.set(true)
            }
            for (choice in listOf(false, true)) {
                for (confirmed in listOf(false, true)) {
                    val expected = saved.copy(bookUrl = "https://fixture.invalid/book", lastSyncedAt = Date(1))
                    repository.upsertProgress(expected)
                    val before = uploads.get()
                    acknowledged.set(confirmed)
                    withContext(Dispatchers.Main) {
                        progressField.set(model, expected)
                        urlField.set(model, expected.bookUrl)
                        allowedField.setBoolean(model, true)
                        baselineField.set(model, null)
                        if (choice) {
                            state.value = state.value.copy(remoteProgressConflict = conflict)
                            model.keepLocalProgress()
                        } else {
                            model.saveCurrentProgress()
                        }
                    }
                    withTimeout(10_000) { while (state.value.isResolvingRemoteProgress) delay(25) }
                    assertTrue(
                        "The actual reader upload path must run (choice=$choice, confirmed=$confirmed)",
                        uploads.get() > before,
                    )
                    val stored = checkNotNull(repository.getProgress(saved.chapterId))
                    assertEquals(if (confirmed) stored.updatedAt else Date(1), stored.lastSyncedAt)
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                sync.set(originalSync)
                adapterField.set(model, original)
                urlField.set(model, originalUrl)
                allowedField.setBoolean(model, originalAllowed)
                baselineField.set(model, originalBaseline)
                progressField.set(model, saved)
                state.value = state.value.copy(remoteProgressConflict = null)
            }
            repository.upsertProgress(saved)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun conflictState(model: EpubReaderViewModel) =
        EpubReaderViewModel::class.java.getDeclaredField("mutableState").let {
            it.isAccessible = true
            it.get(model) as kotlinx.coroutines.flow.MutableStateFlow<EpubReaderUiState>
        }

    private suspend fun <T> withRemoteReview(
        model: EpubReaderViewModel,
        review: suspend () -> koharia.connection.RemoteEpubProgression?,
        action: suspend () -> T,
    ): T {
        val field = EpubReaderViewModel::class.java.getDeclaredField("currentEpubProgressAdapter").apply {
            isAccessible = true
        }
        val original = field.get(model) as koharia.connection.ConnectionEpubProgressAdapter
        val local = original as koharia.connection.ConnectionLocalEpubProgressAdapter
        val adapter = object :
            koharia.connection.ConnectionEpubProgressAdapter by original,
            koharia.connection.ConnectionLocalEpubProgressAdapter by local {
            override suspend fun reviewEpubProgress(resourceId: String) = review()
            override suspend fun acceptRemoteEpubProgress(
                resourceId: String,
                locator: org.readium.r2.shared.publication.Locator,
                modifiedAt: Date,
            ) = Unit
        }
        withContext(Dispatchers.Main) { field.set(model, adapter) }
        try {
            return action()
        } finally {
            withContext(Dispatchers.Main) { field.set(model, original) }
        }
    }

    private fun EpubRemoteProgressConflict.remote() = koharia.connection.RemoteEpubProgression(
        checkNotNull(org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(locatorJson))),
        Date(modifiedAtMillis),
    )

    private suspend fun verifyRemoteChoiceRejectsChangedRemote(
        model: EpubReaderViewModel,
        repository: EpubProgressRepository,
        saved: koharia.domain.epub.model.EpubProgress,
    ) {
        val state = conflictState(model)
        val conflict =
            EpubRemoteProgressConflict(checkNotNull(saved.locatorJson), 0, null, saved.updatedAt.time + 1_000)
        val changed = conflict.copy(modifiedAtMillis = conflict.modifiedAtMillis + 1_000)
        for (accept in listOf(true, false)) {
            for (result in listOf("changed", "removed", "offline")) {
                val reads = java.util.concurrent.atomic.AtomicInteger()
                withRemoteReview(model, {
                    reads.incrementAndGet()
                    when (result) {
                        "changed" -> changed.remote()
                        "removed" -> null
                        else -> throw java.io.IOException("Fixture offline")
                    }
                }) {
                    withContext(Dispatchers.Main) {
                        state.value = state.value.copy(remoteProgressConflict = conflict)
                        if (accept) {
                            model.acceptRemoteProgress { error("Obsolete remote choice navigated") }
                        } else {
                            model.keepLocalProgress()
                        }
                    }
                    withTimeout(10_000) { while (state.value.isResolvingRemoteProgress) delay(25) }
                    assertEquals(1, reads.get())
                    assertEquals(saved, repository.getProgress(saved.chapterId))
                    assertEquals(
                        if (result == "changed") changed.modifiedAtMillis else conflict.modifiedAtMillis,
                        state.value.remoteProgressConflict?.modifiedAtMillis,
                    )
                    val allowed = EpubReaderViewModel::class.java.getDeclaredField("remoteProgressWriteAllowed")
                    allowed.isAccessible = true
                    assertFalse(allowed.getBoolean(model))
                }
            }
        }
    }

    private suspend fun verifyRemoteChoiceWaitsForCommit(
        model: EpubReaderViewModel,
        repository: EpubProgressRepository,
        saved: koharia.domain.epub.model.EpubProgress,
    ): koharia.domain.epub.model.EpubProgress {
        val state = conflictState(model)
        val conflict =
            EpubRemoteProgressConflict(checkNotNull(saved.locatorJson), 0, null, saved.updatedAt.time + 1_000)
        val mutex = EpubReaderViewModel::class.java.getDeclaredField("progressPersistenceMutex").let {
            it.isAccessible = true
            it.get(model) as kotlinx.coroutines.sync.Mutex
        }
        val navigations = java.util.concurrent.atomic.AtomicInteger()
        return withRemoteReview(model, { conflict.remote() }) {
            mutex.lock()
            try {
                withContext(Dispatchers.Main) {
                    state.value = state.value.copy(remoteProgressConflict = conflict)
                    model.acceptRemoteProgress { navigations.incrementAndGet() }
                    model.acceptRemoteProgress { error("Duplicate acceptance navigated") }
                    assertTrue(state.value.isResolvingRemoteProgress)
                    assertEquals(conflict, state.value.remoteProgressConflict)
                    assertEquals(0, navigations.get())
                }
                assertEquals(saved, repository.getProgress(saved.chapterId))
            } finally {
                mutex.unlock()
            }
            withTimeout(10_000) { while (state.value.isResolvingRemoteProgress) delay(25) }
            assertEquals(1, navigations.get())
            assertNull(state.value.remoteProgressConflict)
            val accepted = checkNotNull(repository.getProgress(saved.chapterId))
            assertEquals(Date(conflict.modifiedAtMillis), accepted.lastSyncedAt)
            accepted
        }
    }

    private suspend fun verifyRemoteChoiceRejectsChangedRow(
        model: EpubReaderViewModel,
        repository: EpubProgressRepository,
        newer: koharia.domain.epub.model.EpubProgress,
    ) {
        val state = conflictState(model)
        val conflict =
            EpubRemoteProgressConflict(checkNotNull(newer.locatorJson), 0, null, newer.updatedAt.time + 1_000)
        withRemoteReview(model, { conflict.remote() }) {
            withContext(Dispatchers.Main) {
                state.value = state.value.copy(remoteProgressConflict = conflict)
                model.acceptRemoteProgress { error("Failed commit navigated the reader") }
            }
            withTimeout(10_000) { while (state.value.isResolvingRemoteProgress) delay(25) }
            assertEquals(conflict, state.value.remoteProgressConflict)
            assertEquals(newer, repository.getProgress(newer.chapterId))
            withContext(Dispatchers.Main) { model.keepLocalProgress() }
            withTimeout(10_000) { while (state.value.isResolvingRemoteProgress) delay(25) }
            assertEquals(conflict, state.value.remoteProgressConflict)
            assertEquals(newer, repository.getProgress(newer.chapterId))
            withContext(Dispatchers.Main) { state.value = state.value.copy(remoteProgressConflict = null) }
        }
    }

    private suspend fun verifyOverlappingInitialization(
        mangaId: Long,
        chapterId: Long,
        sourceId: Long,
        end: InitializationEnd,
    ) =
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val firstRead = AtomicBoolean(true)
            val bookmarks = Injekt.get<EpubBookmarkRepository>()
            val sessions = EpubReaderSessionRepository()
            val launchExtras = withContext(Dispatchers.IO) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                checkNotNull(EpubReaderLauncher().resolveIntent(context, mangaId, chapterId).extras)
            }
            val model = EpubReaderViewModel(
                savedState = SavedStateHandle.createHandle(null, launchExtras),
                sessionRepository = sessions,
                getEpubBookmarks = GetEpubBookmarks(
                    object : EpubBookmarkRepository by bookmarks {
                        override suspend fun getBookmarksByChapterId(chapterId: Long): List<EpubBookmark> {
                            if (firstRead.getAndSet(false)) {
                                entered.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled.complete(Unit)
                                    withContext(NonCancellable) { cleanup.await() }
                                }
                            }
                            return bookmarks.getBookmarksByChapterId(chapterId)
                        }
                    },
                ),
            )
            val store = ViewModelStore().apply { put("overlap", model) }
            try {
                withTimeout(25_000) {
                    val first = async { model.init(mangaId, chapterId) }
                    try {
                        select {
                            entered.onAwait { }
                            first.onAwait {
                                it.getOrThrow()
                                error("Initialization completed without reaching the bookmark gate")
                            }
                        }
                        val oldSession = checkNotNull(sessions.get(chapterId))
                        val second = when (end) {
                            InitializationEnd.REINITIALIZE -> async { model.init(mangaId, chapterId) }
                            InitializationEnd.RELEASE -> {
                                model.releaseSession()
                                null
                            }
                            InitializationEnd.CLEAR -> {
                                store.clear()
                                null
                            }
                        }
                        cancelled.await()
                        second?.let { assertFalse(it.isCompleted) }
                        assertTrue(sessions.get(chapterId) === oldSession)
                        if (second == null) {
                            val closed = runCatching { model.init(mangaId, chapterId) }
                            assertTrue(closed.exceptionOrNull() is CancellationException)
                        }
                        cleanup.complete(Unit)
                        second?.await()?.getOrThrow()
                        first.join()
                        assertTrue(first.isCancelled)
                        if (second != null) {
                            assertTrue(model.state.value.isReady)
                            assertEquals(1L, model.state.value.sessionToken)
                            assertEquals(sourceId, model.state.value.sourceId)
                            assertTrue(checkNotNull(sessions.get(chapterId)) !== oldSession)
                        } else {
                            while (sessions.get(chapterId) != null) delay(10)
                            assertFalse(model.state.value.isReady)
                            assertFalse(model.state.value.isLoading)
                            assertEquals(0L, model.state.value.sessionToken)
                            assertNull(Injekt.get<EpubProgressRepository>().getProgress(chapterId))
                        }
                    } finally {
                        cleanup.complete(Unit)
                    }
                }
            } finally {
                cleanup.complete(Unit)
                model.releaseSession()
                store.clear()
            }
        }

    private fun writeBook(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.trimIndent().toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """,
            )
            entry(
                "content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">progress-fixture</dc:identifier><dc:title>Progress fixture</dc:title>
                    <dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
                """,
            )
            entry(
                "nav.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>Contents</title></head><body><nav epub:type="toc">
                    <ol><li><a href="chapter.xhtml">Chapter</a></li></ol>
                  </nav></body>
                </html>
                """,
            )
            val paragraphs = (1..100).joinToString("") { "<p>Paragraph $it. Reading position fixture.</p>" }
            entry(
                "chapter.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head>
                  <body><h1>Chapter</h1>$paragraphs</body>
                </html>
                """,
            )
        }
    }
}
