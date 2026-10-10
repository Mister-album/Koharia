package koharia.source.local

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLibraryFilterUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences: LibraryPreferences get() = Injekt.get()

    @Test
    fun folderUsesChapterTabsAndAppliesConditionsWithoutClosing() {
        val filters = MutableStateFlow(LocalLibraryFilters())
        withDialog(isFolder = true, filters = filters) {
            clickLabel(context.stringResource(MR.strings.action_filter_unread))
            awaitCondition { filters.value.unread == TriState.ENABLED_IS }
            clickLabel(context.stringResource(MR.strings.action_filter_unread))
            awaitCondition { filters.value.unread == TriState.ENABLED_NOT }
            clickLabel(context.stringResource(MR.strings.action_filter_unread))
            awaitCondition { filters.value.unread == TriState.DISABLED }
            clickLabel(context.stringResource(MR.strings.action_filter_bookmarked))
            awaitCondition { filters.value.bookmarked == TriState.ENABLED_IS }
            clickLabel(context.stringResource(MR.strings.action_sort))
            clickLabel(context.stringResource(MR.strings.sort_by_number))
            awaitCondition { filters.value.sort == 3 }
            clickLabel(context.stringResource(MR.strings.sort_by_number))
            awaitCondition { filters.value.descending }
            clickLabel(context.stringResource(MR.strings.action_filter))
            clickLabel(context.stringResource(MR.strings.scanlator))
            awaitLabel(context.stringResource(MR.strings.exclude_scanlators))
            awaitLabel("Group A")
        }
    }

    @Test
    fun outerListUsesLibraryConditionsAndDisplayControls() {
        val filters = MutableStateFlow(LocalLibraryFilters())
        val display = MutableStateFlow<LibraryDisplayMode>(LibraryDisplayMode.CompactGrid)
        withDialog(isFolder = false, filters = filters, display = display) {
            awaitLabel(context.stringResource(MR.strings.label_started))
            awaitLabel(context.stringResource(MR.strings.completed))
            clickLabel(context.stringResource(MR.strings.label_started))
            awaitCondition { filters.value.started == TriState.ENABLED_IS }
            clickLabel(context.stringResource(MR.strings.action_display))
            clickLabel(context.stringResource(MR.strings.action_display_list))
            awaitCondition { display.value == LibraryDisplayMode.List }
            clickLabel(context.stringResource(MR.strings.action_filter))
            awaitLabel(context.stringResource(MR.strings.local_library_available))
            clickLabel(context.stringResource(MR.strings.label_more))
            clickLabel(context.stringResource(MR.strings.action_reset))
            awaitCondition { filters.value == LocalLibraryFilters() }
        }
    }

    @Test
    fun folderDisplayUsesSharedPreferencesAndNetworkAvailabilityUsesDownloads() {
        val priorMode = preferences.chapterCoverDisplayMode.get()
        val priorFileSize = preferences.showChapterFileSize.get()
        val titleMode = MutableStateFlow(Manga.CHAPTER_DISPLAY_NAME)
        try {
            preferences.chapterCoverDisplayMode.set(Manga.CHAPTER_COVER_DISPLAY_TEXT)
            preferences.showChapterFileSize.set(false)
            withDialog(isFolder = true, networkStorage = true, titleMode = titleMode) {
                awaitLabel(context.stringResource(MR.strings.label_downloaded))
                assertFalse(hasLabel(context.stringResource(MR.strings.local_library_available)))
                clickLabel(context.stringResource(MR.strings.action_display))
                clickLabel(context.stringResource(MR.strings.action_display_comfortable_grid))
                awaitCondition { preferences.chapterCoverDisplayMode.get() == Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE }
                clickLabel(context.stringResource(MR.strings.show_original_file_name))
                awaitCondition { titleMode.value == Manga.CHAPTER_DISPLAY_FILE_NAME }
                clickLabel(context.stringResource(MR.strings.pref_show_chapter_file_size))
                awaitCondition { preferences.showChapterFileSize.get() }
                clickLabel(context.stringResource(MR.strings.pref_show_chapter_file_size))
                awaitCondition { !preferences.showChapterFileSize.get() }
            }
        } finally {
            preferences.chapterCoverDisplayMode.set(priorMode)
            preferences.showChapterFileSize.set(priorFileSize)
        }
    }

    @Test
    fun rememberedConditionsAreIsolatedBetweenConnectionsAndFolders() {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val sourceId = 9_710_000_000_000L + System.currentTimeMillis()
        val otherId = sourceId + 1
        try {
            val root = LocalLibraryFilterPreferences(sourceId)
            val folder = LocalLibraryFilterPreferences(sourceId, "folder-a")
            val sibling = LocalLibraryFilterPreferences(sourceId, "folder-b")
            val other = LocalLibraryFilterPreferences(otherId)
            root.write(LocalLibraryFilters(author = "Root"), "shelf", true)
            folder.write(LocalLibraryFilters(unread = TriState.ENABLED_IS), null, true)
            assertEquals("Root", root.read()!!.filters.author)
            assertEquals(TriState.ENABLED_IS, folder.read()!!.filters.unread)
            assertFalse(sibling.enabled)
            assertFalse(other.enabled)
            folder.write(LocalLibraryFilters(), null, false)
            assertTrue(root.enabled)
            assertEquals(null, folder.read())
        } finally {
            sourcePreferences("source_$sourceId").edit().clear().commit()
            sourcePreferences("source_$otherId").edit().clear().commit()
        }
    }

    @Test
    fun cachedFolderSortingAndSearchWorkForAllStorageModes() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        LibraryStorageMode.entries.forEach { mode ->
            val sourceId = 9_720_000_000_000L + System.currentTimeMillis()
            try {
                NetworkStoragePreferences(sourceId).save(NetworkStorageConfiguration(mode = mode), "", "")
                val localPreferences = LocalLibraryPreferences(sourceId, Json)
                localPreferences.setConfig(
                    LocalLibraryConfig(
                        setupCompleted = true,
                        enabledContentTypes = setOf(LocalLibraryContentType.COMICS),
                        bookshelves = listOf(
                            LocalBookshelf(
                                "shelf",
                                "Fixture",
                                LocalLibraryContentType.COMICS,
                                LocalLibraryOrganizationMode.FOLDER,
                            ),
                        ),
                        roots = listOf(
                            LocalLibraryRootConfig(
                                "root",
                                context.cacheDir.toURI().toString(),
                                contentType = LocalLibraryContentType.COMICS,
                                bookshelfId = "shelf",
                            ),
                        ),
                    ),
                )
                val paths =
                    listOf(
                        "Collection",
                        "Collection/A Chapter 10.cbz",
                        "Collection/Z Chapter 2.cbz",
                        "Collection/Nested",
                    )
                localPreferences.setIndex(
                    LocalLibraryIndex(
                        items = paths.mapIndexed { index, path ->
                            val folder = index == 0 || index == 3
                            LocalLibraryItem(
                                itemKey = LocalLibraryLocator.itemKey("root", path),
                                rootId = "root",
                                relativePath = path,
                                contentType = LocalLibraryContentType.COMICS,
                                kind = if (folder) LocalLibraryItem.Kind.FOLDER else LocalLibraryItem.Kind.FILE_ENTRY,
                                format = if (folder) "directory" else "cbz",
                                sizeBytes = 100,
                                modifiedAt = index.toLong(),
                            )
                        },
                    ),
                )
                val source =
                    LocalFolderSource(
                        context,
                        sourceId,
                        "Fixture",
                        LibraryConnectionProfile(sourceId, LocalFolderConnectionProvider.ID, "Fixture"),
                    )
                val mangas = paths.mapIndexed { index, path ->
                    Manga.create().copy(
                        id = index.toLong(),
                        source = sourceId,
                        url = LocalLibraryLocator.entryUrl(sourceId, "root", path),
                        title = path.substringAfterLast('/').substringBeforeLast('.'),
                    )
                }
                val parent = mangas.first().url
                assertEquals(
                    listOf(mangas.first().id),
                    source.browseIndexedLibrary(mangas, "", LibraryContentScope.ALL).map {
                        it.id
                    },
                )
                val numeric = source.browseIndexedLibrary(
                    mangas,
                    "",
                    filters = LocalLibraryFilters(sort = 3),
                    parentUrl = parent,
                )
                assertEquals(listOf(mangas[2].id, mangas[1].id), numeric.filter { it.id != mangas[3].id }.map { it.id })
                val alphabetical = source.browseIndexedLibrary(mangas, "", parentUrl = parent)
                assertEquals(
                    listOf(mangas[1].id, mangas[2].id),
                    alphabetical.filter {
                        it.id != mangas[3].id
                    }.map { it.id },
                )
                val foldersFirst = source.browseIndexedLibrary(
                    mangas,
                    "",
                    filters = LocalLibraryFilters(sort = 3, foldersFirst = true),
                    parentUrl = parent,
                )
                assertEquals(mangas[3].id, foldersFirst.first().id)
                assertEquals(
                    listOf(mangas[2].id),
                    source.browseIndexedLibrary(mangas, "Z Chapter", parentUrl = parent).map {
                        it.id
                    },
                )
            } finally {
                sourcePreferences("source_$sourceId").edit().clear().commit()
            }
        }
    }

    private fun withDialog(
        isFolder: Boolean,
        networkStorage: Boolean = false,
        filters: MutableStateFlow<LocalLibraryFilters> = MutableStateFlow(LocalLibraryFilters()),
        display: MutableStateFlow<LibraryDisplayMode> = MutableStateFlow(LibraryDisplayMode.CompactGrid),
        titleMode: MutableStateFlow<Long> = MutableStateFlow(Manga.CHAPTER_DISPLAY_NAME),
        check: () -> Unit,
    ) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val currentFilters by filters.collectAsState()
                    val currentDisplay by display.collectAsState()
                    val currentTitleMode by titleMode.collectAsState()
                    TachiyomiPreviewTheme {
                        LocalLibraryFilterDialog(
                            filters = currentFilters, rememberFilters = false, isFolder = isFolder,
                            networkStorage = networkStorage, availableScanlators = setOf("Group A"),
                            libraryPreferences = preferences, displayMode = currentDisplay,
                            onDisplayModeChanged = { display.value = it }, onDismissRequest = {},
                            titleDisplayMode = currentTitleMode, onTitleDisplayModeChanged = { titleMode.value = it },
                            onFiltersChanged = { value, _ -> filters.value = value },
                        )
                    }
                }
            }
            awaitLabel(context.stringResource(MR.strings.action_filter))
            awaitLabel(context.stringResource(MR.strings.action_sort))
            awaitLabel(context.stringResource(MR.strings.action_display))
            SystemClock.sleep(350)
            saveScreenshot(
                if (networkStorage) {
                    "folder-network"
                } else if (isFolder) {
                    "folder"
                } else {
                    "outer"
                },
            )
            check()
        }
    }

    private fun clickLabel(label: String) {
        instrumentation.waitForIdleSync()
        var node = awaitLabel(label)
        while (!node.isClickable) node = node.parent ?: error("No action for $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(350)
    }

    private fun saveScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureSettingsScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), "local-filter-tabs-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitLabel(label: String): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        awaitCondition {
            match = find(instrumentation.uiAutomation.rootInActiveWindow, label)
            match != null
        }
        return checkNotNull(match)
    }

    private fun hasLabel(label: String): Boolean = find(instrumentation.uiAutomation.rootInActiveWindow, label) != null

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Local library settings did not reach the expected state", condition())
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
