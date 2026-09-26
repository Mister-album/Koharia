package koharia.epub

import android.Manifest
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
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
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderStatusIndicator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationOverlayView
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderToolbarActions
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import koharia.connection.SharedAppPreferences
import koharia.epub.control.TtsDisclosureDialog
import koharia.epub.control.TtsPanelState
import koharia.epub.font.EpubFontId
import koharia.epub.font.EpubFontManager
import koharia.epub.service.EpubReaderSupportResolution
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubPreferencesBridge
import koharia.epub.settings.EpubReaderPreferences
import koharia.importing.IncomingMediaNavigation
import koharia.tts.TtsChapterTextStore
import koharia.tts.TtsPreferences
import koharia.tts.TtsVendor
import koharia.tts.progress.TtsProgressNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
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
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderActivity : BaseActivity(), EpubReaderFragment.Host {

    companion object {
        const val EXTRA_PDF_REFLOW_REQUESTED = "pdf_reflow_requested"
        private const val READER_EDGE_PADDING_DP = 8
        private const val PAGINATION_SETTINGS_DEBOUNCE_MS = 250L
        private const val PAGINATION_VIEWPORT_DEBOUNCE_MS = 250L
        private const val PROGRESSION_SEEK_DEBOUNCE_MS = 100L
        private const val FOOTNOTE_TOUCH_POSITION_MAX_AGE_MS = 10_000L

        // Phase 3 step 1 修复:Android 13+ POST_NOTIFICATIONS 运行时权限请求码。
        // 在 onToggleTts 启动 TTS 前请求;用户拒绝也不影响音频播放,只丢通知栏视觉入口。
        private const val REQ_CODE_POST_NOTIFICATIONS = 8421

        // Phase 3 自动续播:导航下一章后等待其落定、再提取文本起播的超时兜底。
        // onLocatorChanged 命中目标 href 时会提前触发;同一资源内的锚点跳转不会改变
        // href,只能靠这个超时兜底。
        private const val TTS_AUTO_ADVANCE_FALLBACK_MS = 900L

        // 续播前让导航/排版落定的稳定延迟。
        private const val TTS_AUTO_ADVANCE_SETTLE_MS = 250L

        fun newPdfReflowIntent(
            context: Context,
            mangaId: Long?,
            chapterId: Long?,
            sourceId: Long?,
            initialPage: Int? = null,
            resolution: EpubReaderSupportResolution? = null,
        ): Intent = newIntent(context, mangaId, chapterId, sourceId, resolution).apply {
            putExtra(EXTRA_PDF_REFLOW_REQUESTED, true)
            initialPage?.let { putExtra("pdf_reflow_initial_page", it) }
        }

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
                    putExtra("pdf_reflow_revision", it.pdfReflowRevision)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val viewModel by viewModels<EpubReaderViewModel>()
    private val sharedAppPreferences = Injekt.get<SharedAppPreferences>()
    private val sessionRepository = Injekt.get<EpubReaderSessionRepository>()
    private val eInkPreferences = Injekt.get<EInkPreferences>()
    private val sourceId by lazy { intent.extras?.getLong("source", -1L) ?: -1L }
    private val basePreferences by lazy { sharedAppPreferences.basePreferences() }
    private val persistentReaderSettingsStore: PreferenceStore by lazy { sharedAppPreferences.store() }
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
    private val displayRefreshHost by lazy { DisplayRefreshHost(readerPreferences) }
    private val epubPreferencesBridge = EpubPreferencesBridge()
    private val epubFontManager: EpubFontManager = Injekt.get()

    // ===== Phase 5 (PR review): TTS 数据披露 / 正文 token 传递 / 失败提示 =====
    private val ttsPreferences = Injekt.get<TtsPreferences>()
    private val ttsChapterTextStore = Injekt.get<TtsChapterTextStore>()
    private val ttsProgressNotifier = Injekt.get<TtsProgressNotifier>()

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
    private var capturedVisibleFontRequirements: Pair<Long, String>? = null

    // ===== Phase 3 自动续播 =====
    // 目标章节 href（等待导航落定后起播）；null 表示当前没有待续播任务。
    private var pendingTtsAdvanceSession: TtsProgressNotifier.Session? = null
    private var ttsStartRequestId = 0L
    private var ttsVisiblePage: Pair<String, Int>? = null
    private var ttsResourceProgression: Pair<String, Double>? = null
    private val ttsPageFollow by lazy {
        EpubTtsPageFollow(
            scope = lifecycleScope,
            currentSession = {
                ttsProgressNotifier.progress.value.session?.takeIf(viewModel::isCurrentTtsSession)
            },
            onPendingChanged = ttsProgressNotifier::setNavigationPending,
            onCompletionReady = ::handleTtsChapterCompleted,
            follow = { session ->
                val restart = startTtsFromCurrentViewport(session)
                try {
                    restart?.join()
                    withTimeoutOrNull(1_500) {
                        ttsProgressNotifier.progress.first { it.session != session }
                    }
                } finally {
                    restart?.cancel()
                }
            },
        )
    }

    private var pendingTtsAdvanceRequestId = 0L
    private var stoppedTtsSession: TtsProgressNotifier.Session? = null
    private var pendingTtsAdvanceTargetHref: String? = null

    // true 时靠 onLocatorChanged 命中目标 href 提前触发；false（同一资源内锚点）只能靠超时兜底。
    private var pendingTtsAdvanceMatchByHref = false
    private var pendingTtsAdvanceFallbackJob: Job? = null
    private var imagePreviewVisible = false
    private var lastTouchXFraction: Float? = null
    private var lastTouchYFraction: Float? = null
    private var lastTouchPositionTimeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        if (needsSharedConfigSelection()) {
            configStartupDeferred = true
            super.onCreate(null)
            redirectToSharedConfigSelection()
            return
        }

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

        // PR review P1：本章一句都没能发声（key 无效 / 断网 / 限流 / 超长句）时提示用户，
        // 而不是静默地当作"播完"去跳下一章。
        ttsProgressNotifier.playbackFailed
            .onEach { session ->
                val activeSession = ttsProgressNotifier.progress.value.session
                if (session.belongsTo(viewModel.state.value.chapterId, viewModel.state.value.mangaId) &&
                    (activeSession == null || activeSession == session)
                ) {
                    toast(MR.strings.tts_error_synthesis_failed, Toast.LENGTH_LONG)
                }
            }
            .launchIn(lifecycleScope)

        setComposeContent(enableAppRefresh = false) {
            val state by viewModel.state.collectAsState()
            val followingManualPage by ttsPageFollow.pending.collectAsState()
            val flashOnPageChange by readerPreferences.flashOnPageChange.changes()
                .collectAsState(readerPreferences.flashOnPageChange.get())
            val imageState by viewModel.imageState.collectAsState()
            val footnoteState by viewModel.footnoteState.collectAsState()
            val tocEntries =
                remember(state.chapterId, state.sessionToken, state.isReady) { viewModel.tableOfContents() }
            val adjacentTocEntries = remember(tocEntries, state.currentPosition, state.currentHref) {
                viewModel.adjacentTocEntries(tocEntries, state.currentPosition, state.currentHref)
            }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var activePanel by rememberSaveable { mutableStateOf(EpubBottomPanel.NONE) }
            var showBookInfoDialog by rememberSaveable { mutableStateOf(false) }
            var showFontPicker by rememberSaveable(state.chapterId) { mutableStateOf(false) }
            var showTtsDisclosureDialog by rememberSaveable { mutableStateOf(false) }
            val currentTheme by epubLayoutPreferences.theme.changes().collectAsState(epubLayoutPreferences.theme.get())
            val currentCustomBackgroundColor by epubLayoutPreferences.customBackgroundColor.changes()
                .collectAsState(epubLayoutPreferences.customBackgroundColor.get())
            val currentReadingMode by epubLayoutPreferences.readingMode.changes()
                .collectAsState(epubLayoutPreferences.readingMode.get())
            val currentPageDirection by epubLayoutPreferences.pageDirection.changes()
                .collectAsState(epubLayoutPreferences.pageDirection.get())
            val currentVerticalMargins by epubLayoutPreferences.verticalMargins.changes()
                .collectAsState(epubLayoutPreferences.verticalMargins.get())
            val currentFontScale by epubLayoutPreferences.fontSize.changes()
                .collectAsState(epubLayoutPreferences.fontSize.get())
            val publisherStylesEnabled by epubLayoutPreferences.publisherStyles.changes()
                .collectAsState(epubLayoutPreferences.publisherStyles.get())
            val selectedFontId by epubLayoutPreferences.selectedFontId.changes()
                .collectAsState(epubLayoutPreferences.selectedFontId.get())
            val fontCatalog by epubFontManager.catalogState.collectAsState()
            val selectedFontFingerprint = remember(selectedFontId, fontCatalog) {
                epubFontManager.fingerprint(EpubFontId.fromPreference(selectedFontId))
            }
            val footnoteTypeface by produceState<android.graphics.Typeface?>(
                initialValue = null,
                selectedFontFingerprint,
                publisherStylesEnabled,
            ) {
                value = if (publisherStylesEnabled) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        epubFontManager.previewTypeface(EpubFontId.fromPreference(selectedFontId))
                    }
                }
            }
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
            val readerStatusPosition by readerPreferences.readerStatusPosition.changes()
                .collectAsState(readerPreferences.readerStatusPosition.get())
            val showReaderChapterTitle by readerPreferences.showReaderChapterTitle.changes()
                .collectAsState(readerPreferences.showReaderChapterTitle.get())
            val showReaderClock by readerPreferences.showReaderClock.changes()
                .collectAsState(readerPreferences.showReaderClock.get())
            val showReaderPages by readerPreferences.showReaderPages.changes().collectAsState(
                readerPreferences.showReaderPages.get(),
            )
            val showReaderBattery by readerPreferences.showReaderBattery.changes()
                .collectAsState(readerPreferences.showReaderBattery.get())
            val toolbarActionValue by readerPreferences.epubToolbarActions.changes()
                .collectAsState(readerPreferences.epubToolbarActions.get())
            val toolbarActions = remember(toolbarActionValue) { ReaderToolbarActions.epub(toolbarActionValue) }
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
            val preserveImageColors by epubLayoutPreferences.preserveImageColors.changes()
                .collectAsState(epubLayoutPreferences.preserveImageColors.get())
            val currentReaderBackgroundColor = remember(
                currentTheme,
                currentCustomBackgroundColor,
                grayscale,
                invertedColors,
            ) {
                Color(currentTheme.readerBackgroundColor(currentCustomBackgroundColor))
                    .applyReaderColorFilter(grayscale, invertedColors)
            }
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

            LaunchedEffect(imageState.previewVisible) {
                imagePreviewVisible = imageState.previewVisible
                updateSystemBars(state.menuVisible)
            }

            LaunchedEffect(forceNavigationOverlay) {
                if (forceNavigationOverlay) {
                    persistentReaderPreferences.showNavigationOverlayNewUser.set(false)
                    readerPreferences.showNavigationOverlayNewUser.set(false)
                }
            }

            // Phase 2.5a：TTS 当前句高亮。章节匹配守卫：notifier.chapterHref == state.currentHref
            // 句子 / 阅读器 ready / 章节切换 都会触发；失败时降级为清空高亮（不抛异常）。
            val currentTtsSentence = remember(state.ttsProgress, state.currentHref) {
                val progress = state.ttsProgress
                val href = state.currentHref
                if (progress == null) {
                    logcat(LogPriority.INFO) { "[EpubReaderActivity] tts-hl: progress=null (TTS 还没 bind?)" }
                    return@remember null
                }
                if (progress.session?.belongsTo(state.chapterId, state.mangaId) != true ||
                    progress.chapterHref != href
                ) {
                    logcat(LogPriority.WARN) {
                        "[EpubReaderActivity] tts-hl: href mismatch progress='${progress.chapterHref}' state='$href'"
                    }
                    return@remember null
                }
                if (progress.currentIndex < 0) {
                    logcat(LogPriority.INFO) { "[EpubReaderActivity] tts-hl: currentIndex<0" }
                    return@remember null
                }
                val s = progress.sentences.getOrNull(progress.currentIndex)
                if (s == null) {
                    logcat(LogPriority.WARN) {
                        "[EpubReaderActivity] tts-hl: sentences[${progress.currentIndex}]=null (size=${progress.sentences.size})"
                    }
                }
                s
            }
            LaunchedEffect(
                currentTtsSentence,
                state.ttsProgress?.session,
                state.isReady,
                state.chapterId,
                followingManualPage,
            ) {
                logcat(LogPriority.INFO) {
                    "[EpubReaderActivity] tts-hl LaunchedEffect fired: " +
                        "idx=${currentTtsSentence?.index} isReady=${state.isReady} " +
                        "chapterId=${state.chapterId} sentence=$currentTtsSentence"
                }
                if (!state.isReady || state.chapterId <= 0) {
                    return@LaunchedEffect
                }
                val sentence = currentTtsSentence
                if (sentence == null || followingManualPage) {
                    runCatching { epubReaderFragment()?.clearSentenceHighlight() }
                    return@LaunchedEffect
                }
                runCatching { epubReaderFragment()?.applySentenceHighlight(sentence, scrollIntoView = true) }
            }

            // Phase 3 自动续播：TTS 章节自然播完 → 导航并续播下一章。
            LaunchedEffect(Unit) {
                viewModel.ttsChapterCompleted.collect { session ->
                    logcat(LogPriority.INFO) {
                        "[EpubReaderActivity] tts chapter completed -> auto-advance"
                    }
                    handleTtsChapterCompleted(session)
                }
            }

            BackHandler(
                enabled =
                drawerState.isOpen || activePanel != EpubBottomPanel.NONE || state.isSearchActive,
            ) {
                when {
                    drawerState.isOpen -> scope.launch { drawerState.close() }
                    activePanel != EpubBottomPanel.NONE -> activePanel = EpubBottomPanel.NONE
                    state.isSearchActive -> viewModel.closeSearch()
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = drawerState.isOpen && !state.isSearchActive,
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
                                    ?.takeIf {
                                        currentReadingMode == EpubLayoutPreferences.ReadingMode.PAGINATED &&
                                            state.paginationPhase.hasAccuratePageCount
                                    },
                                totalVisualPages = state.totalVisualPages
                                    ?.takeIf {
                                        currentReadingMode == EpubLayoutPreferences.ReadingMode.PAGINATED &&
                                            state.paginationPhase.hasAccuratePageCount
                                    },
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
                                        onManualReadingNavigation()
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
                                onPreviousChapter = { navigateAdjacentChapter(forward = false) },
                                onNextChapter = { navigateAdjacentChapter(forward = true) },
                                onOpenContents = {
                                    activePanel = EpubBottomPanel.NONE
                                    scope.launch {
                                        if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                    }
                                },
                                toolbarActions = toolbarActions,
                                onToggleNightMode = {
                                    val target = if (currentTheme == EpubLayoutPreferences.Theme.DARK) {
                                        EpubLayoutPreferences.Theme.LIGHT
                                    } else {
                                        EpubLayoutPreferences.Theme.DARK
                                    }
                                    epubLayoutPreferences.theme.set(target)
                                },
                                onOpenFont = if (supportsFontOverride()) {
                                    {
                                        epubReaderFragment()?.prepareFontSelection()
                                        showFontPicker = true
                                    }
                                } else {
                                    null
                                },
                                onOpenBrightness = {
                                    activePanel = EpubBottomPanel.SETTINGS
                                },
                                onSearch = viewModel::openSearch,
                                ttsPanelState = TtsPanelState(state.ttsActive, state.ttsPlaybackState),
                                onTtsAction = { action ->
                                    if (action == koharia.tts.TtsAction.STOP) {
                                        ttsStartRequestId++
                                        ttsPageFollow.cancel()
                                        stoppedTtsSession = ttsProgressNotifier.progress.value.session
                                    }
                                    viewModel.onTtsAction(action)
                                },
                                onToggleTts = {
                                    if (state.ttsActive) {
                                        viewModel.onTtsAction(
                                            TtsPanelState(true, state.ttsPlaybackState).playPauseAction,
                                        )
                                    } else {
                                        // Phase 3 step 1 修复:Android 13+ 必须运行时授予 POST_NOTIFICATIONS,
                                        // 否则 TtsService 的前台通知 + MediaSession 锁屏卡片会被系统静默丢弃
                                        // (测试观察:`dumpsys notification` 显示 app.koharia.dev importance=NONE,
                                        // 通知已 post 但用户看不到)。媒体按钮/蓝牙按键仍可用 —— 通知只是少了视觉入口。
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            PermissionChecker.checkSelfPermission(
                                                this@EpubReaderActivity,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PermissionChecker.PERMISSION_GRANTED
                                        ) {
                                            ActivityCompat.requestPermissions(
                                                this@EpubReaderActivity,
                                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                                REQ_CODE_POST_NOTIFICATIONS,
                                            )
                                        }
                                        // PR review P2：首次启用朗读前先做一次性数据披露
                                        // （章节正文会上传至所选 TTS 厂商做语音合成）。
                                        if (ttsPreferences.disclosureAcknowledged.get()) {
                                            startTtsFromCurrentViewport()
                                        } else {
                                            showTtsDisclosureDialog = true
                                        }
                                    }
                                },
                                onToggleOrientation = {
                                    val orientations = ReaderOrientation.entries
                                    val current = ReaderOrientation.fromPreference(
                                        readerPreferences.defaultOrientationType.get(),
                                    )
                                    val next = orientations[(orientations.indexOf(current) + 1) % orientations.size]
                                    readerPreferences.defaultOrientationType.set(next.flagValue)
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
                                onOpenFontPicker = {
                                    epubReaderFragment()?.prepareFontSelection()
                                },
                                morePanel = {
                                    EpubReaderMorePanel(
                                        state = state,
                                        isPdfReflow = intent.getStringExtra("pdf_reflow_revision") != null ||
                                            intent.getBooleanExtra(EXTRA_PDF_REFLOW_REQUESTED, false),
                                        onOpenAsPages = {
                                            activePanel = EpubBottomPanel.NONE
                                            openAsOriginalPages()
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
                                    preserveImageColors = preserveImageColors,
                                    theme = currentTheme,
                                    customBackgroundColor = currentCustomBackgroundColor,
                                    verticalMargins = currentVerticalMargins,
                                )

                                // Readium applies padding to a white Fragment container. Paint only the reserved
                                // fullscreen edges here; visible reader menus must keep their Material theme colors.
                                if (fullscreen && !state.menuVisible) {
                                    if (!drawUnderCutout) {
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
                                    val visualPage = state.currentVisualPage
                                    val visualTotal = state.totalVisualPages
                                    ReaderStatusIndicator(
                                        chapterTitle = state.currentSectionTitle ?: state.chapterTitle,
                                        currentPage = visualPage ?: ((state.progressionPercent ?: 0) + 1),
                                        totalPages = visualTotal ?: 101,
                                        percentageOnly = visualPage == null || visualTotal == null,
                                        showStatus = true,
                                        showChapterTitle = showReaderChapterTitle,
                                        showClock = showReaderClock,
                                        showBattery = showReaderBattery,
                                        showPages = showReaderPages,
                                        position = readerStatusPosition,
                                        edgeBandHeight = maxOf(
                                            fullscreenVerticalPadding.value.takeIf { fullscreen } ?: 0f,
                                            16f * resources.configuration.fontScale + 20f,
                                        ).dp,
                                        edgeInsets = when {
                                            !fullscreen -> WindowInsets.systemBars
                                            !drawUnderCutout -> WindowInsets.displayCutout
                                            else -> WindowInsets(0, 0, 0, 0)
                                        },
                                        contentColor = if (androidx.core.graphics.ColorUtils.calculateLuminance(
                                                currentTheme.readerBackgroundColor(currentCustomBackgroundColor),
                                            ) > 0.5
                                        ) {
                                            Color.Black
                                        } else {
                                            Color.White
                                        },
                                    )
                                }

                                // PR review P2：首次启用朗读前的一次性数据披露对话框。
                                // 确认后记录标记并立即起播；取消则不播。
                                if (showTtsDisclosureDialog) {
                                    TtsDisclosureDialog(
                                        vendorDisplayName = TtsVendor
                                            .fromId(ttsPreferences.vendorId.get())
                                            .displayName,
                                        onAcknowledge = {
                                            ttsPreferences.disclosureAcknowledged.set(true)
                                            showTtsDisclosureDialog = false
                                            startTtsFromCurrentViewport()
                                        },
                                        onDismissRequest = { showTtsDisclosureDialog = false },
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
                                    onImportTemporaryMedia = ::importTemporaryMedia.takeIf {
                                        IncomingMediaNavigation.temporaryMediaUri(intent) != null
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
                                            openAsOriginalPages()
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
                    onDismissRequest = {},
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
                        TextButton(onClick = viewModel::keepLocalProgress) {
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
                    properties = DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                    ),
                )
            }

            if (showFontPicker) {
                koharia.epub.settings.EpubFontPickerSheet(
                    preferences = epubLayoutPreferences,
                    manager = epubFontManager,
                    onDismissRequest = { showFontPicker = false },
                )
            }

            if (showBookInfoDialog) {
                EpubBookInfoDialog(
                    state = state,
                    onDismissRequest = { showBookInfoDialog = false },
                )
            }

            EpubImageOverlay(
                state = imageState,
                onClosePreview = viewModel::closeImagePreview,
                onDismissActions = viewModel::dismissImageActions,
                onPreview = viewModel::loadSelectedImageForPreview,
                onShowActions = viewModel::showImageActions,
                onRetry = viewModel::retrySelectedImage,
                onSave = { viewModel.saveSelectedImage(readerPreferences.folderPerManga.get()) },
                onShare = { viewModel.shareSelectedImage(copyToClipboard = false) },
                onCopy = { viewModel.shareSelectedImage(copyToClipboard = true) },
                onSetAsCover = viewModel::setSelectedImageAsCover,
            )

            EpubFootnotePopup(
                state = footnoteState,
                backgroundColor = currentReaderBackgroundColor,
                readerFontSizeSp = EpubLayoutPreferences.fontSizeFromScale(currentFontScale).toFloat(),
                applyReaderStyles = !publisherStylesEnabled,
                typeface = footnoteTypeface,
                onDismissRequest = viewModel::dismissFootnote,
            )

            if (flashOnPageChange) {
                DisplayRefreshHost(hostState = displayRefreshHost)
            }
        }

        viewModel.imageEvents
            .onEach(::handleImageEvent)
            .launchIn(lifecycleScope)

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
        if (configStartupDeferred) {
            super.onPause()
            return
        }
        isReaderResumed = false
        ttsPageFollow.endDrag()
        paginationViewportJob?.cancel()
        paginationJob?.cancel()
        progressionSeekGeneration += 1
        progressionSeekJob?.cancel()
        epubReaderFragment()?.stopPagination()
        lifecycleScope.launchNonCancellable {
            withUIContext { epubReaderFragment()?.captureContinuousScrollProgress() }
            viewModel.saveCurrentProgress()
            viewModel.updateHistory()
        }
        resetEpubBrightness()
        super.onPause()
    }

    override fun onResume() {
        if (configStartupDeferred) {
            super.onResume()
            if (configRedirected) return
            return
        }
        super.onResume()
        if (configRedirected) return
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
        if (configStartupDeferred) {
            super.onLowMemory()
            return
        }
        epubReaderFragment()?.stopPagination()
        lifecycleScope.launchNonCancellable { viewModel.saveCurrentProgress() }
        super.onLowMemory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (configRedirected) return
        if (hasFocus) {
            updateSystemBars(viewModel.state.value.menuVisible)
        }
    }

    override fun onDestroy() {
        ttsPageFollow.cancel()
        if (configStartupDeferred) {
            super.onDestroy()
            return
        }
        val releaseSession = isFinishing
        super.onDestroy()
        // FragmentActivity destroys the Readium navigator during super.onDestroy(). Closing the
        // publication afterwards prevents outstanding navigator reads from seeing a closed ZIP.
        if (releaseSession) viewModel.releaseSession()
    }

    private var finishRequested = false

    override fun finish() {
        val fragment = if (configStartupDeferred) null else epubReaderFragment()
        if (fragment == null) {
            super.finish()
            return
        }
        if (finishRequested) return
        finishRequested = true
        lifecycleScope.launchNonCancellable {
            try {
                fragment.captureContinuousScrollProgress()
            } finally {
                withUIContext { super@EpubReaderActivity.finish() }
            }
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
    }

    override fun swipePageTurnsEnabled(): Boolean = readerPreferences.swipePageTurns.get()

    override fun onTap(positionX: Float, positionY: Float): Boolean {
        if (viewModel.imageState.value.isVisible || viewModel.footnoteState.value != null) return true
        val turnOrigin = PageTurnOrigin(positionX, positionY, PageTurnCause.TAP).normalized()
        val isRightToLeft = epubLayoutPreferences.readingMode.get() == EpubLayoutPreferences.ReadingMode.PAGINATED &&
            epubLayoutPreferences.pageDirection.get() == EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT
        return when (resolveNavigationAction(positionX, positionY)) {
            ViewerNavigation.NavigationRegion.MENU -> {
                viewModel.showMenus(!viewModel.state.value.menuVisible)
                true
            }
            ViewerNavigation.NavigationRegion.NEXT,
            -> epubReaderFragment()?.goForward(turnOrigin) ?: false

            ViewerNavigation.NavigationRegion.PREV,
            -> epubReaderFragment()?.goBackward(turnOrigin) ?: false

            ViewerNavigation.NavigationRegion.RIGHT -> {
                if (isRightToLeft) {
                    epubReaderFragment()?.goBackward(turnOrigin) ?: false
                } else {
                    epubReaderFragment()?.goForward(turnOrigin) ?: false
                }
            }
            ViewerNavigation.NavigationRegion.LEFT -> {
                if (isRightToLeft) {
                    epubReaderFragment()?.goForward(turnOrigin) ?: false
                } else {
                    epubReaderFragment()?.goBackward(turnOrigin) ?: false
                }
            }
        }
    }

    private fun ttsPageLocation(): EpubTtsPageFollow.Location {
        return viewModel.state.value.ttsPageFollowLocation(ttsVisiblePage, ttsResourceProgression)
    }

    override fun onManualReadingNavigation(dragging: Boolean) {
        val session = ttsProgressNotifier.progress.value.session ?: return
        if (!viewModel.isCurrentTtsSession(session) ||
            viewModel.state.value.ttsPlaybackState == koharia.tts.TtsPlaybackState.STOPPED
        ) {
            return
        }
        ttsStartRequestId++
        pendingTtsAdvanceTargetHref = null
        pendingTtsAdvanceSession = null
        pendingTtsAdvanceFallbackJob?.cancel()
        pendingTtsAdvanceFallbackJob = null
        ttsPageFollow.begin(session, ttsPageLocation(), dragging)
    }

    override fun onManualReadingDragEnded() {
        ttsPageFollow.endDrag()
    }

    override fun onLocatorChanged(locator: Locator) {
        ttsResourceProgression = (locator.locations.progression as? Number)?.toDouble()
            ?.let { locator.href.toString() to it }
        viewModel.updateLocator(locator)
        ttsPageFollow.moved(ttsPageLocation())
        maybeFirePendingTtsAdvance(locator)
    }

    override fun onPdfSourceAnchorChanged(id: String, href: String) {
        viewModel.onPdfSourceAnchorChanged(id, href)
    }

    override fun preferredPdfSourceAnchor(): String? = viewModel.preferredPdfSourceAnchor

    private fun openAsOriginalPages() {
        lifecycleScope.launch {
            epubReaderFragment()?.capturePdfSourceAnchor()
            viewModel.saveCurrentProgress()
            val state = viewModel.state.value
            startActivity(
                IncomingMediaNavigation.inheritTemporaryMediaUri(
                    from = intent,
                    target = ReaderActivity.newIntent(
                        this@EpubReaderActivity,
                        state.mangaId,
                        state.chapterId,
                        sourceId,
                        pageIndex = viewModel.currentOriginalPdfPage(),
                    ),
                ),
            )
            finish()
        }
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
        ttsVisiblePage = locator.href.toString() to pageIndex
        ttsResourceProgression = (locator.locations.progression as? Number)?.toDouble()
            ?.let { locator.href.toString() to it }
        viewModel.onFirstContentDisplayed()
        viewModel.updateVisualPage(pageIndex, totalPages, locator)
        ttsPageFollow.moved(ttsPageLocation())
        displayRefreshHost.flash()
        lifecycleScope.launch {
            epubReaderFragment()?.currentTocHref(viewModel.tableOfContents().map { it.link })
                ?.let(viewModel::updateCurrentTocHref)
        }
    }

    override fun onBookPaginationChanged(
        generation: Long,
        pageCounts: Map<String, Int>,
        isComplete: Boolean,
    ) {
        viewModel.updateBookPagination(generation, pageCounts, isComplete)
        if (isComplete && generation == viewModel.state.value.paginationGeneration) {
            viewModel.currentLocator()?.let { locator ->
                epubReaderFragment()?.goTo(locator, userInitiated = false)
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

    override fun onFootnoteActivated(link: Link, contentHtml: String) {
        val hasRecentTouch = lastTouchPositionTimeMs != 0L &&
            SystemClock.elapsedRealtime() - lastTouchPositionTimeMs <= FOOTNOTE_TOUCH_POSITION_MAX_AGE_MS
        viewModel.showFootnote(
            link = link,
            contentHtml = contentHtml,
            anchorXFraction = lastTouchXFraction.takeIf { hasRecentTouch },
            anchorYFraction = lastTouchYFraction.takeIf { hasRecentTouch },
        )
        lastTouchXFraction = null
        lastTouchYFraction = null
        lastTouchPositionTimeMs = 0L
    }

    override fun onImageInteraction(reference: EpubImageReference, interaction: EpubImageInteraction) {
        if (interaction == EpubImageInteraction.PREVIEW && viewModel.state.value.menuVisible) {
            viewModel.showMenus(false)
            return
        }
        viewModel.onImageInteraction(reference, interaction)
    }

    override fun onFontLoadFailed() {
        toast(MR.strings.epub_font_load_failed)
    }

    override fun onVisibleFontRequirementsCaptured(chapterId: Long, requirementsJson: String?) {
        capturedVisibleFontRequirements = requirementsJson?.let { chapterId to it }
    }

    override fun onNavigatorReady(fragment: EpubReaderFragment) {
        readerFragment = fragment
        capturedVisibleFontRequirements
            ?.takeIf { (chapterId) -> chapterId == viewModel.state.value.chapterId }
            ?.second
            ?.let(fragment::restoreFontSelectionContext)
        if (paginationViewportJob?.isActive != true) {
            applyCurrentReadiumPreferences(fragment)
        }
    }

    override fun onSessionMissing(chapterId: Long) {
        viewModel.onSessionMissing(chapterId)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        viewModel.onReadingInteraction()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val decorView = window.decorView
            if (decorView.width > 0 && decorView.height > 0) {
                lastTouchXFraction = (event.x / decorView.width).coerceIn(0f, 1f)
                lastTouchYFraction = (event.y / decorView.height).coerceIn(0f, 1f)
                lastTouchPositionTimeMs = SystemClock.elapsedRealtime()
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            ttsPageFollow.endDrag()
        }
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        viewModel.onReadingInteraction()
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isSpenPageKey = event.metaState.and(KeyEvent.META_CTRL_ON) > 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
        val isSpenChapterKey = event.keyCode == KeyEvent.KEYCODE_N || event.keyCode == KeyEvent.KEYCODE_P
        val state = viewModel.state.value
        val hasBlockingOverlay = state.isSearchActive || viewModel.imageState.value.isVisible ||
            viewModel.footnoteState.value != null
        val canHandleSpenPageKey = isSpenPageKey && !hasBlockingOverlay
        val canHandleSpenChapterKey = isSpenChapterKey && !hasBlockingOverlay
        val canHandleVolumeKey = isVolumeKey && epubLayoutPreferences.readWithVolumeKeys.get() &&
            !state.menuVisible && !hasBlockingOverlay
        val isReaderNavigationKey = canHandleSpenPageKey || canHandleSpenChapterKey || canHandleVolumeKey
        if (!isReaderNavigationKey) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when {
                isSpenChapterKey -> navigateAdjacentChapter(forward = event.keyCode == KeyEvent.KEYCODE_N)
                isSpenPageKey -> navigatePage(forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                else -> navigatePage(
                    forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) !=
                        epubLayoutPreferences.readWithVolumeKeysInverted.get(),
                )
            }
            return true
        }
        return true
    }

    private fun updateSystemBars(visible: Boolean) {
        if (imagePreviewVisible) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else if (visible || !readerPreferences.fullscreen.get()) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun handleImageEvent(event: EpubImageEvent) {
        when (event) {
            is EpubImageEvent.Share -> {
                startActivity(event.uri.toShareIntent(applicationContext, event.mimeType))
            }
            is EpubImageEvent.Copy -> {
                val clipboard = getSystemService(ClipboardManager::class.java) ?: return
                clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "", event.uri))
            }
            is EpubImageEvent.Saved -> toast(MR.strings.picture_saved)
            EpubImageEvent.CoverUpdated -> toast(MR.strings.cover_updated)
            is EpubImageEvent.Error -> toast(event.message)
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
                    epubPreferencesBridge.toReadiumPreferences(
                        preferences = epubLayoutPreferences,
                        publicationMetadata = currentPublicationMetadata(),
                    ),
                )
            }
            .launchIn(lifecycleScope)

        val fontSelectionChanges = combine(
            epubLayoutPreferences.selectedFontId.changes(),
            epubFontManager.catalogState,
        ) { selectedFontId, _ ->
            selectedFontId to epubFontManager.fingerprint(EpubFontId.fromPreference(selectedFontId))
        }.distinctUntilChanged()
        val paginationAffectingChanges = listOf(
            epubLayoutPreferences.readingMode.changes().map { it as Any },
            epubLayoutPreferences.pageDirection.changes().map { it as Any },
            epubLayoutPreferences.fontSize.changes().map { it as Any },
            epubLayoutPreferences.lineHeight.changes().map { it as Any },
            epubLayoutPreferences.paragraphSpacing.changes().map { it as Any },
            epubLayoutPreferences.paragraphIndent.changes().map { it as Any },
            epubLayoutPreferences.pageMargins.changes().map { it as Any },
            epubLayoutPreferences.verticalMargins.changes().map { it as Any },
            fontSelectionChanges.map { it as Any },
            epubLayoutPreferences.textAlignment.changes().map { it as Any },
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
                epubReaderFragment()?.cancelPageTransition()
                viewModel.onLayoutPreferencesChanged()
                paginationJob?.cancel()
                viewModel.invalidatePaginationDisplay()
                epubReaderFragment()?.stopPagination()
            }
            .debounce(PAGINATION_SETTINGS_DEBOUNCE_MS)
            .onEach {
                val publisherStyles = epubLayoutPreferences.publisherStyles.get()
                val shouldReload = publisherStyles != currentPublisherStyles
                currentPublisherStyles = publisherStyles
                if (shouldReload) {
                    reloadEpubSessionForPublisherStyles(publisherStyles)
                } else {
                    applyCurrentReadiumPreferences()
                }
            }
            .launchIn(lifecycleScope)

        epubLayoutPreferences.pageTransitionEffect.changes()
            .distinctUntilChanged()
            .drop(1)
            .onEach { epubReaderFragment()?.cancelPageTransition() }
            .launchIn(lifecycleScope)

        eInkPreferences.enabled.changes()
            .distinctUntilChanged()
            .drop(1)
            .onEach { epubReaderFragment()?.cancelPageTransition() }
            .launchIn(lifecycleScope)
    }

    private fun applyCurrentReadiumPreferences(fragment: EpubReaderFragment? = epubReaderFragment()) {
        val activeFragment = fragment?.takeIf { it.isAdded && it.view != null } ?: return
        val viewport = paginationViewport ?: return
        val preferences = epubPreferencesBridge.toReadiumPreferences(
            preferences = epubLayoutPreferences,
            publicationMetadata = currentPublicationMetadata(),
        )
        activeFragment.submitPreferences(preferences)
        paginationJob?.cancel()
        paginationJob = lifecycleScope.launch {
            val snapshot = EpubPaginationLayoutSnapshot.from(epubLayoutPreferences, viewport, epubFontManager)
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
                    viewModel.currentLocator()?.let { activeFragment.goTo(it, userInitiated = false) }
                }
            }
        }
    }

    private fun currentPublicationMetadata(): org.readium.r2.shared.publication.Metadata? {
        return sessionRepository.get(viewModel.state.value.chapterId)?.publication?.metadata
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

    internal fun supportsFontOverride(): Boolean =
        currentPublicationMetadata()?.layout != org.readium.r2.shared.publication.Layout.FIXED

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

    private fun navigatePage(forward: Boolean) {
        if (forward) {
            epubReaderFragment()?.goForward()
        } else {
            epubReaderFragment()?.goBackward()
        }
    }

    // ===== Phase 3 自动续播 =====

    /**
     * 收到 [EpubReaderViewModel.ttsChapterCompleted] 后调用：计算下一章节并导航过去。
     *
     * reader-driven：导航落定后由 [startTtsFromCurrentViewport] 重新起播 ——
     * 起播文本仍由 WebView [EpubReaderFragment.extractTtsTextModel] 提取，
     * 与高亮 JS tree walker 同源，避免 Service 侧 Jsoup 文本偏移漂移。
     */
    private fun handleTtsChapterCompleted(session: TtsProgressNotifier.Session) {
        if (ttsPageFollow.deferCompletion(session)) return
        if (stoppedTtsSession == session ||
            !viewModel.isCurrentTtsSession(session)
        ) {
            return
        }
        val state = viewModel.state.value
        // 守卫：只续播"刚播完的那一章"的下一章。若用户在播放期间滚动/跳到了别的章节，
        // 播放章节 != 当前显示章节，此时续播会错位 —— 直接跳过。
        val playedHref = state.ttsProgress?.chapterHref.orEmpty()
        if (playedHref.isNotBlank() && !sameResourceHref(playedHref, state.currentHref.orEmpty())) {
            logcat(LogPriority.WARN) {
                "[EpubReaderActivity] tts auto-advance skipped: played '$playedHref' " +
                    "!= current '${state.currentHref}'"
            }
            return
        }
        // 用 readingOrder 的下一格，而不是 TOC：这个 EPUB 是 ~198 个很小的资源，
        // 目录只覆盖少数带 fragment 的锚点位置，用 adjacentTocEntries 会选错（真机 ch007→ch003#b）。
        val next = viewModel.nextReadingOrderLink(state.currentHref)
        if (next == null) {
            logcat(LogPriority.INFO) {
                "[EpubReaderActivity] tts auto-advance: no next resource (chapterId=${state.chapterId})"
            }
            return
        }
        val target = next.href.toString()
        val current = state.currentHref.orEmpty()
        logcat(LogPriority.INFO) {
            "[EpubReaderActivity] tts auto-advance -> '$target' (from '$current')"
        }
        pendingTtsAdvanceTargetHref = target
        pendingTtsAdvanceSession = session
        pendingTtsAdvanceRequestId = ttsStartRequestId
        // 同一资源内的锚点跳转不会改变 href，只能靠超时兜底。
        pendingTtsAdvanceMatchByHref = !sameResourceHref(target, current)
        epubReaderFragment()?.goTo(next, userInitiated = false)
        pendingTtsAdvanceFallbackJob?.cancel()
        pendingTtsAdvanceFallbackJob = lifecycleScope.launch {
            delay(TTS_AUTO_ADVANCE_FALLBACK_MS)
            firePendingTtsAdvance()
        }
    }

    /** onLocatorChanged 命中待续播目标 href 时提前触发（避免固定等待超时）。 */
    private fun maybeFirePendingTtsAdvance(locator: Locator) {
        val target = pendingTtsAdvanceTargetHref ?: return
        if (!pendingTtsAdvanceMatchByHref) return
        if (!sameResourceHref(locator.href.toString(), target)) return
        firePendingTtsAdvance()
    }

    /**
     * 触发待续播：先核对当前 href 确实已到达目标，再 [startTtsFromCurrentViewport]。
     *
     * 核对可防止 goTo 失败时把**旧章节**重新起播，从而形成"播完→续播判定失败→重播旧章"死循环。
     */
    private fun firePendingTtsAdvance() {
        val target = pendingTtsAdvanceTargetHref ?: return
        val session = pendingTtsAdvanceSession ?: return
        val requestId = pendingTtsAdvanceRequestId
        pendingTtsAdvanceTargetHref = null
        pendingTtsAdvanceSession = null
        pendingTtsAdvanceFallbackJob?.cancel()
        pendingTtsAdvanceFallbackJob = null
        val current = viewModel.state.value.currentHref.orEmpty()
        if (requestId != ttsStartRequestId || !viewModel.isCurrentTtsSession(session) ||
            !sameResourceHref(current, target)
        ) {
            logcat(LogPriority.WARN) {
                "[EpubReaderActivity] tts auto-advance aborted: current '$current' != target '$target'"
            }
            return
        }
        lifecycleScope.launch {
            delay(TTS_AUTO_ADVANCE_SETTLE_MS)
            if (requestId == ttsStartRequestId && viewModel.isCurrentTtsSession(session) &&
                sameResourceHref(viewModel.state.value.currentHref.orEmpty(), target)
            ) {
                startTtsFromCurrentViewport(session)
            }
        }
    }

    /** 从当前视口起播 TTS（手动播放按钮 / 自动续播共用）。 */
    private fun startTtsFromCurrentViewport(expectedSession: TtsProgressNotifier.Session? = null): Job? {
        val state = viewModel.state.value
        if (!state.isReady || state.chapterId <= 0 || state.mangaId <= 0) return null
        val requestId = ++ttsStartRequestId
        val ttsChapterId = state.chapterId
        val ttsMangaId = state.mangaId
        val ttsHref = state.currentHref.orEmpty()
        val viewport = ttsPageLocation()
        val ttsProgression = viewModel.ttsStartProgression()
        val ttsAnchor = viewModel.ttsStartAnchor()
        return lifecycleScope.launch {
            val fragment = epubReaderFragment()
            // Phase 2.5a fast-path：一次 JS 调用拿到章节文本 + 视口顶部字符偏移。
            // 文本与高亮 JS tree walker 同源 → 高亮不偏移；
            // 偏移与句子同空间 → 起播精确（不依赖可能滞后的 progression）。
            // 拿不到再降级给 TtsService 内部 ChapterTextExtractor + 进度。
            val model = fragment?.extractTtsTextModel()
            val current = viewModel.state.value
            if (requestId != ttsStartRequestId || current.chapterId != ttsChapterId || current.mangaId != ttsMangaId ||
                viewport != ttsPageLocation() ||
                !sameResourceHref(current.currentHref.orEmpty(), ttsHref) ||
                (expectedSession != null && !viewModel.isCurrentTtsSession(expectedSession))
            ) {
                return@launch
            }
            // 只记录长度 / 节点数 / 偏移，不打印正文片段（release 最低日志级别为 INFO）。
            logcat(LogPriority.INFO) {
                "[EpubReaderActivity] tts text model " +
                    "len=${model?.text?.length ?: -1} " +
                    "nodes=${model?.nodeCount ?: -1} " +
                    "startOffset=${model?.startOffset ?: -1}"
            }
            // PR review P1：正文经进程内 store 传递，Intent 里只放短 token，
            // 避免大单文件 EPUB 的整章正文触发 Binder TransactionTooLargeException。
            val textToken = model?.text
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    UUID.randomUUID().toString().also { token ->
                        ttsChapterTextStore.put(token, text)
                    }
                }
            koharia.tts.TtsService.start(
                context = this@EpubReaderActivity,
                chapterId = ttsChapterId,
                mangaId = ttsMangaId,
                href = ttsHref,
                progression = ttsProgression,
                textAnchor = model?.snippet?.takeIf { it.isNotBlank() } ?: ttsAnchor?.first,
                anchorIsBefore = model == null && (ttsAnchor?.second ?: false),
                startOffset = model?.startOffset ?: -1,
                textToken = textToken,
                expectedSessionToken = expectedSession?.token,
            )
        }
    }

    /** href 归一化：去 fragment/query、去前导斜杠后比较（与 ChapterTextExtractor 口径一致）。 */
    private fun sameResourceHref(a: String, b: String): Boolean {
        fun key(value: String): String = value.substringBefore('#').substringBefore('?').trimStart('/')
        return key(a) == key(b)
    }

    private fun navigateAdjacentChapter(forward: Boolean) {
        lifecycleScope.launch {
            epubReaderFragment()?.currentTocHref(viewModel.tableOfContents().map { it.link })
                ?.let(viewModel::updateCurrentTocHref)
            navigateResolvedAdjacentChapter(forward)
        }
    }

    private fun navigateResolvedAdjacentChapter(forward: Boolean) {
        val state = viewModel.state.value
        val adjacentSections = viewModel.adjacentTocEntries(
            entries = viewModel.tableOfContents(),
            currentPosition = state.currentPosition,
            currentHref = state.currentHref,
        )
        val section = if (forward) adjacentSections.second else adjacentSections.first
        if (section != null) {
            epubReaderFragment()?.goTo(section.link)
            return
        }

        val adjacentBookChapterId = if (forward) state.nextBookChapterId else state.previousBookChapterId
        adjacentBookChapterId?.let(::openAdjacentBook)
    }

    private fun openAdjacentBook(chapterId: Long) {
        val mangaId = viewModel.state.value.mangaId.takeIf { it > 0L } ?: return
        lifecycleScope.launch {
            epubReaderFragment()?.captureContinuousScrollProgress()
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
            val detailsRoute = viewModel.resolveDetailsRoute() ?: return@launch
            logcat(LogPriority.DEBUG) {
                "EPUB openMangaScreen detailsMangaId=${detailsRoute.mangaId} " +
                    "readerMangaId=${viewModel.state.value.mangaId} " +
                    "chapterId=${viewModel.state.value.chapterId} sourceId=${detailsRoute.sourceId}"
            }
            startActivity(
                Intent(this@EpubReaderActivity, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, detailsRoute.mangaId)
                    putExtra(Constants.MANGA_SOURCE_EXTRA, detailsRoute.sourceId)
                    putExtra(Constants.MANGA_URL_EXTRA, detailsRoute.mangaUrl)
                    putExtra(Constants.FROM_SOURCE_EXTRA, true)
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
        preserveImageColors: Boolean,
        theme: EpubLayoutPreferences.Theme,
        customBackgroundColor: Int,
        verticalMargins: Float,
    ) {
        val statusVisible by readerPreferences.showPageNumber.changes().collectAsState(
            readerPreferences.showPageNumber.get(),
        )
        val statusPosition by readerPreferences.readerStatusPosition.changes().collectAsState(
            readerPreferences.readerStatusPosition.get(),
        )
        key(chapterId, sessionToken) {
            val arguments = remember(chapterId, sourceId) {
                EpubReaderFragment.createArguments(chapterId, sourceId)
            }
            AndroidFragment<EpubReaderFragment>(
                modifier = modifier,
                arguments = arguments,
                onUpdate = { fragment ->
                    readerFragment = fragment
                    fragment.updateImageColorPolicy(
                        preserveImageColors = preserveImageColors,
                        parentColorsInverted = invertedColors,
                    )
                    fragment.view?.let { view ->
                        view.setBackgroundColor(theme.readerBackgroundColor(customBackgroundColor))
                        view.setLayerType(View.LAYER_TYPE_HARDWARE, readerColorFilterPaint(grayscale, invertedColors))
                        view.updateInsetsHandling(
                            fullscreen = fullscreen,
                            drawUnderCutout = drawUnderCutout,
                            verticalMargins = verticalMargins,
                        )
                    }
                },
            )
            LaunchedEffect(
                chapterId,
                sessionToken,
                fullscreen,
                drawUnderCutout,
                grayscale,
                invertedColors,
                preserveImageColors,
                theme,
                customBackgroundColor,
                verticalMargins,
                statusVisible,
                statusPosition,
            ) {
                readerFragment?.updateImageColorPolicy(
                    preserveImageColors = preserveImageColors,
                    parentColorsInverted = invertedColors,
                )
                // AndroidFragment.onUpdate is only invoked when the Fragment is created. Re-apply
                // layout-affecting reader settings when Compose preferences change in the same session.
                readerFragment?.view?.let { view ->
                    view.setBackgroundColor(theme.readerBackgroundColor(customBackgroundColor))
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, readerColorFilterPaint(grayscale, invertedColors))
                    view.updateInsetsHandling(
                        fullscreen = fullscreen,
                        drawUnderCutout = drawUnderCutout,
                        verticalMargins = verticalMargins,
                    )
                }
            }
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

    private fun View.updateInsetsHandling(
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
        verticalMargins: Float,
    ) {
        applyInsetsPadding(
            windowInsets = ViewCompat.getRootWindowInsets(this),
            fullscreen = fullscreen,
            drawUnderCutout = drawUnderCutout,
            verticalMargins = verticalMargins,
        )
        ViewCompat.setOnApplyWindowInsetsListener(this) { insetView, windowInsets ->
            insetView.applyInsetsPadding(
                windowInsets = windowInsets,
                fullscreen = fullscreen,
                drawUnderCutout = drawUnderCutout,
                verticalMargins = verticalMargins,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(this)
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
        val topInset = when {
            !fullscreen -> systemBars.top
            !drawUnderCutout -> displayCutout.top
            else -> 0
        }
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
        val statusPadding = if (readerPreferences.showPageNumber.get()) {
            maxOf(
                verticalPadding,
                ((16f * resources.configuration.fontScale + 20f) * resources.displayMetrics.density).roundToInt(),
            )
        } else {
            verticalPadding
        }
        val statusAtTop = readerPreferences.readerStatusPosition.get() ==
            eu.kanade.tachiyomi.ui.reader.setting.ReaderStatusPosition.TOP
        setPadding(
            horizontalInsets.left,
            topInset + if (statusAtTop) statusPadding else verticalPadding,
            horizontalInsets.right,
            bottomInset + if (statusAtTop) verticalPadding else statusPadding,
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

    /**
     * Applies the same grayscale/inversion operations as [readerColorFilterPaint] to Compose edge colors.
     * The reader Fragment is filtered by an Android [ColorMatrixColorFilter], while the Compose edge bands
     * are separate layers and therefore need the transformed color explicitly.
     */
    private fun Color.applyReaderColorFilter(grayscale: Boolean, invertedColors: Boolean): Color {
        var red = red
        var green = green
        var blue = blue

        if (grayscale) {
            val luminance = 0.213f * red + 0.715f * green + 0.072f * blue
            red = luminance
            green = luminance
            blue = luminance
        }
        if (invertedColors) {
            red = 1f - red
            green = 1f - green
            blue = 1f - blue
        }

        return Color(red, green, blue, alpha)
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
