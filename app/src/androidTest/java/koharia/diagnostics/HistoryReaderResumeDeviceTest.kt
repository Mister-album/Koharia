package koharia.diagnostics

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.ConnectionLibraryTab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import koharia.connection.SharedAppPreferences
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.epub.EpubReaderActivity
import koharia.epub.EpubReaderFragment
import koharia.epub.EpubReaderLauncher
import koharia.epub.EpubReaderViewModel
import koharia.epub.settings.EpubLayoutPreferences
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.source.local.defaultBookshelfId
import koharia.source.local.withInitialBookshelves
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import eu.kanade.presentation.more.settings.Preference as SettingsPreference

@RunWith(AndroidJUnit4::class)
class HistoryReaderResumeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun historyReopensOwnedComicPdfAndEpubAtSavedPosition() = runReaderJourney(false)

    @Test
    fun settingsKeepContentOwnerAcrossConnectionSelection() = runReaderJourney(true)

    @Test
    fun imageDirectoryReaderTitleOpensOwnedStandardDetails() = runReaderJourney(false, readerDetails = true)

    private fun runReaderJourney(settingsMatrix: Boolean, readerDetails: Boolean = false): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val history = Injekt.get<HistoryRepository>()
        val progress = Injekt.get<EpubProgressRepository>()
        val root = File(context.cacheDir, "history-reader-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val selectedProfiles = mutableListOf<LibraryConnectionProfile>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val profile = profiles.add("local-folder", "History reader ${UUID.randomUUID()}")
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putLong("fixture_owned_profile", profile.id)
                putString("fixture_owned_root", root.name)
                putLong("fixture_previous_active", connections.activeConnectionId.get())
            },
        )
        try {
            val shared = Injekt.get<SharedAppPreferences>()
            replace(connections.activeConnectionId, profile.id)
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(shared.basePreferences().incognitoMode, false)
            replace(shared.downloadPreferences().removeAfterReadSlots, -1)
            val reader = shared.readerPreferences()
            replace(reader.showNavigationOverlayNewUser, false)
            replace(reader.showNavigationOverlayOnStart, false)
            replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
            replace(shared.epubReaderPreferences().syncRemoteProgression, false)
            val layout = shared.epubLayoutPreferences()
            if (settingsMatrix) {
                replace(reader.persistReaderSettingsChanges, true)
                replace(shared.epubReaderPreferences().persistReaderSettingsChanges, true)
                replace(reader.readerTheme, 0)
                replace(reader.readerCustomBackgroundColor, android.graphics.Color.WHITE)
                val colors = EpubLayoutPreferences.encodeBackgroundColors(
                    EpubLayoutPreferences.DEFAULT_BACKGROUND_COLORS,
                )
                replace(reader.readerBackgroundColors, colors)
                replace(layout.theme, EpubLayoutPreferences.Theme.LIGHT)
                replace(layout.customBackgroundColor, android.graphics.Color.WHITE)
                replace(layout.backgroundColors, colors)
                for (provider in Injekt.get<ConnectionRegistry>().availableProviders()) {
                    selectedProfiles +=
                        profiles.add(provider.id, "Settings selection ${provider.id} ${UUID.randomUUID()}")
                }
                for (mode in listOf(
                    koharia.storage.LibraryStorageMode.WEBDAV,
                    koharia.storage.LibraryStorageMode.SMB,
                )) {
                    val selected = profiles.add("local-folder", "Settings selection $mode ${UUID.randomUUID()}")
                    selectedProfiles += selected
                    profiles.commitConfiguration(selected.id) { draft ->
                        koharia.storage.NetworkStoragePreferences(selected.id, draft).save(
                            koharia.storage.NetworkStorageConfiguration(mode = mode, needsValidation = true),
                            "",
                            "",
                        )
                    }
                }
                assertEquals(Injekt.get<ConnectionRegistry>().availableProviders().size + 2, selectedProfiles.size)
            }

            replace(layout.readingMode, EpubLayoutPreferences.ReadingMode.PAGINATED)
            replace(layout.pageDirection, EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT)
            replace(
                layout.pageTransitionEffect,
                eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect.NONE.value,
            )
            if (readerDetails) {
                val directory = File(root, "comics/ImageDirectory").apply { check(mkdirs()) }
                File(root, "books").mkdirs()
                repeat(3) { index ->
                    val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                        File(directory, "$index.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                replace(shared.basePreferences().downloadedOnly, false)
                replace(Injekt.get<BasePreferences>().downloadedOnly, false)
            } else {
                createMedia(root)
            }
            val originalBytes = root.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            profiles.commitConfiguration(profile.id) { draft ->
                LocalLibraryPreferences(profile.id, Json, draft).setConfig(
                    config.copy(
                        setupCompleted = true,
                        bookshelves = config.bookshelves.map {
                            if (readerDetails && it.contentType == LocalLibraryContentType.COMICS) {
                                it.copy(organizationMode = koharia.source.local.LocalLibraryOrganizationMode.FOLDER)
                            } else {
                                it
                            }
                        },
                        roots = listOf(
                            LocalLibraryContentType.COMICS to "comics",
                            LocalLibraryContentType.BOOKS to "books",
                        ).map { (kind, directory) ->
                            LocalLibraryRootConfig(
                                id = kind.name,
                                treeUri = File(root, directory).toURI().toString(),
                                contentType = kind,
                                bookshelfId = config.defaultBookshelfId(kind),
                            )
                        },
                    ),
                )
            }
            val source = Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource
            withTimeout(30_000) { source.refreshLibrary().getOrThrow() }
            val units = mangas.getMangaBySourceId(profile.id).associate { manga ->
                Injekt.get<SyncChaptersWithSource>().fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
                manga.title to (manga.id to chapters.getChapterByMangaId(manga.id).single().id)
            }
            if (readerDetails) {
                assertEquals(setOf("ImageDirectory"), units.keys)
                val (mangaId, chapterId) = units.getValue("ImageDirectory")
                verifyImageDirectoryDetails(source, mangaId, chapterId)
            } else {
                assertEquals(setOf("HistoryComic", "HistoryPdf", "HistoryBook"), units.keys)
            }
            for ((title, ids) in if (readerDetails) emptyMap() else units) {
                val (mangaId, chapterId) = ids
                if (settingsMatrix) connections.activeConnectionId.set(selectedProfiles.first().id)
                val intent = EpubReaderLauncher().resolveIntent(context, mangaId, chapterId)
                FixtureActivityLauncher.launch<Activity>(intent).use { scenario ->
                    lateinit var activity: Activity
                    scenario.onActivity { activity = it }
                    awaitReader(activity, mangaId, chapterId, profile.id, title, 0)
                    if (settingsMatrix) {
                        verifyReaderSettings(activity, mangaId, chapterId, profile.id, title, selectedProfiles)
                        exitReader(activity)
                        return@use
                    }

                    if (activity is ReaderActivity) {
                        withContext(Dispatchers.Main) {
                            val state = (activity as ReaderActivity).viewModel.state.value
                            state.viewer!!.moveToPage(state.currentChapter!!.pages!![1])
                        }
                    } else {
                        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                            instrumentation.sendKeySync(
                                KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON),
                            )
                        }
                    }
                    awaitReader(activity, mangaId, chapterId, profile.id, title, 1)
                    exitReader(activity)
                }
                if (settingsMatrix) continue
                withTimeout(15_000) {
                    while (history.getHistoryByMangaId(mangaId).none {
                            it.chapterId == chapterId && it.readAt != null
                        }
                    ) {
                        delay(50)
                    }
                }
                if (title == "HistoryBook") {
                    val saved = checkNotNull(progress.getProgress(chapterId))
                    assertEquals(mangaId, saved.mangaId)
                    assertEquals("s2.xhtml", JSONObject(saved.locatorJson).getString("href"))
                } else {
                    assertEquals(1L, chapters.getChapterById(chapterId)!!.lastPageRead)
                }
                assertFalse(chapters.getChapterById(chapterId)!!.read)
                status(title, "read-exit-persisted")
            }
            if (!settingsMatrix && !readerDetails) {
                FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent { TachiyomiTheme { Navigator(HomeScreen) } }
                    }
                    withTimeout(10_000) { HomeScreen.openTab(HomeScreen.Tab.History) }
                    for ((title, ids) in units) {
                        val (mangaId, chapterId) = ids
                        val readerClass = if (title ==
                            "HistoryBook"
                        ) {
                            EpubReaderActivity::class.java
                        } else {
                            ReaderActivity::class.java
                        }
                        val monitor = instrumentation.addMonitor(readerClass.name, null, false)
                        var opened: Activity? = null
                        try {
                            clickHistory(title)
                            opened = checkNotNull(monitor.waitForActivityWithTimeout(30_000)) {
                                "History did not launch the expected reader for $title"
                            }
                            awaitReader(opened, mangaId, chapterId, profile.id, title, 1)
                            assertEquals(profile.id, connections.activeConnectionId.get())
                            status(title, "history-resumed-owner-position")
                            exitReader(opened)
                        } finally {
                            opened?.let { activity ->
                                withContext(Dispatchers.Main) { if (!activity.isDestroyed) activity.finish() }
                            }
                            instrumentation.removeMonitor(monitor)
                        }
                        assertTrue(history.getHistoryByMangaId(mangaId).all { it.chapterId == chapterId })
                    }
                }
            }
            if (settingsMatrix) verifyDedicatedSettings(selectedProfiles)
            for ((path, bytes) in originalBytes) {
                assertEquals(bytes, MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).toList())
            }
            status("all", "source-bytes-retained")
        } finally {
            try {
                ConnectionLibraryTab.clearAllRuntimeState()
                for (selected in selectedProfiles) profiles.remove(selected.id).getOrThrow()
                profiles.remove(profile.id).getOrThrow()
                mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
            } finally {
                restores.asReversed().forEach { it() }
                check(root.parentFile == context.cacheDir.canonicalFile && root.deleteRecursively())
            }
            status("all", "owned-profile-root-removed;preferences-restored")
        }
    }

    private suspend fun verifyImageDirectoryDetails(source: LocalFolderSource, mangaId: Long, chapterId: Long) {
        val manga = Injekt.get<MangaRepository>().getMangaById(mangaId)
        val chapter = Injekt.get<ChapterRepository>().getChapterById(chapterId)!!
        check(source.indexedEntry(manga.url)?.imageComic == true)
        val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
        var opened: ReaderActivity? = null
        try {
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    host.setContent {
                        TachiyomiTheme {
                            Navigator(
                                koharia.source.local.LocalLibraryScreen(
                                    source.id,
                                    koharia.connection.LibraryContentScope.ALL,
                                    null,
                                    false,
                                ),
                            )
                        }
                    }
                }
                clickHistory(manga.title)
                val reader = checkNotNull(monitor.waitForActivityWithTimeout(30_000)) as ReaderActivity
                opened = reader
                awaitReader(reader, mangaId, chapterId, source.id, manga.title, 0)
                withContext(Dispatchers.Main) { reader.viewModel.showMenus(true) }
                clickHistory(manga.title)
                withTimeout(15_000) {
                    while (true) {
                        val screen = withContext(Dispatchers.Main) {
                            val main = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                                .filterIsInstance<eu.kanade.tachiyomi.ui.main.MainActivity>().singleOrNull()
                            val navigator = main?.let {
                                eu.kanade.tachiyomi.ui.main.MainActivity::class.java.getDeclaredField("navigator")
                                    .apply { isAccessible = true }.get(it) as? Navigator
                            }
                            navigator?.lastItem as? eu.kanade.tachiyomi.ui.manga.MangaScreen
                        }
                        if (screen != null) {
                            assertEquals(mangaId, screen.mangaId)
                            assertEquals(source.id, screen.sourceId)
                            assertEquals(manga.url, screen.mangaUrl)
                            break
                        }
                        delay(50)
                    }
                }
                withTimeout(15_000) {
                    while (nodes(freshRoot()).none {
                            it.isVisibleToUser && it.text?.toString() == chapter.name
                        }
                    ) {
                        delay(50)
                    }
                }
                val after = Injekt.get<MangaRepository>().getMangaById(mangaId)
                assertEquals(manga.source, after.source)
                assertEquals(manga.url, after.url)
                val units = Injekt.get<ChapterRepository>().getChapterByMangaId(mangaId)
                assertEquals(listOf(chapterId), units.map { it.id })
                assertEquals(chapter.url, units.single().url)
                status(manga.title, "shelf;image-reader;title;MainActivity;MangaScreen;canonical-owner;single-unit")
            }
        } finally {
            opened?.let { reader -> withContext(Dispatchers.Main) { if (!reader.isDestroyed) reader.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

    private suspend fun verifyReaderSettings(
        activity: Activity,
        mangaId: Long,
        chapterId: Long,
        sourceId: Long,
        title: String,
        selections: List<LibraryConnectionProfile>,
    ) {
        val shared = Injekt.get<SharedAppPreferences>()
        val connections = Injekt.get<ConnectionPreferences>()
        val comic = activity is ReaderActivity
        val previousColor = if (comic) {
            shared.readerPreferences().readerCustomBackgroundColor.get()
        } else {
            shared.epubLayoutPreferences().customBackgroundColor.get()
        }
        val color = if (previousColor == 0xFFFBF0D9.toInt()) 0xFFC4EDC8.toInt() else 0xFFFBF0D9.toInt()
        val colorDescription = "#%06X".format(color and 0xFFFFFF)
        withContext(Dispatchers.Main) {
            if (activity is ReaderActivity) {
                activity.viewModel.showMenus(true)
            } else {
                ViewModelProvider(activity as EpubReaderActivity)[EpubReaderViewModel::class.java].showMenus(true)
            }
        }
        status(title, "settings-opening-panel")
        clickSettingDescription(
            context.stringResource(if (comic) MR.strings.action_settings else MR.strings.epub_reader_settings),
        )
        clickSettingDescription(colorDescription)
        status(title, "settings-color-clicked")
        withTimeout(10_000) {
            while (if (comic) {
                    shared.readerPreferences().readerCustomBackgroundColor.get() != color
                } else {
                    shared.epubLayoutPreferences().customBackgroundColor.get() != color
                }
            ) {
                delay(50)
            }
        }
        for (selected in selections) {
            connections.activeConnectionId.set(selected.id)
            withTimeout(10_000) {
                while (nodes(freshRoot()).none {
                        it.isVisibleToUser && it.contentDescription?.toString() == colorDescription
                    }
                ) {
                    delay(50)
                }
            }
            withContext(Dispatchers.Main) {
                if (activity is ReaderActivity) {
                    assertEquals(mangaId, activity.viewModel.state.value.manga?.id)
                    assertEquals(sourceId, activity.viewModel.state.value.manga?.source)
                    assertEquals(chapterId, activity.viewModel.state.value.currentChapter?.chapter?.id)
                } else {
                    val reader = activity as EpubReaderActivity
                    val state = ViewModelProvider(reader)[EpubReaderViewModel::class.java].state.value
                    assertEquals(mangaId, state.mangaId)
                    assertTrue(reader.isCurrentReaderSession(chapterId, sourceId, state.sessionToken))
                }
            }
            if (comic) {
                assertEquals(color, shared.readerPreferences().readerCustomBackgroundColor.get())
                assertEquals(
                    eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.CUSTOM_BACKGROUND_THEME,
                    shared.readerPreferences().readerTheme.get(),
                )
            } else {
                assertEquals(color, shared.epubLayoutPreferences().customBackgroundColor.get())
                assertEquals(EpubLayoutPreferences.Theme.CUSTOM, shared.epubLayoutPreferences().theme.get())
            }
            assertEquals(selected.id, connections.activeConnectionId.get())
            status(title, "settings-owner-and-shared-values-${selected.providerId}")
        }
        clickSettingDescription(
            context.stringResource(if (comic) MR.strings.action_settings else MR.strings.epub_reader_settings),
        )
        withTimeout(10_000) {
            while (nodes(freshRoot()).any { it.contentDescription?.toString() == colorDescription }) delay(50)
        }
    }

    private suspend fun verifyDedicatedSettings(selections: List<LibraryConnectionProfile>) {
        val connections = Injekt.get<ConnectionPreferences>()
        val observed = AtomicReference<Pair<Long, List<String>>>()
        val komgaGroup = context.stringResource(
            MR.strings.connection_provider_settings_group,
            "Komga",
            context.stringResource(MR.strings.komga_library_classification_group),
        )
        FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme {
                        Navigator(SettingsLibraryScreen) {
                            val selected by connections.activeConnectionId.collectAsState()
                            val groups = SettingsLibraryScreen.getPreferences()
                                .filterIsInstance<SettingsPreference.PreferenceGroup>().map { it.title }
                            SideEffect { observed.set(selected to groups) }
                            SettingsLibraryScreen.Content()
                        }
                    }
                }
            }
            for (selected in selections) {
                connections.activeConnectionId.set(selected.id)
                withTimeout(10_000) {
                    while (observed.get()?.let {
                            it.first == selected.id && (komgaGroup in it.second) == (selected.providerId == "komga")
                        } != true
                    ) {
                        delay(50)
                    }
                }
                status(selected.providerId, "dedicated-settings-follow-selection")
            }
        }
    }

    private suspend fun clickSettingDescription(description: String) {
        val clicked = withTimeoutOrNull(10_000) {
            while (true) {
                val node = nodes(freshRoot()).firstOrNull {
                    it.isVisibleToUser && it.contentDescription?.toString() == description
                }
                if (node != null) {
                    var target: AccessibilityNodeInfo = node
                    while (!target.isClickable && target.parent != null) target = checkNotNull(target.parent)
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeoutOrNull true
                }
                delay(50)
            }
            false
        }
        check(clicked == true) {
            "Missing settings control $description; visible=" + nodes(freshRoot())
                .filter { it.isVisibleToUser }.mapNotNull { it.contentDescription?.toString() }.take(30)
        }
    }

    private suspend fun awaitReader(
        activity: Activity,
        mangaId: Long,
        chapterId: Long,
        sourceId: Long,
        title: String,
        page: Int,
    ) {
        status(title, "await-page-$page")
        try {
            awaitReaderPosition(activity, mangaId, chapterId, sourceId, title, page)
        } catch (error: TimeoutCancellationException) {
            val diagnostic = withContext(Dispatchers.Main) {
                if (activity is EpubReaderActivity) {
                    val model = ViewModelProvider(activity)[EpubReaderViewModel::class.java]
                    val state = model.state.value
                    "ready=${state.isReady};manga=${state.mangaId};chapter=${state.chapterId};" +
                        "session=${activity.isCurrentReaderSession(chapterId, sourceId, state.sessionToken)};" +
                        "href=${model.currentLocator()?.href};transition=${epubTransitionReady(activity, page)}"
                } else {
                    val state = (activity as ReaderActivity).viewModel.state.value
                    "manga=${state.manga?.id};chapter=${state.currentChapter?.chapter?.id};page=${state.currentPage}"
                }
            }
            status(title, diagnostic)
            status(title, nodes(freshRoot()).mapNotNull { it.text?.toString() }.joinToString("|"))
            throw error
        }
    }

    private suspend fun awaitReaderPosition(
        activity: Activity,
        mangaId: Long,
        chapterId: Long,
        sourceId: Long,
        title: String,
        page: Int,
    ) = withTimeout(30_000) {
        while (true) {
            val ready = withContext(Dispatchers.Main) {
                if (title == "HistoryBook") {
                    check(activity is EpubReaderActivity)
                    val model = ViewModelProvider(activity)[EpubReaderViewModel::class.java]
                    val state = model.state.value
                    check(state.errorMessage == null) { state.errorMessage.orEmpty() }
                    state.isReady && state.mangaId == mangaId && state.chapterId == chapterId &&
                        activity.isCurrentReaderSession(chapterId, sourceId, state.sessionToken) &&
                        model.currentLocator()?.href?.toString() == "s${page + 1}.xhtml" &&
                        epubTransitionReady(activity, page)
                } else {
                    check(activity is ReaderActivity)
                    val state = activity.viewModel.state.value
                    state.manga?.id == mangaId && state.manga.source == sourceId &&
                        state.currentChapter?.chapter?.id == chapterId && state.totalPages == 3 &&
                        state.currentPage == page + 1 &&
                        state.currentChapter?.pages?.get(page)?.status == Page.State.Ready &&
                        descendants(activity.window.decorView).filterIsInstance<SubsamplingScaleImageView>().any {
                            it.isReady
                        }
                }
            }
            val rendered = title != "HistoryBook" || nodes(freshRoot()).any {
                it.isVisibleToUser && it.text?.contains("History section ${page + 1}") == true
            }
            if (ready && rendered) break
            delay(100)
        }
    }

    private fun epubTransitionReady(activity: EpubReaderActivity, page: Int): Boolean {
        val fragment = activity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>().singleOrNull()
            ?: return false
        fun field(name: String) = EpubReaderFragment::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(fragment)
        return field("currentTransitionHref") == "s${page + 1}.xhtml" &&
            (field("currentTransitionPageCount") as Int) > 0
    }

    private suspend fun exitReader(activity: Activity) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        withTimeout(30_000) {
            while (!withContext(Dispatchers.Main) { activity.isDestroyed }) delay(50)
        }
    }

    private suspend fun clickHistory(title: String) = withTimeout(15_000) {
        while (true) {
            val label = nodes(freshRoot()).firstOrNull { it.isVisibleToUser && it.text?.toString() == title }
            var target = label
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
            delay(100)
        }
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        if (root == null) return@buildList
        add(root)
        for (index in 0 until root.childCount) addAll(nodes(root.getChild(index)))
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (child in view.children) yieldAll(descendants(child))
    }

    private fun status(title: String, phase: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("history_reader", "$title;$phase") })
    }

    private fun createMedia(root: File) {
        val comic = File(root, "comics/HistoryComic/book.cbz").apply { parentFile!!.mkdirs() }
        ZipOutputStream(comic.outputStream()).use { zip ->
            repeat(3) { index ->
                val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                    zip.putNextEntry(ZipEntry("$index.png"))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val pdf = File(root, "comics/HistoryPdf/book.pdf").apply { parentFile!!.mkdirs() }
        val document = PdfDocument()
        try {
            repeat(3) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        val epub = File(root, "books/HistoryBook.epub").apply { parentFile!!.mkdirs() }
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray()
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
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">history-reader</dc:identifier>
                <dc:title>HistoryBook</dc:title><dc:language>en</dc:language></metadata>
                <manifest>$manifest</manifest><spine>$spine</spine></package>
                """.trimIndent(),
            )
            for (section in 1..3) {
                entry(
                    "s$section.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Section $section</title></head>
                    <body><p>History section $section</p></body></html>
                    """.trimIndent(),
                )
            }
        }
    }
}
