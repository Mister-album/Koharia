package koharia.epub

import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationOverlayView
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import koharia.epub.service.EpubReaderSupportResolution
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubPreferencesBridge
import koharia.epub.settings.EpubReaderPreferences
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.toUri
import tachiyomi.core.common.Constants
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.ScopedPreferenceStore
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderActivity : BaseActivity(), EpubReaderFragment.Host {

    companion object {
        private const val READER_EDGE_PADDING_DP = 8
        private const val PAGINATION_SETTINGS_DEBOUNCE_MS = 250L
        private const val PAGINATION_VIEWPORT_DEBOUNCE_MS = 250L
        private const val PROGRESSION_SEEK_DEBOUNCE_MS = 100L

        fun newIntent(
            context: Context,
            mangaId: Long?,
            chapterId: Long?,
            sourceId: Long? = null,
            resolution: EpubReaderSupportResolution? = null,
        ): Intent {
            return Intent(context, EpubReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                sourceId?.let { putExtra("source", it) }
                resolution?.let {
                    putExtra("epub_resolution_chapter", it.chapterId)
                    putExtra("epub_resolution_source", it.sourceId)
                    putExtra("epub_resolution_local_uri", it.localUri)
                    putExtra("epub_resolution_remote_url", it.remoteBookUrl)
                    putExtra("epub_resolution_open_source", it.preferredOpenSource?.name)
                    putExtra("epub_resolution_publication_key", it.publicationKey)
                    putExtra("epub_resolution_file_name", it.bookFileName)
                    it.bookSizeBytes?.let { size -> putExtra("epub_resolution_file_size", size) }
                    putExtra("epub_resolution_manual_download", it.isManualDownload)
                    putExtra("epub_resolution_complete_cache", it.isCompleteCache)
                    putExtra("epub_resolution_divina", it.isDivinaCompatible)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val viewModel by viewModels<EpubReaderViewModel>()
    private val scopedPreferenceStoreFactory = Injekt.get<KomgaScopedPreferenceStoreFactory>()
    private val sessionRepository = Injekt.get<EpubReaderSessionRepository>()
    private val sourceId by lazy { intent.extras?.getLong("source", -1L) ?: -1L }
    private val basePreferences by lazy {
        sourceId
            .takeIf { it > 0L }
            ?.let(scopedPreferenceStoreFactory::basePreferences)
            ?: Injekt.get<BasePreferences>()
    }
    private val persistentReaderSettingsStore: PreferenceStore by lazy {
        if (sourceId > 0L) {
            scopedPreferenceStoreFactory.storeForServer(sourceId)
        } else {
            Injekt.get<ScopedPreferenceStore>()
        }
    }
    private val epubReaderPreferences by lazy {
        EpubReaderPreferences(persistentReaderSettingsStore)
    }
    private val persistentReaderPreferences by lazy {
        ReaderPreferences(persistentReaderSettingsStore)
    }
    private val readerSettingsStore by lazy {
        viewModel.readerSettingsStore(
            backingStore = persistentReaderSettingsStore,
            persistChanges = epubReaderPreferences.persistReaderSettingsChanges.get(),
        )
    }
    private val epubLayoutPreferences by lazy {
        EpubLayoutPreferences(readerSettingsStore)
    }
    private val readerPreferences by lazy {
        ReaderPreferences(readerSettingsStore)
    }
    private val epubPreferencesBridge = EpubPreferencesBridge()
    private val epubReaderLauncher by lazy { EpubReaderLauncher() }
    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }
    private var isReaderResumed = false
    private var paginationViewport: EpubPaginationViewport? = null
    private var paginationViewportJob: Job? = null
    private var paginationJob: Job? = null
    private var progressionSeekJob: Job? = null
    private var progressionSeekGeneration = 0L
    private var currentPublisherStyles: Boolean? = null
    private var readerFragment: EpubReaderFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)
        removeRestoredReaderWithoutSession(savedInstanceState)

        viewModel.setPublisherStylesOverride(
            epubLayoutPreferences.publisherStyles.get()
                .takeUnless { epubReaderPreferences.persistReaderSettingsChanges.get() },
        )

        epubReaderPreferences.persistReaderSettingsChanges.changes()
            .onEach { enabled ->
                viewModel.setPersistReaderSettingsChanges(enabled)
                viewModel.setPublisherStylesOverride(
                    epubLayoutPreferences.publisherStyles.get().takeUnless { enabled },
                )
            }
            .launchIn(lifecycleScope)

        setComposeContent {
            val state by viewModel.state.collectAsState()
            val tocEntries =
                remember(state.chapterId, state.sessionToken, state.isReady) { viewModel.tableOfContents() }
            val adjacentTocEntries = remember(tocEntries, state.currentPosition, state.currentHref) {
                viewModel.adjacentTocEntries(tocEntries, state.currentPosition, state.currentHref)
            }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var activePanel by rememberSaveable { mutableStateOf(EpubBottomPanel.NONE) }
            var showBookInfoDialog by rememberSaveable { mutableStateOf(false) }
            val currentTheme by epubLayoutPreferences.theme.changes().collectAsState(epubLayoutPreferences.theme.get())
            val currentCustomBackgroundColor by epubLayoutPreferences.customBackgroundColor.changes()
                .collectAsState(epubLayoutPreferences.customBackgroundColor.get())
            val currentReaderBackgroundColor = remember(currentTheme, currentCustomBackgroundColor) {
                Color(currentTheme.readerBackgroundColor(currentCustomBackgroundColor))
            }
            val currentReadingMode by epubLayoutPreferences.readingMode.changes()
                .collectAsState(epubLayoutPreferences.readingMode.get())
            val currentPageDirection by epubLayoutPreferences.pageDirection.changes()
                .collectAsState(epubLayoutPreferences.pageDirection.get())
            val currentVerticalMargins by epubLayoutPreferences.verticalMargins.changes()
                .collectAsState(epubLayoutPreferences.verticalMargins.get())
            val fullscreenVerticalPadding = remember(currentVerticalMargins) {
                readerVerticalPaddingDp(currentVerticalMargins).dp
            }
            val navigationModeWebtoon by readerPreferences.navigationModeWebtoon.changes()
                .collectAsState(readerPreferences.navigationModeWebtoon.get())
            val navigationModePager by readerPreferences.navigationModePager.changes()
                .collectAsState(readerPreferences.navigationModePager.get())
            val currentNavigationMode = when (currentReadingMode) {
                EpubLayoutPreferences.ReadingMode.SCROLL -> navigationModeWebtoon
                EpubLayoutPreferences.ReadingMode.PAGINATED -> navigationModePager
            }
            val webtoonNavInverted by readerPreferences.webtoonNavInverted.changes()
                .collectAsState(readerPreferences.webtoonNavInverted.get())
            val pagerNavInverted by readerPreferences.pagerNavInverted.changes()
                .collectAsState(readerPreferences.pagerNavInverted.get())
            val currentInvertMode = when (currentReadingMode) {
                EpubLayoutPreferences.ReadingMode.SCROLL -> webtoonNavInverted
                EpubLayoutPreferences.ReadingMode.PAGINATED -> pagerNavInverted
            }
            val showNavigationOverlayOnStart by readerPreferences.showNavigationOverlayOnStart.changes()
                .collectAsState(readerPreferences.showNavigationOverlayOnStart.get())
            val forceNavigationOverlay = remember { readerPreferences.showNavigationOverlayNewUser.get() }
            val fullscreen by readerPreferences.fullscreen.changes().collectAsState(readerPreferences.fullscreen.get())
            val drawUnderCutout by readerPreferences.drawUnderCutout.changes()
                .collectAsState(readerPreferences.drawUnderCutout.get())
            val showReadingProgress by readerPreferences.showPageNumber.changes()
                .collectAsState(readerPreferences.showPageNumber.get())
            val customBrightnessEnabled by readerPreferences.customBrightness.changes()
                .collectAsState(readerPreferences.customBrightness.get())
            val customBrightnessValue by readerPreferences.customBrightnessValue.changes()
                .collectAsState(readerPreferences.customBrightnessValue.get())
            val brightnessState = remember(customBrightnessEnabled, customBrightnessValue) {
                calculateEpubBrightness(
                    enabled = customBrightnessEnabled,
                    value = customBrightnessValue,
                )
            }
            val colorOverlayEnabled by readerPreferences.colorFilter.changes()
                .collectAsState(readerPreferences.colorFilter.get())
            val colorOverlay by readerPreferences.colorFilterValue.changes()
                .collectAsState(readerPreferences.colorFilterValue.get())
            val colorOverlayMode by readerPreferences.colorFilterMode.changes()
                .collectAsState(readerPreferences.colorFilterMode.get())
            val colorOverlayBlendMode = remember(colorOverlayMode) {
                ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
            }
            val grayscale by readerPreferences.grayscale.changes().collectAsState(readerPreferences.grayscale.get())
            val invertedColors by readerPreferences.invertedColors.changes()
                .collectAsState(readerPreferences.invertedColors.get())
            val subtitle = remember(state.chapterTitle, state.currentSectionTitle, state.progressionPercent) {
                listOfNotNull(
                    state.chapterTitle,
                    state.currentSectionTitle,
                    state.progressionPercent?.let { "$it%" },
                ).joinToString(" / ").takeIf { it.isNotBlank() }
            }

            LaunchedEffect(state.menuVisible) {
                if (!state.menuVisible) {
                    activePanel = EpubBottomPanel.NONE
                    drawerState.close()
                }
            }

            LaunchedEffect(brightnessState) {
                applyEpubBrightness(brightnessState)
            }

            DisposableEffect(Unit) {
                onDispose(::resetEpubBrightness)
            }

            LaunchedEffect(forceNavigationOverlay) {
                if (forceNavigationOverlay) {
                    persistentReaderPreferences.showNavigationOverlayNewUser.set(false)
                    readerPreferences.showNavigationOverlayNewUser.set(false)
                }
            }

            BackHandler(
                enabled =
                drawerState.isOpen || activePanel != EpubBottomPanel.NONE || state.isSearchActive ||
                    state.menuVisible,
            ) {
                when {
                    drawerState.isOpen -> scope.launch { drawerState.close() }
                    activePanel != EpubBottomPanel.NONE -> activePanel = EpubBottomPanel.NONE
                    state.isSearchActive -> viewModel.closeSearch()
                    state.menuVisible -> viewModel.showMenus(false)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = state.menuVisible && !state.isSearchActive,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.65f)
                                .widthIn(max = 420.dp),
                        ) {
                            EpubNavigationDrawer(
                                bookTitle = state.chapterTitle,
                                entries = tocEntries,
                                bookmarks = state.bookmarks,
                                currentHref = state.currentHref,
                                onSelectTocEntry = { entry ->
                                    epubReaderFragment()?.goTo(entry.link)
                                    scope.launch { drawerState.close() }
                                },
                                onSelectBookmark = { bookmark ->
                                    viewModel.locatorFromBookmark(bookmark)?.let { locator ->
                                        epubReaderFragment()?.goTo(locator)
                                    }
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteBookmark = { bookmark -> viewModel.deleteBookmark(bookmark.id) },
                                onUpdateBookmarkNote = { bookmark, note ->
                                    viewModel.updateBookmarkNote(bookmark.id, note)
                                },
                            )
                        }
                    },
                ) {
                    Scaffold(
                        contentWindowInsets = WindowInsets(0),
                        topBar = {
                            if (state.isSearchActive) {
                                SearchToolbar(
                                    searchQuery = state.searchQuery,
                                    onChangeSearchQuery = { query ->
                                        if (query == null) {
                                            viewModel.closeSearch()
                                        } else {
                                            viewModel.updateSearchQuery(query)
                                        }
                                    },
                                    titleContent = {
                                        AppBarTitle(state.mangaTitle, subtitle = subtitle)
                                    },
                                    navigateUp = ::finish,
                                    searchEnabled = state.isSearchable,
                                    placeholderText = stringResource(MR.strings.epub_reader_search_hint),
                                    onClickCloseSearch = viewModel::closeSearch,
                                    onSearch = { viewModel.submitSearch() },
                                )
                            }
                        },
                        bottomBar = {
                            EpubReaderBottomArea(
                                visible = state.menuVisible && state.errorMessage == null && !state.isSearchActive,
                                activePanel = activePanel,
                                preferences = epubLayoutPreferences,
                                readerPreferences = readerPreferences,
                                epubReaderPreferences = epubReaderPreferences,
                                chapterNavigatorType = if (
                                    currentReadingMode == EpubLayoutPreferences.ReadingMode.PAGINATED &&
                                    currentPageDirection == EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT
                                ) {
                                    ChapterNavigatorType.HORIZONTAL_RTL
                                } else {
                                    ChapterNavigatorType.HORIZONTAL_LTR
                                },
                                currentPosition = state.currentPosition,
                                totalPositions = state.totalPositions,
                                progression = state.progression,
                                currentVisualPage = state.currentVisualPage
                                    ?.takeIf { currentReadingMode == EpubLayoutPreferences.ReadingMode.PAGINATED },
                                totalVisualPages = state.totalVisualPages
                                    ?.takeIf { currentReadingMode == EpubLayoutPreferences.ReadingMode.PAGINATED },
                                enabledPreviousChapter = adjacentTocEntries.first != null ||
                                    state.previousBookChapterId != null,
                                enabledNextChapter = adjacentTocEntries.second != null ||
                                    state.nextBookChapterId != null,
                                onPositionChange = { index ->
                                    viewModel.locatorAtPosition(index)?.let { locator ->
                                        epubReaderFragment()?.goTo(locator)
                                    }
                                },
                                onProgressionChange = { progression ->
                                    val generation = ++progressionSeekGeneration
                                    progressionSeekJob?.cancel()
                                    progressionSeekJob = lifecycleScope.launch {
                                        delay(PROGRESSION_SEEK_DEBOUNCE_MS)
                                        val locator = viewModel.locatorAtProgression(progression)
                                        if (generation != progressionSeekGeneration) return@launch
                                        if (locator == null) {
                                            logcat(LogPriority.WARN) {
                                                "EPUB progression seek unresolved generation=$generation " +
                                                    "target=$progression"
                                            }
                                            viewModel.restoreCurrentProgressDisplay()
                                            return@launch
                                        }
                                        viewModel.previewProgressionSeek(progression, locator)
                                        val accepted = epubReaderFragment()?.goTo(locator) == true
                                        logcat(LogPriority.DEBUG) {
                                            "EPUB progression seek submitted generation=$generation " +
                                                "target=$progression href=${locator.href} accepted=$accepted"
                                        }
                                        if (!accepted && generation == progressionSeekGeneration) {
                                            viewModel.restoreCurrentProgressDisplay()
                                        }
                                    }
                                },
                                onPreviousChapter = {
                                    val previousSection = adjacentTocEntries.first
                                    if (previousSection != null) {
                                        epubReaderFragment()?.goTo(previousSection.link)
                                    } else {
                                        state.previousBookChapterId?.let(::openAdjacentBook)
                                    }
                                },
                                onNextChapter = {
                                    val nextSection = adjacentTocEntries.second
                                    if (nextSection != null) {
                                        epubReaderFragment()?.goTo(nextSection.link)
                                    } else {
                                        state.nextBookChapterId?.let(::openAdjacentBook)
                                    }
                                },
                                onOpenContents = {
                                    activePanel = EpubBottomPanel.NONE
                                    scope.launch {
                                        if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                    }
                                },
                                onToggleNightMode = {
                                    val target = if (currentTheme == EpubLayoutPreferences.Theme.DARK) {
                                        EpubLayoutPreferences.Theme.LIGHT
                                    } else {
                                        EpubLayoutPreferences.Theme.DARK
                                    }
                                    epubLayoutPreferences.theme.set(target)
                                },
                                onToggleSettings = {
                                    activePanel = if (activePanel == EpubBottomPanel.SETTINGS) {
                                        EpubBottomPanel.NONE
                                    } else {
                                        EpubBottomPanel.SETTINGS
                                    }
                                },
                                onToggleMore = {
                                    activePanel = if (activePanel == EpubBottomPanel.MORE) {
                                        EpubBottomPanel.NONE
                                    } else {
                                        EpubBottomPanel.MORE
                                    }
                                },
                                morePanel = {
                                    EpubReaderMorePanel(
                                        state = state,
                                        onOpenAsPages = {
                                            activePanel = EpubBottomPanel.NONE
                                            startActivity(
                                                ReaderActivity.newIntent(
                                                    this@EpubReaderActivity,
                                                    state.mangaId,
                                                    state.chapterId,
                                                    sourceId,
                                                ),
                                            )
                                            finish()
                                        },
                                        onReload = {
                                            activePanel = EpubBottomPanel.NONE
                                            viewModel.retry()?.let { (mangaId, chapterId) ->
                                                lifecycleScope.launch {
                                                    viewModel.init(mangaId, chapterId)
                                                }
                                            }
                                        },
                                        onOpenExternal = {
                                            activePanel = EpubBottomPanel.NONE
                                            openEpubInExternalApp(state.localEpubUri)
                                        },
                                        onShowBookInfo = {
                                            activePanel = EpubBottomPanel.NONE
                                            showBookInfoDialog = true
                                        },
                                    )
                                },
                            )
                        },
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (state.isReady && state.chapterId > 0) {
                                ReaderFragmentContainer(
                                    chapterId = state.chapterId,
                                    sourceId = sourceId,
                                    sessionToken = state.sessionToken,
                                    modifier = Modifier.fillMaxSize(),
                                    fullscreen = fullscreen,
                                    drawUnderCutout = drawUnderCutout,
                                    grayscale = grayscale,
                                    invertedColors = invertedColors,
                                    theme = currentTheme,
                                    customBackgroundColor = currentCustomBackgroundColor,
                                    verticalMargins = currentVerticalMargins,
                                )

                                // Readium applies padding to a white Fragment container. Paint only the reserved
                                // fullscreen edges here; visible reader menus must keep their Material theme colors.
                                if (fullscreen && !state.menuVisible) {
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .background(currentReaderBackgroundColor),
                                    ) {
                                        Spacer(
                                            modifier = Modifier.windowInsetsTopHeight(WindowInsets.displayCutout),
                                        )
                                        Spacer(modifier = Modifier.height(fullscreenVerticalPadding))
                                    }
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(currentReaderBackgroundColor),
                                    ) {
                                        Spacer(modifier = Modifier.height(fullscreenVerticalPadding))
                                        if (!drawUnderCutout) {
                                            Spacer(
                                                modifier = Modifier.windowInsetsBottomHeight(
                                                    WindowInsets.displayCutout,
                                                ),
                                            )
                                        }
                                    }
                                }

                                ReaderContentOverlay(
                                    brightness = 0,
                                    color = colorOverlay.takeIf { colorOverlayEnabled },
                                    colorBlendMode = colorOverlayBlendMode,
                                )

                                EpubNavigationOverlay(
                                    readingMode = currentReadingMode,
                                    navigationMode = currentNavigationMode,
                                    invertMode = currentInvertMode,
                                    showOnStart = showNavigationOverlayOnStart || forceNavigationOverlay,
                                )

                                if (!state.menuVisible && showReadingProgress) {
                                    EpubReadingProgressIndicator(
                                        progressPercent = state.progressionPercent,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .navigationBarsPadding()
                                            .padding(bottom = 8.dp),
                                    )
                                }

                                EpubReaderTopBar(
                                    visible = state.menuVisible && !state.isSearchActive,
                                    title = state.mangaTitle,
                                    subtitle = subtitle,
                                    isSearchable = state.isSearchable,
                                    isBookmarked = state.currentBookmarkId != null,
                                    bookmarkEnabled = !state.isIncognito && state.isReady,
                                    onNavigateUp = ::finish,
                                    onClick = ::openMangaScreen,
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    onSearch = {
                                        activePanel = EpubBottomPanel.NONE
                                        viewModel.openSearch()
                                    },
                                    onToggleBookmark = {
                                        activePanel = EpubBottomPanel.NONE
                                        viewModel.toggleBookmarkAtCurrentLocator()
                                    },
                                )
                            }

                            when {
                                state.isLoading -> {
                                    LoadingScreen(Modifier.fillMaxSize())
                                }
                                state.errorMessage != null -> {
                                    ErrorContent(
                                        message = state.errorMessage.orEmpty(),
                                        onRetry = {
                                            viewModel.retry()?.let { (mangaId, chapterId) ->
                                                lifecycleScope.launch {
                                                    viewModel.init(mangaId, chapterId)
                                                }
                                            }
                                        },
                                        onOpenAsPages = {
                                            startActivity(
                                                ReaderActivity.newIntent(
                                                    this@EpubReaderActivity,
                                                    state.mangaId,
                                                    state.chapterId,
                                                    sourceId,
                                                ),
                                            )
                                            finish()
                                        }.takeIf { state.canOpenAsPages && state.mangaId > 0 && state.chapterId > 0 },
                                    )
                                }
                            }
                        }

                        if (state.isSearchActive && state.isSearchSubmitted) {
                            EpubSearchSheet(
                                query = state.searchQuery,
                                brightnessState = brightnessState,
                                isLoading = state.isSearchLoading,
                                results = state.searchResults,
                                errorMessage = state.searchErrorMessage,
                                onDismissRequest = viewModel::dismissSearchResults,
                                onSelectResult = { result ->
                                    epubReaderFragment()?.goTo(result.locator)
                                    viewModel.closeSearch()
                                },
                            )
                        }
                    }
                }

                ReaderContentOverlay(
                    brightness = brightnessState.overlayValue,
                    color = null,
                    colorBlendMode = null,
                )
            }

            state.serverTimeOffsetMinutes?.let { offsetMinutes ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissServerTimeWarning,
                    title = { Text(stringResource(MR.strings.epub_reader_server_time_warning_title)) },
                    text = {
                        Text(
                            stringResource(
                                MR.strings.epub_reader_server_time_warning_message,
                                offsetMinutes,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissServerTimeWarning) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                )
            }

            state.remoteProgressConflict?.let { conflict ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissRemoteProgressConflict,
                    title = { Text(stringResource(MR.strings.epub_reader_remote_progress_title)) },
                    text = {
                        Text(
                            buildString {
                                append(
                                    stringResource(
                                        MR.strings.epub_reader_remote_progress_message,
                                        conflict.progressionPercent ?: 0,
                                    ),
                                )
                                conflict.sectionTitle?.takeIf { it.isNotBlank() }?.let {
                                    append("\n")
                                    append(it)
                                }
                            },
                        )
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::dismissRemoteProgressConflict) {
                            Text(stringResource(MR.strings.epub_reader_keep_local_progress))
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.acceptRemoteProgress()?.let { locator ->
                                    epubReaderFragment()?.goTo(locator)
                                }
                            },
                        ) {
                            Text(stringResource(MR.strings.epub_reader_jump_remote_progress))
                        }
                    },
                )
            }

            if (showBookInfoDialog) {
                EpubBookInfoDialog(
                    state = state,
                    onDismissRequest = { showBookInfoDialog = false },
                )
            }
        }

        if (viewModel.needsInit()) {
            val mangaId = intent.extras?.getLong("manga", -1L) ?: -1L
            val chapterId = intent.extras?.getLong("chapter", -1L) ?: -1L
            if (mangaId <= 0 || chapterId <= 0) {
                finish()
                return
            }

            lifecycleScope.launchNonCancellable {
                val initResult = viewModel.init(mangaId, chapterId)
                if (initResult.isFailure) {
                    withUIContext { updateSystemBars(viewModel.state.value.menuVisible) }
                }
            }
        }

        setKeepScreenOn(readerPreferences.keepScreenOn.get())
        readerPreferences.keepScreenOn.changes()
            .onEach(::setKeepScreenOn)
            .launchIn(lifecycleScope)

        applyOrientation(readerPreferences.defaultOrientationType.get())
        readerPreferences.defaultOrientationType.changes()
            .onEach(::applyOrientation)
            .launchIn(lifecycleScope)

        readerPreferences.fullscreen.changes()
            .onEach { updateSystemBars(viewModel.state.value.menuVisible) }
            .launchIn(lifecycleScope)

        basePreferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        observeEpubLayoutPreferences()

        viewModel.state
            .map { it.menuVisible }
            .distinctUntilChanged()
            .onEach(::updateSystemBars)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.paginationSourceVersion }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                if (viewModel.state.value.isReady) {
                    epubReaderFragment()?.stopPagination()
                    applyCurrentReadiumPreferences()
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onPause() {
        isReaderResumed = false
        paginationViewportJob?.cancel()
        paginationJob?.cancel()
        progressionSeekGeneration += 1
        progressionSeekJob?.cancel()
        epubReaderFragment()?.stopPagination()
        lifecycleScope.launchNonCancellable {
            viewModel.saveCurrentProgress()
            viewModel.updateHistory()
        }
        resetEpubBrightness()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        isReaderResumed = true
        applyCurrentEpubBrightness()
        updateSystemBars(viewModel.state.value.menuVisible)
        if (viewModel.state.value.isReady &&
            paginationViewport != null &&
            paginationViewportJob?.isActive != true
        ) {
            applyCurrentReadiumPreferences()
        }
    }

    override fun onLowMemory() {
        epubReaderFragment()?.stopPagination()
        lifecycleScope.launchNonCancellable { viewModel.saveCurrentProgress() }
        super.onLowMemory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateSystemBars(viewModel.state.value.menuVisible)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.releaseSession()
        }
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
    }

    override fun onTap(positionX: Float, positionY: Float): Boolean {
        val isRightToLeft = epubLayoutPreferences.readingMode.get() == EpubLayoutPreferences.ReadingMode.PAGINATED &&
            epubLayoutPreferences.pageDirection.get() == EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT
        return when (resolveNavigationAction(positionX, positionY)) {
            ViewerNavigation.NavigationRegion.MENU -> {
                viewModel.showMenus(!viewModel.state.value.menuVisible)
                true
            }
            ViewerNavigation.NavigationRegion.NEXT,
            -> epubReaderFragment()?.goForward() ?: false

            ViewerNavigation.NavigationRegion.PREV,
            -> epubReaderFragment()?.goBackward() ?: false

            ViewerNavigation.NavigationRegion.RIGHT -> {
                if (isRightToLeft) {
                    epubReaderFragment()?.goBackward() ?: false
                } else {
                    epubReaderFragment()?.goForward() ?: false
                }
            }
            ViewerNavigation.NavigationRegion.LEFT -> {
                if (isRightToLeft) {
                    epubReaderFragment()?.goForward() ?: false
                } else {
                    epubReaderFragment()?.goBackward() ?: false
                }
            }
        }
    }

    override fun onLocatorChanged(locator: Locator) {
        viewModel.updateLocator(locator)
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
        viewModel.onFirstContentDisplayed()
        viewModel.updateVisualPage(pageIndex, totalPages, locator)
    }

    override fun onBookPaginationChanged(
        generation: Long,
        pageCounts: Map<String, Int>,
        isComplete: Boolean,
    ) {
        viewModel.updateBookPagination(generation, pageCounts, isComplete)
        if (isComplete && generation == viewModel.state.value.paginationGeneration) {
            viewModel.currentLocator()?.let { locator ->
                epubReaderFragment()?.goTo(locator)
            }
        }
    }

    override fun onPaginationViewportChanged(viewport: EpubPaginationViewport) {
        val previousViewport = paginationViewport
        if (viewport == previousViewport) return
        val state = viewModel.state.value
        logcat(LogPriority.DEBUG) {
            "EPUB pagination viewport changed old=$previousViewport new=$viewport " +
                "ready=${state.isReady} phase=${state.paginationPhase} " +
                "pages=${state.currentVisualPage}/${state.totalVisualPages}"
        }
        paginationViewport = viewport
        paginationViewportJob?.cancel()
        if (state.isReady) {
            paginationJob?.cancel()
            viewModel.invalidatePaginationDisplay()
            epubReaderFragment()?.stopPagination()
            paginationViewportJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(PAGINATION_VIEWPORT_DEBOUNCE_MS)
                if (paginationViewport == viewport && viewModel.state.value.isReady) {
                    logcat(LogPriority.DEBUG) { "EPUB pagination viewport settled viewport=$viewport" }
                    applyCurrentReadiumPreferences()
                }
            }
        }
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        openInBrowser(url.toUri(), forceDefaultBrowser = false)
    }

    override fun onNavigatorReady(fragment: EpubReaderFragment) {
        readerFragment = fragment
        if (paginationViewportJob?.isActive != true) {
            applyCurrentReadiumPreferences(fragment)
        }
    }

    override fun onSessionMissing(chapterId: Long) {
        viewModel.onSessionMissing(chapterId)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val state = viewModel.state.value
        if (!isVolumeKey || !epubLayoutPreferences.readWithVolumeKeys.get() ||
            state.menuVisible || state.isSearchActive
        ) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            val forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) !=
                epubLayoutPreferences.readWithVolumeKeysInverted.get()
            if (forward) {
                epubReaderFragment()?.goForward()
            } else {
                epubReaderFragment()?.goBackward()
            }
            return true
        }
        return true
    }

    private fun updateSystemBars(visible: Boolean) {
        if (visible || !readerPreferences.fullscreen.get()) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyEpubBrightness(state: EpubBrightnessState) {
        if (!isReaderResumed) return

        window.attributes = window.attributes.apply {
            screenBrightness = state.windowBrightness
                ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun applyCurrentEpubBrightness() {
        applyEpubBrightness(
            calculateEpubBrightness(
                enabled = readerPreferences.customBrightness.get(),
                value = readerPreferences.customBrightnessValue.get(),
            ),
        )
    }

    private fun resetEpubBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun applyOrientation(preference: Int) {
        requestedOrientation = ReaderOrientation.fromPreference(preference).flag
    }

    private fun observeEpubLayoutPreferences() {
        currentPublisherStyles = epubLayoutPreferences.publisherStyles.get()

        combine(
            epubLayoutPreferences.theme.changes(),
            epubLayoutPreferences.customBackgroundColor.changes(),
        ) { theme, customBackgroundColor -> theme to customBackgroundColor }
            .distinctUntilChanged()
            .onEach {
                epubReaderFragment()?.submitPreferences(
                    epubPreferencesBridge.toReadiumPreferences(epubLayoutPreferences),
                )
            }
            .launchIn(lifecycleScope)

        val paginationAffectingChanges = listOf(
            epubLayoutPreferences.readingMode.changes().map { it as Any },
            epubLayoutPreferences.pageDirection.changes().map { it as Any },
            epubLayoutPreferences.fontSize.changes().map { it as Any },
            epubLayoutPreferences.lineHeight.changes().map { it as Any },
            epubLayoutPreferences.paragraphSpacing.changes().map { it as Any },
            epubLayoutPreferences.paragraphIndent.changes().map { it as Any },
            epubLayoutPreferences.pageMargins.changes().map { it as Any },
            epubLayoutPreferences.verticalMargins.changes().map { it as Any },
            epubLayoutPreferences.fontFamily.changes().map { it as Any },
            epubLayoutPreferences.publisherStyles.changes().map { it as Any },
        )
        combine(paginationAffectingChanges) { values -> values.toList() }
            .distinctUntilChanged()
            .drop(1)
            .onEach { values ->
                val state = viewModel.state.value
                logcat(LogPriority.DEBUG) {
                    "EPUB pagination settings changed values=$values phase=${state.paginationPhase} " +
                        "pages=${state.currentVisualPage}/${state.totalVisualPages}"
                }
                viewModel.onLayoutPreferencesChanged()
                paginationJob?.cancel()
                viewModel.invalidatePaginationDisplay()
                epubReaderFragment()?.stopPagination()
            }
            .debounce(PAGINATION_SETTINGS_DEBOUNCE_MS)
            .onEach { values ->
                val publisherStyles = values.last() as Boolean
                val shouldReload = publisherStyles != currentPublisherStyles
                currentPublisherStyles = publisherStyles
                if (shouldReload) {
                    reloadEpubSessionForPublisherStyles(publisherStyles)
                } else {
                    applyCurrentReadiumPreferences()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun applyCurrentReadiumPreferences(fragment: EpubReaderFragment? = epubReaderFragment()) {
        val activeFragment = fragment?.takeIf { it.isAdded && it.view != null } ?: return
        val viewport = paginationViewport ?: return
        val preferences = epubPreferencesBridge.toReadiumPreferences(epubLayoutPreferences)
        activeFragment.submitPreferences(preferences)
        paginationJob?.cancel()
        paginationJob = lifecycleScope.launch {
            val snapshot = EpubPaginationLayoutSnapshot.from(epubLayoutPreferences, viewport)
            val stateBefore = viewModel.state.value
            logcat(LogPriority.DEBUG) {
                "EPUB pagination prepare requested key=${snapshot.key.take(12)} viewport=$viewport " +
                    "phase=${stateBefore.paginationPhase} " +
                    "pages=${stateBefore.currentVisualPage}/${stateBefore.totalVisualPages}"
            }
            val request = viewModel.preparePagination(snapshot)
            val stateAfter = viewModel.state.value
            logcat(LogPriority.DEBUG) {
                "EPUB pagination prepare completed key=${snapshot.key.take(12)} " +
                    "generation=${request.generation} shouldScan=${request.shouldScan} " +
                    "phase=${stateAfter.paginationPhase} " +
                    "pages=${stateAfter.currentVisualPage}/${stateAfter.totalVisualPages}"
            }
            if (request.generation == viewModel.state.value.paginationGeneration &&
                activeFragment.isAdded && activeFragment.view != null && readerFragment === activeFragment
            ) {
                activeFragment.startPagination(request)
                if (!request.shouldScan &&
                    viewModel.state.value.paginationPhase != EpubPaginationPhase.UNAVAILABLE
                ) {
                    viewModel.currentLocator()?.let(activeFragment::goTo)
                }
            }
        }
    }

    private fun reloadEpubSessionForPublisherStyles(enabled: Boolean) {
        val state = viewModel.state.value
        if (!state.isReady || state.mangaId <= 0L || state.chapterId <= 0L) return

        logcat(LogPriority.DEBUG) {
            "EPUB reload for publisher styles enabled=$enabled chapterId=${state.chapterId}"
        }
        if (!epubReaderPreferences.persistReaderSettingsChanges.get()) {
            viewModel.setPublisherStylesOverride(enabled)
        }
        lifecycleScope.launch {
            viewModel.init(
                mangaId = state.mangaId,
                chapterId = state.chapterId,
                preserveLocalProgressAfterLayoutChange = true,
            )
        }
    }

    internal fun sessionEpubLayoutPreferences(): EpubLayoutPreferences = epubLayoutPreferences

    private fun openEpubInExternalApp(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            toast(MR.strings.epub_reader_external_app_requires_download)
            return
        }

        runCatching {
            val sourceUri = Uri.parse(uriString)
            val uri = if (sourceUri.scheme == ContentResolver.SCHEME_FILE) {
                File(checkNotNull(sourceUri.path)).getUriCompat(this)
            } else {
                sourceUri
            }
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/epub+zip")
                clipData = ClipData.newRawUri("EPUB", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                Intent.createChooser(
                    openIntent,
                    null,
                ),
            )
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Unable to open EPUB in another app: $uriString" }
            toast(MR.strings.epub_reader_external_open_failed)
        }
    }

    private fun epubReaderFragment(): EpubReaderFragment? {
        return readerFragment?.takeIf { it.isAdded && it.view != null }
    }

    private fun openAdjacentBook(chapterId: Long) {
        val mangaId = viewModel.state.value.mangaId.takeIf { it > 0L } ?: return
        lifecycleScope.launch {
            viewModel.saveCurrentProgress()
            val targetIntent = epubReaderLauncher.resolveIntent(
                context = this@EpubReaderActivity,
                mangaId = mangaId,
                chapterId = chapterId,
            )
            startActivity(targetIntent)
            finish()
        }
    }

    private fun openMangaScreen() {
        lifecycleScope.launch {
            val detailsMangaId = viewModel.resolveDetailsMangaId() ?: return@launch
            logcat(LogPriority.DEBUG) {
                val sourceId = intent.extras?.getLong("source", -1L)
                "EPUB openMangaScreen detailsMangaId=$detailsMangaId readerMangaId=${viewModel.state.value.mangaId} chapterId=${viewModel.state.value.chapterId} sourceId=$sourceId"
            }
            startActivity(
                Intent(this@EpubReaderActivity, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, detailsMangaId)
                    putExtra(Constants.FROM_SOURCE_EXTRA, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun resolveNavigationAction(
        positionX: Float,
        positionY: Float,
    ): ViewerNavigation.NavigationRegion {
        val readingMode = epubLayoutPreferences.readingMode.get()
        val navigationMode = if (readingMode == EpubLayoutPreferences.ReadingMode.SCROLL) {
            readerPreferences.navigationModeWebtoon.get()
        } else {
            readerPreferences.navigationModePager.get()
        }
        val invertMode = if (readingMode == EpubLayoutPreferences.ReadingMode.SCROLL) {
            readerPreferences.webtoonNavInverted.get()
        } else {
            readerPreferences.pagerNavInverted.get()
        }
        val navigator = createEpubNavigation(
            readingMode = readingMode,
            navigationMode = navigationMode,
            invertMode = invertMode,
        )
        return navigator.getAction(android.graphics.PointF(positionX, positionY))
    }

    @Composable
    private fun ReaderFragmentContainer(
        chapterId: Long,
        sourceId: Long,
        sessionToken: Long,
        modifier: Modifier = Modifier,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
        grayscale: Boolean,
        invertedColors: Boolean,
        theme: EpubLayoutPreferences.Theme,
        customBackgroundColor: Int,
        verticalMargins: Float,
    ) {
        key(chapterId, sessionToken) {
            val arguments = remember(chapterId, sourceId) {
                EpubReaderFragment.createArguments(chapterId, sourceId)
            }
            AndroidFragment<EpubReaderFragment>(
                modifier = modifier,
                arguments = arguments,
                onUpdate = { fragment ->
                    readerFragment = fragment
                    fragment.view?.let { view ->
                        view.setBackgroundColor(theme.readerBackgroundColor(customBackgroundColor))
                        view.setLayerType(View.LAYER_TYPE_HARDWARE, readerColorFilterPaint(grayscale, invertedColors))
                        view.applyInsetsPadding(
                            windowInsets = ViewCompat.getRootWindowInsets(view),
                            fullscreen = fullscreen,
                            drawUnderCutout = drawUnderCutout,
                            verticalMargins = verticalMargins,
                        )
                        ViewCompat.setOnApplyWindowInsetsListener(view) { insetView, windowInsets ->
                            insetView.applyInsetsPadding(
                                windowInsets = windowInsets,
                                fullscreen = fullscreen,
                                drawUnderCutout = drawUnderCutout,
                                verticalMargins = verticalMargins,
                            )
                            windowInsets
                        }
                        ViewCompat.requestApplyInsets(view)
                    }
                },
            )
        }
    }

    private fun removeRestoredReaderWithoutSession(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val chapterId = intent.extras?.getLong("chapter", -1L) ?: -1L
        if (chapterId <= 0L || sessionRepository.get(chapterId) != null) return

        supportFragmentManager.fragments
            .filterIsInstance<EpubReaderFragment>()
            .forEach { fragment ->
                supportFragmentManager.commitNow {
                    remove(fragment)
                }
            }
    }

    @Composable
    private fun ErrorContent(
        message: String,
        onRetry: () -> Unit,
        onOpenAsPages: (() -> Unit)?,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(text = message)
            Text(
                text = stringResource(MR.strings.action_retry),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clickable(onClick = onRetry),
            )
            if (onOpenAsPages != null) {
                Text(
                    text = stringResource(MR.strings.epub_reader_open_as_pages),
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .clickable(onClick = onOpenAsPages),
                )
            }
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
        verticalMargins: Float,
    ) {
        val systemBars = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: Insets.NONE
        val displayCutout = windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val horizontalInsets = when {
            !fullscreen -> systemBars
            !drawUnderCutout -> displayCutout
            else -> Insets.NONE
        }
        // EPUB text must always stay below the camera cutout, even when edge drawing is enabled.
        val topInset = if (fullscreen) displayCutout.top else systemBars.top
        val bottomInset = when {
            !fullscreen -> systemBars.bottom
            !drawUnderCutout -> displayCutout.bottom
            else -> 0
        }
        val verticalPadding = if (fullscreen) {
            (readerVerticalPaddingDp(verticalMargins) * resources.displayMetrics.density).roundToInt()
        } else {
            0
        }

        setPadding(
            horizontalInsets.left,
            topInset + verticalPadding,
            horizontalInsets.right,
            bottomInset + verticalPadding,
        )
    }

    private fun readerColorFilterPaint(grayscale: Boolean, invertedColors: Boolean): Paint? {
        if (!grayscale && !invertedColors) return null

        return Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    if (grayscale) {
                        setSaturation(0f)
                    }
                    if (invertedColors) {
                        postConcat(
                            ColorMatrix(
                                floatArrayOf(
                                    -1f, 0f, 0f, 0f, 255f,
                                    0f, -1f, 0f, 0f, 255f,
                                    0f, 0f, -1f, 0f, 255f,
                                    0f, 0f, 0f, 1f, 0f,
                                ),
                            ),
                        )
                    }
                },
            )
        }
    }

    // Shared by the Fragment padding and fullscreen background overlays so their bounds cannot drift apart.
    private fun readerVerticalPaddingDp(verticalMargins: Float): Float {
        return READER_EDGE_PADDING_DP +
            EpubLayoutPreferences.VERTICAL_MARGIN_BASE_DP * verticalMargins
    }

    private fun EpubLayoutPreferences.Theme.readerBackgroundColor(customBackgroundColor: Int): Int {
        return when (this) {
            EpubLayoutPreferences.Theme.LIGHT -> Color.White
            EpubLayoutPreferences.Theme.DARK -> Color.Black
            EpubLayoutPreferences.Theme.SEPIA -> Color(0xFFFAF4E8)
            EpubLayoutPreferences.Theme.MINT -> Color(0xFFC4EDC8)
            EpubLayoutPreferences.Theme.BLUE -> Color(0xFFE0F0FC)
            EpubLayoutPreferences.Theme.PINK -> Color(0xFFFBE4EE)
            EpubLayoutPreferences.Theme.GRAY -> Color(0xFFF1F3F5)
            EpubLayoutPreferences.Theme.CUSTOM -> Color(customBackgroundColor)
        }.toArgb()
    }

    @Composable
    private fun BoxScope.EpubNavigationOverlay(
        readingMode: EpubLayoutPreferences.ReadingMode,
        navigationMode: Int,
        invertMode: ReaderPreferences.TappingInvertMode,
        showOnStart: Boolean,
    ) {
        val navigator = remember(readingMode, navigationMode, invertMode) {
            createEpubNavigation(
                readingMode = readingMode,
                navigationMode = navigationMode,
                invertMode = invertMode,
            )
        }
        val navigationKey = remember(readingMode, navigationMode, invertMode, showOnStart) {
            listOf(readingMode, navigationMode, invertMode, showOnStart)
        }
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ReaderNavigationOverlayView(context, null).apply {
                    alpha = 0f
                    isVisible = false
                }
            },
            update = { overlay ->
                if (overlay.tag != navigationKey) {
                    overlay.setNavigation(navigator, showOnStart)
                    overlay.tag = navigationKey
                }
            },
        )
    }
}
