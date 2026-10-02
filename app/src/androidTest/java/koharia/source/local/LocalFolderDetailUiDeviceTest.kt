package koharia.source.local

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalFolderDetailUiDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<EInkMotionFixtureActivity>()

    @Test
    fun folderHeaderMatchesSeriesMetadataAndRetainsContentActions() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "folder-detail-ui-").toFile().canonicalFile
        val id = 9_600_000_000_000L + System.currentTimeMillis()
        val repository: MangaRepository = Injekt.get()
        val preferences = LocalLibraryPreferences(id, Json)
        try {
            val cover = root.resolve("cover.png")
            val bitmap = Bitmap.createBitmap(360, 540, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(0xff274b65.toInt())
                drawCircle(245f, 150f, 100f, Paint().apply { color = 0xffffc56e.toInt() })
                drawText(
                    "SUNRISE",
                    30f,
                    390f,
                    Paint().apply {
                        color = -1
                        textSize = 52f
                    },
                )
            }
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            listOf("01 Sunrise.cbz", "02 Journey.cbz", "03 Nested/Inside.cbz").forEach { name ->
                val file = root.resolve("Collection/$name").apply { parentFile!!.mkdirs() }
                ZipOutputStream(file.outputStream()).use {
                    it.putNextEntry(ZipEntry("001.png"))
                    it.write(cover.readBytes())
                    it.closeEntry()
                }
            }
            preferences.setConfig(
                LocalLibraryConfig(
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
                            root.toURI().toString(),
                            contentType = LocalLibraryContentType.COMICS,
                            bookshelfId = "folders",
                        ),
                    ),
                ),
            )
            val source = LocalFolderSource(
                context,
                id,
                "Folders",
                LibraryConnectionProfile(id, LocalFolderConnectionProvider.ID, "Folders"),
            )
            source.refreshLibrary().getOrThrow()
            val all = repository.getMangaBySourceId(id)
            val folder = all.first { it.title == "Collection" }.copy(
                title = "Sunrise Collection",
                description = "A collection of illustrated journeys.",
                author = "Sunrise author",
                genre = listOf("sunrise", "folder"),
                thumbnailUrl = cover.absolutePath,
            )
            val entries = source.browseIndexedLibrary(
                all,
                "",
                parentUrl = folder.url,
            )
                .map { it.copy(thumbnailUrl = cover.absolutePath) }
            val paging = flowOf(PagingData.from(entries.map { MutableStateFlow(it) as StateFlow<Manga> }))
            var showFolder by mutableStateOf(true)
            var displayMode by mutableLongStateOf(Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE)
            var clicked: Manga? = null
            var imported = false
            composeRule.setContent {
                TachiyomiPreviewTheme {
                    key(showFolder, displayMode) {
                        val snackbar = remember { SnackbarHostState() }
                        if (showFolder) {
                            LocalFolderDetailContent(
                                folder = folder,
                                mangaList = paging.collectAsLazyPagingItems(),
                                source = source,
                                selectedIds = emptySet(),
                                displayMode = displayMode,
                                columns = 2,
                                readProgress = mapOf("another-folder" to MangaReadProgress(1, 2)),
                                showReadProgress = true,
                                showFileSize = false,
                                refreshing = false,
                                hasFilters = false,
                                error = null,
                                snackbarHostState = snackbar,
                                navigateUp = {},
                                onDisplayModeChange = { displayMode = it },
                                onFilter = {},
                                onRefresh = {},
                                onImport = { imported = true },
                                onEditSeriesDetails = {},
                                onEditNotes = {},
                                onCoverClick = {},
                                onSearch = {},
                                onSearchClick = {},
                                onReadAsComic = null,
                                onRecoverOperation = null,
                                onEntryClick = { clicked = it },
                                onContinueReading = {},
                                onEntryLongClick = {},
                                onSelectAll = {},
                                onInvertSelection = {},
                                onClearSelection = {},
                                onMarkRead = {},
                                onDelete = {},
                            )
                        } else {
                            MangaScreen(
                                state = MangaScreenModel.State.Success(
                                    manga = folder.withChapterCoverDisplayMode(displayMode),
                                    source = source,
                                    isFromSource = true,
                                    availableScanlators = emptySet(),
                                    excludedScanlators = emptySet(),
                                    chapters = entries.mapIndexed { index, manga ->
                                        ChapterList.Item(
                                            Chapter.create().copy(
                                                id = manga.id,
                                                mangaId = folder.id,
                                                name = manga.title,
                                                url = cover.absolutePath,
                                                dateUpload = source.indexedEntry(manga.url)?.modifiedAt ?: 0,
                                                sourceOrder = index.toLong(),
                                                chapterNumber = index.toDouble(),
                                            ),
                                            Download.State.NOT_DOWNLOADED,
                                            0,
                                        )
                                    },
                                ),
                                snackbarHostState = snackbar,
                                isTabletUi = false,
                                chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                chapterCoverGridColumns = 2,
                                showChapterReadProgress = true,
                                showChapterFileSize = false,
                                navigateUp = {},
                                onChapterClicked = {},
                                onDownloadChapter = null,
                                onAddToLibraryClicked = null,
                                onWebViewClicked = null,
                                onWebViewLongClicked = null,
                                onTagSearch = null,
                                onFilterButtonClicked = {},
                                onChapterCoverDisplayModeChange = {},
                                onRefresh = {},
                                onContinueReading = {},
                                onSearch = { _, _ -> },
                                onCoverClicked = {},
                                onShareClicked = null,
                                onDownloadActionClicked = null,
                                onEditSeriesDetailsClicked = {},
                                onEditCategoryClicked = null,
                                onMigrateClicked = null,
                                onEditNotesClicked = {},
                                onMultiBookmarkClicked = { _, _ -> },
                                onMultiMarkAsReadClicked = { _, _ -> },
                                onMarkPreviousAsReadClicked = {},
                                onMultiDeleteClicked = {},
                                onChapterSwipe = { _, _ -> },
                                onChapterSelected = { _, _, _ -> },
                                onAllChapterSelected = {},
                                onInvertSelection = {},
                            )
                        }
                    }
                }
            }
            val screenshots = File(context.getExternalFilesDir(null), "folder-series-ui").apply { mkdirs() }
            for ((name, mode) in listOf(
                "grid" to Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE,
                "list" to Manga.CHAPTER_COVER_DISPLAY_TEXT,
            )) {
                composeRule.runOnIdle {
                    displayMode = mode
                    showFolder = true
                }
                composeRule.waitUntil(10_000) {
                    runCatching { composeRule.onNodeWithText("01 Sunrise").fetchSemanticsNode() }.isSuccess
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Sunrise author").assertExists()
                composeRule.onNodeWithText("sunrise").assertExists()
                composeRule.onNodeWithText("A collection of illustrated journeys.").assertExists()
                composeRule.onNodeWithText(
                    context.stringResource(MR.strings.action_start),
                    useUnmergedTree = true,
                ).assertExists()
                val folderBounds = composeRule.onNodeWithText("01 Sunrise").fetchSemanticsNode().boundsInRoot
                capture(screenshots.resolve("folder-$name.png"))
                composeRule.onNodeWithContentDescription(context.stringResource(MR.strings.local_library_import_files))
                    .performClick()
                composeRule.runOnIdle { assertEquals(true, imported) }
                composeRule.onNodeWithText("03 Nested").performClick()
                composeRule.runOnIdle { assertEquals("03 Nested", clicked?.title) }
                composeRule.runOnIdle { showFolder = false }
                composeRule.waitForIdle()
                val seriesBounds = composeRule.onNodeWithText("01 Sunrise").fetchSemanticsNode().boundsInRoot
                capture(screenshots.resolve("series-$name.png"))
                assertEquals("$name content x", seriesBounds.left, folderBounds.left, 1f)
                assertEquals("$name content top", seriesBounds.top, folderBounds.top, 1f)
                assertEquals("$name content width", seriesBounds.width, folderBounds.width, 1f)
            }
        } finally {
            repository.deleteMangaBySourceId(id)
            context.getSharedPreferences("source_$id", 0).edit().clear().commit()
            check(root.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun capture(file: File) {
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
