package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.crash.CrashDiagnostics
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.download.DownloadNetworkQoS
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import koharia.connection.ConnectionScopedPreferenceStoreFactory
import koharia.document.toDocumentRenderSettings
import koharia.epub.DocumentBookInfoDialog
import koharia.epub.EpubBottomPanel
import koharia.epub.EpubDocumentMorePanel
import koharia.epub.EpubReaderBottomArea
import koharia.epub.EpubReaderTopBar
import koharia.epub.font.EpubFontManager
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubReaderPreferences
import koharia.epub.settings.EpubReaderSettingsSheet
import koharia.importing.IncomingMediaNavigation
import koharia.importing.IncomingMediaSessionLocator
import koharia.source.local.LocalLibraryLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.ScopedPreferenceStore
import tachiyomi.core.common.preference.SessionPreferenceStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class ReaderActivity : BaseActivity() {

    companion object {
        private const val EXTRA_USE_EPUB_SETTINGS = "use_epub_settings"

        fun newIntent(
            context: Context,
            mangaId: Long?,
            chapterId: Long?,
            sourceId: Long? = null,
            useEpubSettings: Boolean = false,
        ): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                sourceId?.let { putExtra("source", it) }
                putExtra(EXTRA_USE_EPUB_SETTINGS, useEpubSettings)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val scopedPreferenceStoreFactory = Injekt.get<ConnectionScopedPreferenceStoreFactory>()
    private val eInkPreferences = Injekt.get<EInkPreferences>()
    val readerPreferences: ReaderPreferences by lazy { viewModel.readerPreferences }
    private val useEpubSettings: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_USE_EPUB_SETTINGS, false)
    }
    val basePreferences: BasePreferences by lazy {
        val sourceId = intent.extras?.getLong("source", -1L) ?: -1L
        if (sourceId > 0L) {
            scopedPreferenceStoreFactory.basePreferences(sourceId)
        } else {
            Injekt.get<BasePreferences>()
        }
    }

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel>()
    private var assistUrl: String? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost by lazy { DisplayRefreshHost(readerPreferences) }

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private val epubSettingsBackingStore: PreferenceStore by lazy {
        val sourceId = intent.extras?.getLong("source", -1L) ?: -1L
        if (sourceId > 0L) {
            scopedPreferenceStoreFactory.storeForServer(sourceId)
        } else {
            Injekt.get<ScopedPreferenceStore>()
        }
    }
    private val epubReaderPreferences by lazy { EpubReaderPreferences(epubSettingsBackingStore) }
    private val epubFontManager: EpubFontManager by lazy { Injekt.get() }
    private val epubSettingsStore by lazy {
        SessionPreferenceStore(
            backingStore = epubSettingsBackingStore,
            persistChanges = epubReaderPreferences.persistReaderSettingsChanges.get(),
        )
    }
    private val epubLayoutPreferences by lazy { EpubLayoutPreferences(epubSettingsStore) }

    private var loadingIndicator: ReaderProgressIndicator? = null

    var isScrollingThroughPages = false
        private set

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        if (eInkPreferences.enabled.get()) {
            disableActivityTransition(OVERRIDE_TRANSITION_OPEN)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setComposeOverlay()

        if (useEpubSettings) {
            syncEpubViewerPreferences()
        }

        viewModel.setDocumentRenderSettingsProvider {
            epubLayoutPreferences.toDocumentRenderSettings()
        }

        if (useEpubSettings) {
            epubReaderPreferences.persistReaderSettingsChanges.changes()
                .onEach { enabled ->
                    epubSettingsStore.setPersistChanges(enabled)
                    viewModel.setSessionReaderSettingsPersistence(enabled)
                }
                .launchIn(lifecycleScope)
            observeDocumentLayoutPreferences()
        }

        if (viewModel.needsInit()) {
            val manga = intent.extras?.getLong("manga", -1) ?: -1L
            val chapter = intent.extras?.getLong("chapter", -1) ?: -1L
            if (manga == -1L || chapter == -1L) {
                finish()
                return
            }
            NotificationReceiver.dismissNotification(this, manga.hashCode(), Notifications.ID_NEW_CHAPTERS)

            lifecycleScope.launchNonCancellable {
                val initResult = viewModel.init(manga, chapter)
                if (!initResult.getOrDefault(false)) {
                    val exception = initResult.exceptionOrNull() ?: IllegalStateException("Unknown err")
                    withUIContext {
                        setInitialChapterError(exception)
                    }
                }
            }
        }

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        basePreferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    is ReaderViewModel.Event.DocumentPagesRefreshed -> {
                        if (useEpubSettings && documentViewerNeedsRecreation()) {
                            updateViewer()
                        }
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                        viewModel.state.value.viewer?.restorePage(event.page)
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onStart() {
        super.onStart()
        DownloadNetworkQoS.acquireReader()
    }

    override fun onStop() {
        DownloadNetworkQoS.releaseReader()
        super.onStop()
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val showPageNumber by readerPreferences.showPageNumber.collectAsState()
        val activeEpubPanel = remember { mutableStateOf(EpubBottomPanel.NONE) }
        val settingsScreenModel = remember {
            ReaderSettingsScreenModel(
                readerState = viewModel.state,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
                preferences = readerPreferences,
                persistReaderSettingsChanges = viewModel.persistReaderSettingsChanges,
                onSetPersistReaderSettingsChanges = viewModel::setPersistReaderSettingsChanges,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.menuVisible && showPageNumber) {
                ReaderPageIndicator(
                    currentPage = state.visiblePageEnd.takeIf { it > 0 } ?: state.currentPage,
                    totalPages = state.totalPages,
                    visiblePageStart = state.visiblePageStart,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }

            ContentOverlay(state = state)

            AppBars(
                state = state,
                activeEpubPanel = activeEpubPanel.value,
                onEpubPanelChange = { activeEpubPanel.value = it },
            )
        }

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Loading -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EInkCircularProgressIndicator()
                            Text(stringResource(MR.strings.loading))
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.Settings -> {
                if (useEpubSettings) {
                    EpubReaderSettingsSheet(
                        preferences = epubLayoutPreferences,
                        readerPreferences = readerPreferences,
                        epubReaderPreferences = epubReaderPreferences,
                        onDismissRequest = {
                            onDismissRequest()
                            setMenuVisibility(true)
                        },
                    )
                } else {
                    ReaderSettingsDialog(
                        onDismissRequest = onDismissRequest,
                        onShowMenus = { setMenuVisibility(true) },
                        onHideMenus = { setMenuVisibility(false) },
                        screenModel = settingsScreenModel,
                    )
                }
            }
            is ReaderViewModel.Dialog.ReadingModeSelect -> {
                ReadingModeSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        if (!readerPreferences.showReadingMode.get()) {
                            menuToggleToast = toast(stringRes)
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.PageActions -> {
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                )
            }
            null -> {}
        }

        state.remoteProgressConflict
            ?.takeIf { state.dialog == null }
            ?.let { conflict ->
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(MR.strings.epub_reader_remote_progress_title)) },
                    text = {
                        Text(
                            stringResource(
                                MR.strings.reader_remote_progress_message,
                                conflict.localPageIndex + 1,
                                conflict.localTotalPages,
                                conflict.localPercent,
                                conflict.remotePageIndex + 1,
                                conflict.remoteTotalPages,
                                conflict.remotePercent,
                            ),
                        )
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::keepLocalProgress) {
                            Text(stringResource(MR.strings.epub_reader_keep_local_progress))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::useRemoteProgress) {
                            Text(stringResource(MR.strings.epub_reader_jump_remote_progress))
                        }
                    },
                    properties = DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                    ),
                )
            }
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        val startedAt = SystemClock.uptimeMillis()
        try {
            try {
                viewModel.state.value.viewer?.destroy()
            } catch (error: Throwable) {
                CrashDiagnostics.recordNonFatal(this, "reader.viewer.destroy", error)
            }

            if (!isChangingConfigurations) {
                try {
                    viewModel.releaseReaderResources()
                } catch (error: Throwable) {
                    CrashDiagnostics.recordNonFatal(this, "reader.resource.release.start", error)
                }
            }

            config = null
            menuToggleToast?.cancel()
            readingModeToast?.cancel()
        } finally {
            try {
                try {
                    super.onDestroy()
                } catch (error: Throwable) {
                    CrashDiagnostics.recordNonFatal(this, "reader.activity.super.onDestroy", error)
                    throw error
                }
            } finally {
                CrashDiagnostics.recordSlowOperation(
                    this,
                    "reader.activity.destroy",
                    SystemClock.uptimeMillis() - startedAt,
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (viewModel.state.value.viewer as? PagerViewer)?.onConfigurationChanged()
    }

    override fun onPause() {
        lifecycleScope.launchNonCancellable {
            try {
                viewModel.updateHistory()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                CrashDiagnostics.recordNonFatal(this@ReaderActivity, "reader.updateHistory", error)
            }
        }
        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (eInkPreferences.enabled.get()) {
            disableActivityTransition(OVERRIDE_TRANSITION_CLOSE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    @Suppress("DEPRECATION")
    private fun disableActivityTransition(transitionType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(transitionType, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    private fun AppBars(
        state: ReaderViewModel.State,
        activeEpubPanel: EpubBottomPanel,
        onEpubPanelChange: (EpubBottomPanel) -> Unit,
    ) {
        if (!ifSourcesLoaded()) {
            return
        }

        if (useEpubSettings) {
            EpubStyleAppBars(
                state = state,
                activePanel = activeEpubPanel,
                onPanelChange = onEpubPanelChange,
            )
            return
        }

        val isHttpSource = viewModel.getSource() is HttpSource

        val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
        val isPagerType = ReadingMode.isPagerType(effectiveReadingModePreference())
        val cropEnabled = if (isPagerType) cropBorderPaged else cropBorderWebtoon

        val verticalNavigatorForLongStrip by readerPreferences.verticalNavigatorForLongStrip.collectAsState()
        val verticalNavigatorOnLeft by readerPreferences.verticalNavigatorOnLeft.collectAsState()

        ReaderAppBars(
            visible = state.menuVisible,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            bookmarked = state.bookmarked,
            onToggleBookmarked = viewModel::toggleChapterBookmark,
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },
            onImportTemporaryMedia = ::importTemporaryMedia.takeIf {
                IncomingMediaNavigation.temporaryMediaUri(intent) != null
            },

            chapterNavigatorType = if (isPagerType || !verticalNavigatorForLongStrip) {
                if (state.viewer is R2LPagerViewer) {
                    ChapterNavigatorType.HORIZONTAL_RTL
                } else {
                    ChapterNavigatorType.HORIZONTAL_LTR
                }
            } else {
                if (verticalNavigatorOnLeft) {
                    ChapterNavigatorType.VERTICAL_LEFT
                } else {
                    ChapterNavigatorType.VERTICAL_RIGHT
                }
            },
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            visiblePageStart = state.visiblePageStart,
            visiblePageEnd = state.visiblePageEnd.takeIf { it > 0 } ?: state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },

            readingMode = ReadingMode.fromPreference(
                effectiveReadingModePreference(),
            ),
            onClickReadingMode = if (useEpubSettings) {
                viewModel::openSettingsDialog
            } else {
                viewModel::openReadingModeSelectDialog
            },
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            onClickSettings = viewModel::openSettingsDialog,
        )
    }

    @Composable
    private fun EpubStyleAppBars(
        state: ReaderViewModel.State,
        activePanel: EpubBottomPanel,
        onPanelChange: (EpubBottomPanel) -> Unit,
    ) {
        val currentReadingMode by epubLayoutPreferences.readingMode.changes()
            .collectAsState(epubLayoutPreferences.readingMode.get())
        val currentPageDirection by epubLayoutPreferences.pageDirection.changes()
            .collectAsState(epubLayoutPreferences.pageDirection.get())
        val totalPages = state.totalPages.coerceAtLeast(1)
        val currentPage = state.currentPage.coerceIn(1, totalPages)
        val progression = if (totalPages <= 1) {
            0.0
        } else {
            (currentPage - 1).toDouble() / (totalPages - 1).toDouble()
        }
        val showBookInfo = remember { mutableStateOf(false) }
        val chapter = state.currentChapter?.chapter
        val sourceId = intent.extras?.getLong("source", -1L) ?: -1L
        val fileName = chapter?.let { currentChapter ->
            documentFileName(
                chapterUrl = currentChapter.url,
                sourceId = sourceId,
                fallback = currentChapter.name,
            )
        }
        val format = fileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?.uppercase()
            ?: "BOOK"
        val fileSizeBytes = chapter?.let { currentChapter ->
            IncomingMediaSessionLocator.chapterFile(this@ReaderActivity, currentChapter.url, sourceId)
                ?.length()
                ?.takeIf { it > 0L }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            EpubReaderTopBar(
                visible = state.menuVisible,
                title = state.manga?.title,
                subtitle = state.currentChapter?.chapter?.name,
                isSearchable = false,
                isBookmarked = state.bookmarked,
                bookmarkEnabled = state.currentChapter != null,
                modifier = Modifier.align(Alignment.TopCenter),
                onNavigateUp = onBackPressedDispatcher::onBackPressed,
                onClick = ::openMangaScreen,
                onSearch = {},
                onToggleBookmark = {
                    onPanelChange(EpubBottomPanel.NONE)
                    viewModel.toggleChapterBookmark()
                },
                onImportTemporaryMedia = ::importTemporaryMedia.takeIf {
                    IncomingMediaNavigation.temporaryMediaUri(intent) != null
                },
            )
            EpubReaderBottomArea(
                visible = state.menuVisible,
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
                currentPosition = currentPage,
                totalPositions = totalPages,
                progression = progression,
                currentVisualPage = currentPage
                    .takeIf { currentReadingMode == EpubLayoutPreferences.ReadingMode.PAGINATED },
                totalVisualPages = totalPages
                    .takeIf { currentReadingMode == EpubLayoutPreferences.ReadingMode.PAGINATED },
                enabledPreviousChapter = state.viewerChapters?.prevChapter != null,
                enabledNextChapter = state.viewerChapters?.nextChapter != null,
                onPositionChange = { index ->
                    isScrollingThroughPages = true
                    moveToPageIndex(index)
                },
                onProgressionChange = { targetProgression ->
                    isScrollingThroughPages = true
                    val targetPage = (targetProgression * (totalPages - 1))
                        .roundToInt()
                        .coerceIn(0, totalPages - 1)
                    moveToPageIndex(targetPage)
                },
                onPreviousChapter = ::loadPreviousChapter,
                onNextChapter = ::loadNextChapter,
                onOpenContents = {
                    onPanelChange(EpubBottomPanel.NONE)
                    openMangaScreen()
                },
                onToggleNightMode = {
                    val target = if (epubLayoutPreferences.theme.get() == EpubLayoutPreferences.Theme.DARK) {
                        EpubLayoutPreferences.Theme.LIGHT
                    } else {
                        EpubLayoutPreferences.Theme.DARK
                    }
                    epubLayoutPreferences.theme.set(target)
                },
                onToggleSettings = {
                    onPanelChange(
                        if (activePanel == EpubBottomPanel.SETTINGS) {
                            EpubBottomPanel.NONE
                        } else {
                            EpubBottomPanel.SETTINGS
                        },
                    )
                },
                onToggleMore = {
                    onPanelChange(
                        if (activePanel == EpubBottomPanel.MORE) {
                            EpubBottomPanel.NONE
                        } else {
                            EpubBottomPanel.MORE
                        },
                    )
                },
                onOpenFontPicker = null,
                morePanel = {
                    EpubDocumentMorePanel(
                        onShowBookInfo = {
                            onPanelChange(EpubBottomPanel.NONE)
                            showBookInfo.value = true
                        },
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )

            if (showBookInfo.value) {
                DocumentBookInfoDialog(
                    seriesTitle = state.manga?.title,
                    bookTitle = chapter?.name,
                    format = format,
                    fileName = fileName,
                    fileSizeBytes = fileSizeBytes,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onDismissRequest = { showBookInfo.value = false },
                )
            }
        }
    }

    private fun documentFileName(
        chapterUrl: String,
        sourceId: Long,
        fallback: String?,
    ): String? {
        return IncomingMediaSessionLocator.location(chapterUrl, sourceId)?.fileName
            ?: LocalLibraryLocator.location(chapterUrl, sourceId)
                ?.relativePath
                ?.substringAfterLast('/')
            ?: runCatching { Uri.parse(chapterUrl).lastPathSegment }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
            ?: fallback?.takeIf(String::isNotBlank)
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(effectiveReadingModePreference(), this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            try {
                prevViewer.destroy()
            } catch (error: Throwable) {
                CrashDiagnostics.recordNonFatal(this, "reader.viewer.replace.destroy", error)
            }
            binding.viewerContainer.removeAllViews()
        }
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewer.getView())

        if (readerPreferences.showReadingMode.get()) {
            showReadingModeToast(effectiveReadingModePreference())
        }

        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    viewModel.manga?.let { manga ->
                        putExtra(Constants.MANGA_SOURCE_EXTRA, manga.source)
                        putExtra(Constants.MANGA_URL_EXTRA, manga.url)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun importTemporaryMedia() {
        val uriValue = IncomingMediaNavigation.temporaryMediaUri(intent) ?: return
        startActivity(IncomingMediaNavigation.importIntent(this, uriValue))
        finish()
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        binding.readerContainer.removeView(loadingIndicator)
        try {
            viewModel.state.value.viewer?.setChapters(viewerChapters)
        } catch (error: Throwable) {
            CrashDiagnostics.recordNonFatal(this, "reader.viewer.setChapters", error)
        }

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    fun onPagesSelected(pages: List<ReaderPage>) {
        viewModel.onPagesSelected(pages)
    }

    fun onPagesActivated(pages: List<ReaderPage>, anchorPage: ReaderPage? = null) {
        viewModel.onPagesActivated(pages, anchorPage)
    }

    fun onPageDisplayed(page: ReaderPage) {
        viewModel.onPageDisplayed(page)
    }

    fun onPagesDisplayed(pages: List<ReaderPage>) {
        viewModel.onPagesDisplayed(pages)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        viewModel.openPageDialog(page)
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(uri: Uri, page: ReaderPage) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number),
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    private fun observeDocumentLayoutPreferences() {
        val renderingChanges = listOf(
            epubLayoutPreferences.readingMode.changes().map { it as Any },
            epubLayoutPreferences.pageDirection.changes().map { it as Any },
            epubLayoutPreferences.theme.changes().map { it as Any },
            epubLayoutPreferences.customBackgroundColor.changes().map { it as Any },
            epubLayoutPreferences.fontSize.changes().map { it as Any },
            epubLayoutPreferences.lineHeight.changes().map { it as Any },
            epubLayoutPreferences.paragraphSpacing.changes().map { it as Any },
            epubLayoutPreferences.paragraphIndent.changes().map { it as Any },
            epubLayoutPreferences.pageMargins.changes().map { it as Any },
            epubLayoutPreferences.verticalMargins.changes().map { it as Any },
            epubLayoutPreferences.spacingMode.changes().map { it as Any },
            epubLayoutPreferences.selectedFontId.changes().map { it as Any },
            epubLayoutPreferences.textAlignment.changes().map { it as Any },
            epubLayoutPreferences.publisherStyles.changes().map { it as Any },
            epubFontManager.catalogState.map { it as Any },
        )
        combine(renderingChanges) { values -> values.toList() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(100)
            .onEach { viewModel.onDocumentLayoutPreferencesChanged() }
            .launchIn(lifecycleScope)

        if (useEpubSettings) {
            epubLayoutPreferences.pageTransitionEffect.changes()
                .drop(1)
                .onEach(readerPreferences.pagerPageTransitionEffect::set)
                .launchIn(lifecycleScope)
            epubLayoutPreferences.readWithVolumeKeys.changes()
                .drop(1)
                .onEach(readerPreferences.readWithVolumeKeys::set)
                .launchIn(lifecycleScope)
            epubLayoutPreferences.readWithVolumeKeysInverted.changes()
                .drop(1)
                .onEach(readerPreferences.readWithVolumeKeysInverted::set)
                .launchIn(lifecycleScope)
        }
    }

    private fun syncEpubViewerPreferences() {
        readerPreferences.pagerPageTransitionEffect.set(epubLayoutPreferences.pageTransitionEffect.get())
        readerPreferences.readWithVolumeKeys.set(epubLayoutPreferences.readWithVolumeKeys.get())
        readerPreferences.readWithVolumeKeysInverted.set(epubLayoutPreferences.readWithVolumeKeysInverted.get())
    }

    private fun effectiveReadingModePreference(): Int {
        if (!useEpubSettings) return viewModel.getMangaReadingMode()
        return when (epubLayoutPreferences.readingMode.get()) {
            EpubLayoutPreferences.ReadingMode.SCROLL -> ReadingMode.WEBTOON.flagValue
            EpubLayoutPreferences.ReadingMode.PAGINATED -> when (epubLayoutPreferences.pageDirection.get()) {
                EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT -> ReadingMode.LEFT_TO_RIGHT.flagValue
                EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT -> ReadingMode.RIGHT_TO_LEFT.flagValue
            }
        }
    }

    private fun documentViewerNeedsRecreation(): Boolean {
        val viewer = viewModel.state.value.viewer ?: return true
        return when (effectiveReadingModePreference()) {
            ReadingMode.WEBTOON.flagValue -> viewer !is WebtoonViewer
            ReadingMode.LEFT_TO_RIGHT.flagValue -> viewer !is L2RPagerViewer
            ReadingMode.RIGHT_TO_LEFT.flagValue -> viewer !is R2LPagerViewer
            else -> false
        }
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
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

        private val grayBackgroundColor = Color.rgb(0x20, 0x21, 0x25)

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme.changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(
                        when (theme) {
                            0 -> Color.WHITE
                            2 -> grayBackgroundColor
                            3 -> automaticBackgroundColor()
                            else -> Color.BLACK
                        },
                    )
                }
                .launchIn(lifecycleScope)

            basePreferences.displayProfile.changes()
                .onEach { setDisplayProfile(it) }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn.changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                grayBackgroundColor
            } else {
                Color.WHITE
            }
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                TachiyomiImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(100)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
