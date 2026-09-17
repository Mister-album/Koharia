package koharia.epub

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import koharia.connection.SharedAppPreferences
import koharia.epub.font.EpubFontId
import koharia.epub.font.EpubFontManager
import koharia.epub.locator.toNavigatorLocator
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubPreferencesBridge
import koharia.tts.progress.TtsProgressNotifier
import koharia.tts.reader.TtsTextModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.WeakHashMap
import kotlin.math.abs

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : Fragment() {

    interface Host {
        fun onTap(positionX: Float, positionY: Float): Boolean
        fun swipePageTurnsEnabled(): Boolean = true

        fun onLocatorChanged(locator: Locator)

        fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator)

        fun onPdfSourceAnchorChanged(id: String, href: String) {}
        fun preferredPdfSourceAnchor(): String? = null

        fun onBookPaginationChanged(
            generation: Long,
            pageCounts: Map<String, Int>,
            isComplete: Boolean,
        )

        fun onPaginationViewportChanged(viewport: EpubPaginationViewport)

        fun onExternalLinkActivated(url: AbsoluteUrl)

        fun onFootnoteActivated(link: Link, contentHtml: String)

        fun onImageInteraction(reference: EpubImageReference, interaction: EpubImageInteraction)

        fun onFontLoadFailed()

        fun onVisibleFontRequirementsCaptured(chapterId: Long, requirementsJson: String?)

        fun onNavigatorReady(fragment: EpubReaderFragment)

        fun onSessionMissing(chapterId: Long)
    }

    private val sessionRepository: EpubReaderSessionRepository = Injekt.get()
    private val fontManager: EpubFontManager = Injekt.get()
    private val eInkPreferences: EInkPreferences = Injekt.get()
    private val sharedAppPreferences: SharedAppPreferences = Injekt.get()
    private val epubPreferencesBridge = EpubPreferencesBridge()
    private val chapterId: Long
        get() = requireArguments().getLong(ARG_CHAPTER_ID)
    private val sourceId: Long
        get() = requireArguments().getLong(ARG_SOURCE_ID, -1L)
    private val epubLayoutPreferences by lazy {
        (activity as? EpubReaderActivity)?.sessionEpubLayoutPreferences() ?: if (sourceId > 0L) {
            sharedAppPreferences.epubLayoutPreferences()
        } else {
            Injekt.get<EpubLayoutPreferences>()
        }
    }
    private var host: Host? = null
    private var containerId: Int = View.NO_ID
    private var scannerContainerId: Int = View.NO_ID
    private var observedNavigator: EpubNavigatorFragment? = null
    private var navigatorInputListener: InputListener? = null
    private var pageTransitionController: EpubPageTransitionController? = null
    private var pageTransitionOverlay: EpubPageTransitionOverlayView? = null
    private var currentTransitionPageIndex = -1
    private var currentTransitionPageCount = 0
    private var currentTransitionHref: String? = null
    private var pdfAnchorCaptureJob: Job? = null
    private var lastReadingInteraction = SystemClock.uptimeMillis()
    private var paragraphIndentDebugGeneration = 0L
    private var paragraphIndentOverrideEnabled = false
    private var readerFontScale = 1f
    private var textAlignmentOverride: EpubLayoutPreferences.TextAlignment? = null
    private var tocHrefs: List<String> = emptyList()
    private var chapterBreaksEnabled = false
    private var continuousScrollInstallJob: Job? = null
    private var continuousScrollInstallHref: String? = null
    private var imageInteractionInstallJob: Job? = null
    private var fontSwitchJob: Job? = null
    private var fontRequirementCaptureJob: Job? = null
    private var paginationStartJob: Job? = null
    private var pendingPaginationRequest: EpubPaginationRequest? = null
    private var pendingFontKey: String? = null
    private var pendingFontPreferences: EpubPreferences? = null
    private var capturedFontRequirementsJson: String? = null
    private var appliedFontId = EpubFontId.ORIGINAL.value
    private var reportedFontFailureKey: String? = null
    private var preserveImageColors = true
    private var parentColorsInverted = false
    private var imageColorPolicyScript = buildEpubDocumentPreparationScript(
        paragraphIndentOverrideEnabled = paragraphIndentOverrideEnabled,
        textAlignment = textAlignmentOverride,
        tocHrefs = tocHrefs,
        chapterBreaksEnabled = chapterBreaksEnabled,
        preserveImageColors = preserveImageColors,
        parentColorsInverted = parentColorsInverted,
        readerFontScale = readerFontScale,
        longWordWrappingEnabled = true,
    )
    private var fontPreparation = buildEpubFontPreparationScript(
        fontManager = fontManager,
        selectedFontId = EpubFontId.ORIGINAL.value,
        publisherStyles = true,
    )
    private var imageColorPolicyGeneration = 0L
    private var imageColorPolicyRoot: View? = null
    private var imageColorPolicyPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private val installedImageColorPolicies = WeakHashMap<WebView, String>()
    private val pendingImageColorPolicies = WeakHashMap<WebView, String>()
    private val imageColorPolicyDrawWaits = WeakHashMap<WebView, ImageColorPolicyDrawWait>()
    private var continuousScrollInstalledHref: String? = null
    private var continuousScrollLocator: Locator? = null

    private val navigatorListener = object : EpubNavigatorFragment.Listener {
        override fun onExternalLinkActivated(url: AbsoluteUrl) {
            host?.onExternalLinkActivated(url)
        }

        override fun shouldFollowInternalLink(
            link: Link,
            context: HyperlinkNavigator.LinkContext?,
        ): Boolean {
            val footnote = context as? HyperlinkNavigator.FootnoteContext ?: return true
            val contentHtml = footnote.noteContent.trim().takeIf { it.isNotEmpty() } ?: return true
            val currentHost = host ?: return true
            pageTransitionController?.cancel()
            currentHost.onFootnoteActivated(link, contentHtml)
            return false
        }
    }

    @Suppress("DEPRECATION")
    private val paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
            pageTransitionController?.onPageChanged(pageIndex, locator.href.toString())
            currentTransitionPageIndex = pageIndex
            currentTransitionPageCount = totalPages
            currentTransitionHref = locator.href.toString()
            host?.onPageChanged(pageIndex, totalPages, locator)
            if (sessionRepository.get(chapterId)?.pdfReflow != null) {
                pdfAnchorCaptureJob?.cancel()
                pdfAnchorCaptureJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(100)
                    capturePdfSourceAnchor()
                }
            }
            scheduleImageInteractionsInstall()
        }

        override fun onPageLoaded() {
            pageTransitionController?.onPageLoaded()
            scheduleContinuousScrollInstall(readyNavigatorFragment())
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val session = sessionRepository.get(chapterId)
        paragraphIndentOverrideEnabled = epubLayoutPreferences.publisherStyles.get().not()
        readerFontScale = epubLayoutPreferences.fontSize.get()
        textAlignmentOverride = epubLayoutPreferences.textAlignment.get()
            .takeIf { paragraphIndentOverrideEnabled }
        tocHrefs = session?.publication?.tableOfContents?.flattenEpubTocHrefs().orEmpty()
        chapterBreaksEnabled =
            epubLayoutPreferences.readingMode.get() == EpubLayoutPreferences.ReadingMode.PAGINATED
        refreshDocumentPreparationPolicy()
        appliedFontId = epubLayoutPreferences.selectedFontId.get()
        logcat(LogPriority.DEBUG) {
            "EPUB fragment onCreate chapterId=$chapterId hasSession=${session != null}"
        }
        val navigatorConfiguration = epubNavigatorConfiguration().apply {
            registerJavascriptInterface(CONTINUOUS_SCROLL_BRIDGE_NAME) {
                ContinuousScrollJavascriptBridge()
            }
            registerJavascriptInterface(EPUB_IMAGE_BRIDGE_NAME) { resource ->
                EpubImageJavascriptBridge(resource)
            }
            registerJavascriptInterface(EPUB_FONT_BRIDGE_NAME) {
                EpubFontJavascriptBridge()
            }
        }
        childFragmentManager.fragmentFactory = session?.navigatorFactory?.createFragmentFactory(
            initialLocator = session.initialLocator,
            initialPreferences = epubPreferencesBridge.toReadiumPreferences(
                preferences = epubLayoutPreferences,
                publicationMetadata = session.publication.metadata,
            ),
            listener = navigatorListener,
            paginationListener = paginationListener,
            configuration = navigatorConfiguration,
        ) ?: EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return SwipePageTurnGate(requireContext(), onVerticalSwipe = ::navigateShortUnstitchedResource) {
            epubLayoutPreferences.readingMode.get() == EpubLayoutPreferences.ReadingMode.PAGINATED &&
                host?.swipePageTurnsEnabled() == false
        }.apply {
            addView(
                FragmentContainerView(requireContext()).apply {
                    id = R.id.epub_reader_navigator_container
                    containerId = id
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                FragmentContainerView(requireContext()).apply {
                    id = R.id.epub_reader_pagination_scanner_container
                    scannerContainerId = id
                    visibility = View.INVISIBLE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                EpubPageTransitionOverlayView(requireContext()).also {
                    pageTransitionOverlay = it
                    it.visibility = View.GONE
                    it.isClickable = false
                    it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val session = sessionRepository.get(chapterId)
        if (session == null) {
            host?.onSessionMissing(chapterId)
            return
        }
        val navigator = (childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment) ?: run {
            logcat(LogPriority.DEBUG) {
                "EPUB fragment create navigator chapterId=$chapterId containerId=$containerId"
            }
            val createdNavigator = childFragmentManager.fragmentFactory.instantiate(
                requireContext().classLoader,
                EpubNavigatorFragment::class.java.name,
            ) as EpubNavigatorFragment
            childFragmentManager.commitNow {
                setReorderingAllowed(true)
                replace(containerId, createdNavigator, NAVIGATOR_TAG)
            }
            createdNavigator
        }
        observeNavigator(navigator)
        observeNavigatorViewReady(navigator)
        val viewportView = view.findViewById<View>(containerId) ?: view
        val root = view as FrameLayout
        val overlay = pageTransitionOverlay
        if (overlay != null) {
            pageTransitionController = EpubPageTransitionController(
                fragment = this,
                root = root,
                content = viewportView,
                overlay = overlay,
                effectProvider = {
                    if (eInkPreferences.enabled.get()) {
                        PageTransitionEffect.NONE
                    } else {
                        PageTransitionEffect.fromPreference(epubLayoutPreferences.pageTransitionEffect.get())
                    }
                },
                rightToLeftProvider = {
                    epubLayoutPreferences.pageDirection.get() == EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT
                },
                currentLocationProvider = {
                    navigator.currentLocator.value.href.toString() to currentTransitionPageIndex
                },
                navigate = { forward, animated ->
                    clearContinuousScrollState()
                    if (forward) navigator.goForward(animated) else navigator.goBackward(animated)
                },
            )
        }
        viewportView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0 ||
                (width == oldRight - oldLeft && height == oldBottom - oldTop)
            ) {
                return@addOnLayoutChangeListener
            }
            pageTransitionController?.cancel()
            val configuration = resources.configuration
            host?.onPaginationViewportChanged(
                EpubPaginationViewport(
                    widthPx = width,
                    heightPx = height,
                    densityDpi = configuration.densityDpi,
                    fontScale = configuration.fontScale,
                    webViewVersion = WebView.getCurrentWebViewPackage()?.versionName.orEmpty(),
                ),
            )
        }
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    override fun onDestroyView() {
        pdfAnchorCaptureJob?.cancel()
        pdfAnchorCaptureJob = null
        pageTransitionController?.cancel()
        pageTransitionController = null
        pageTransitionOverlay = null
        clearContinuousScrollState()
        imageInteractionInstallJob?.cancel()
        imageInteractionInstallJob = null
        fontSwitchJob?.cancel()
        fontSwitchJob = null
        fontRequirementCaptureJob?.cancel()
        fontRequirementCaptureJob = null
        paginationStartJob?.cancel()
        paginationStartJob = null
        pendingPaginationRequest = null
        pendingFontKey = null
        pendingFontPreferences = null
        clearImageColorPolicyDrawGuard()
        clearNavigatorInputListener()
        super.onDestroyView()
    }

    fun goTo(link: Link): Boolean {
        val navigator = readyNavigatorFragment() ?: return false
        pageTransitionController?.cancel()
        clearContinuousScrollState()
        return navigator.go(link)
    }

    suspend fun capturePdfSourceAnchor() {
        if (sessionRepository.get(chapterId)?.pdfReflow == null) return
        val navigator = readyNavigatorFragment() ?: return
        val before = navigator.currentLocator.value
        val preferred = org.json.JSONObject.quote(host?.preferredPdfSourceAnchor().orEmpty())
        val raw = withTimeoutOrNull(1_000) {
            navigator.evaluateJavascript(
                """
            (function(){
                const width = document.documentElement.clientWidth;
                const height = document.documentElement.clientHeight;
                const visible = element => element && Array.from(element.getClientRects()).some(
                    r => r.right > 1 && r.left < width - 1 && r.bottom > 1 && r.top < height - 1
                );
                const preferred = document.getElementById($preferred);
                if (visible(preferred)) {
                    const anchor = preferred.matches('[data-pdf-anchor]') ? preferred
                        : preferred.querySelector('[data-pdf-anchor]');
                    if (visible(anchor)) return anchor.getAttribute('data-pdf-anchor');
                }
                for (const element of document.querySelectorAll('[data-pdf-anchor]')) {
                    for (const r of element.getClientRects()) {
                        if (r.right > 1 && r.left < width - 1 && r.bottom > 1 && r.top < height - 1)
                            return element.getAttribute('data-pdf-anchor');
                    }
                }
                return null;
            })();
                """.trimIndent(),
            )
        } ?: return
        if (before != navigator.currentLocator.value) return
        val id = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return
        host?.onPdfSourceAnchorChanged(id, before.href.toString())
    }

    suspend fun currentTocHref(links: List<Link>): String? {
        val navigator = readyNavigatorFragment() ?: return null
        val location = navigator.currentLocator.value
        val candidates = links.filter { it.href.toString().sameEpubResource(location.href.toString()) }
        if (candidates.size <= 1) return candidates.firstOrNull()?.href?.toString()
        val script = currentEpubTocIndexScript(candidates.map { it.href.toString().substringAfter('#', "") })
        val index = withTimeoutOrNull(1_000) { navigator.evaluateJavascript(script)?.toIntOrNull() }
        if (navigator.currentLocator.value != location) return null
        return index?.let(candidates::getOrNull)?.href?.toString()
    }

    fun goTo(locator: Locator): Boolean {
        val navigator = readyNavigatorFragment() ?: return false
        pageTransitionController?.cancel()
        val publication = sessionRepository.get(chapterId)?.publication ?: return false
        clearContinuousScrollState()
        return navigator.go(publication.toNavigatorLocator(locator))
    }

    fun goForward(origin: PageTurnOrigin? = null): Boolean {
        lastReadingInteraction = SystemClock.uptimeMillis()
        val navigator = readyNavigatorFragment() ?: return false
        navigateContinuousScroll(forward = true)?.let { return it }
        if (!canTurnPage(navigator, true)) return true
        return pageTransitionController?.turnPage(
            forward = true,
            currentHref = navigator.currentLocator.value.href.toString(),
            currentPageIndex = currentTransitionPageIndex,
            origin = origin ?: PageTurnOrigin.center(PageTurnCause.PROGRAMMATIC),
        )
            ?: navigator.goForward()
    }

    fun goBackward(origin: PageTurnOrigin? = null): Boolean {
        lastReadingInteraction = SystemClock.uptimeMillis()
        val navigator = readyNavigatorFragment() ?: return false
        navigateContinuousScroll(forward = false)?.let { return it }
        if (!canTurnPage(navigator, false)) return true
        return pageTransitionController?.turnPage(
            forward = false,
            currentHref = navigator.currentLocator.value.href.toString(),
            currentPageIndex = currentTransitionPageIndex,
            origin = origin ?: PageTurnOrigin.center(PageTurnCause.PROGRAMMATIC),
        )
            ?: navigator.goBackward()
    }

    private fun canTurnPage(navigator: EpubNavigatorFragment, forward: Boolean): Boolean {
        val href = navigator.currentLocator.value.href.toString()
        val publication = sessionRepository.get(chapterId)?.publication ?: return false
        if (publication.metadata.layout == org.readium.r2.shared.publication.Layout.FIXED) return true
        if (currentTransitionHref?.sameEpubResource(href) != true || currentTransitionPageCount <= 0) return false
        val order = publication.readingOrder
        val index = order.indexOfFirst { it.href.toString().sameEpubResource(href) }
        val page = if (navigator.settings.value.readingProgression ==
            org.readium.r2.navigator.preferences.ReadingProgression.RTL
        ) {
            currentTransitionPageCount - 1 - currentTransitionPageIndex
        } else {
            currentTransitionPageIndex
        }
        return canTurnEpubPage(forward, index, order.size, page, currentTransitionPageCount)
    }

    internal suspend fun awaitReadingIdle() {
        while (true) {
            val remaining = 900L - (SystemClock.uptimeMillis() - lastReadingInteraction)
            if (remaining <= 0) return
            delay(remaining)
        }
    }

    fun cancelPageTransition() {
        pageTransitionController?.cancel()
    }

    fun prepareFontSelection() {
        if (!isAdded || view == null) return
        val navigator = readyNavigatorFragment() ?: return
        capturedFontRequirementsJson = null
        host?.onVisibleFontRequirementsCaptured(chapterId, null)
        fontRequirementCaptureJob?.cancel()
        fontRequirementCaptureJob = viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                navigator.evaluateJavascript(EPUB_CAPTURE_VISIBLE_FONT_REQUIREMENTS_SCRIPT)
            }.onSuccess { requirements ->
                capturedFontRequirementsJson = requirements
                host?.onVisibleFontRequirementsCaptured(chapterId, requirements)
                logcat(LogPriority.DEBUG) {
                    "EPUB visible font requirements captured chapterId=$chapterId requirements=$requirements"
                }
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to inspect visible EPUB text before opening font picker"
                }
            }
        }
    }

    fun restoreFontSelectionContext(requirementsJson: String?) {
        capturedFontRequirementsJson = requirementsJson
    }

    fun submitPreferences(preferences: EpubPreferences) {
        if (!isAdded || view == null) return
        val navigator = readyNavigatorFragment()
        val keepContinuousScrollPosition =
            preferences.scroll != false &&
                epubLayoutPreferences.readingMode.get() == EpubLayoutPreferences.ReadingMode.SCROLL
        if (!keepContinuousScrollPosition) {
            clearContinuousScrollState()
        }
        val paragraphOverrideEnabled = preferences.publisherStyles == false
        val nextReaderFontScale = preferences.fontSize?.toFloat() ?: readerFontScale
        val nextTextAlignmentOverride = epubLayoutPreferences.textAlignment.get()
            .takeIf { paragraphOverrideEnabled }
        val nextChapterBreaksEnabled = preferences.scroll != true
        val documentPolicyChanged =
            paragraphIndentOverrideEnabled != paragraphOverrideEnabled ||
                textAlignmentOverride != nextTextAlignmentOverride ||
                chapterBreaksEnabled != nextChapterBreaksEnabled ||
                (paragraphOverrideEnabled && readerFontScale != nextReaderFontScale)
        paragraphIndentOverrideEnabled = paragraphOverrideEnabled
        readerFontScale = nextReaderFontScale
        textAlignmentOverride = nextTextAlignmentOverride
        chapterBreaksEnabled = nextChapterBreaksEnabled
        val nextFontPreparation = buildEpubFontPreparationScript(
            fontManager = fontManager,
            selectedFontId = epubLayoutPreferences.selectedFontId.get(),
            publisherStyles = preferences.publisherStyles != false || fontOverridesDisabled(),
            capturedRequirementsJson = capturedFontRequirementsJson,
        )
        val fontPolicyChanged = nextFontPreparation.key != fontPreparation.key ||
            nextFontPreparation.script != fontPreparation.script
        fontPreparation = nextFontPreparation
        if (documentPolicyChanged || fontPolicyChanged) {
            refreshDocumentPreparationPolicy()
        }
        if (nextFontPreparation.requiresAsyncLoad && navigator != null &&
            (fontPolicyChanged || pendingFontKey == nextFontPreparation.key)
        ) {
            val previousFontId = appliedFontId
            val expectedKey = nextFontPreparation.key
            val requestedFontId = epubLayoutPreferences.selectedFontId.get()
            pendingFontPreferences = preferences
            if (!fontPolicyChanged && fontSwitchJob?.isActive == true) {
                return
            }
            fontSwitchJob?.cancel()
            pendingFontKey = expectedKey
            reportedFontFailureKey = null
            fontSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
                val startedAt = SystemClock.elapsedRealtime()
                navigator.evaluateJavascript(nextFontPreparation.script)
                val loaded = withTimeoutOrNull(FONT_PREPARATION_DRAW_TIMEOUT_MS) {
                    while (true) {
                        if (fontPreparation.key != expectedKey) return@withTimeoutOrNull false
                        val key = kotlinx.serialization.json.JsonPrimitive(expectedKey)
                        val status = navigator.evaluateJavascript(
                            "window.__kohariaFontState && window.__kohariaFontState.key === $key ? " +
                                "window.__kohariaFontState.status : 'missing'",
                        )
                        if (status == "\"ready\"") return@withTimeoutOrNull true
                        if (status == "\"failed\"") return@withTimeoutOrNull false
                        delay(FONT_PREPARATION_POLL_MS)
                    }
                } == true
                if (fontPreparation.key != expectedKey) return@launch
                if (loaded) {
                    appliedFontId = requestedFontId
                    navigator.submitPreferences(pendingFontPreferences ?: preferences)
                    if (capturedFontRequirementsJson != null) {
                        capturedFontRequirementsJson = null
                        host?.onVisibleFontRequirementsCaptured(chapterId, null)
                        refreshDocumentPreparationPolicy()
                    }
                } else {
                    val fallbackFontId = previousFontId.takeUnless { it == requestedFontId }
                        ?: EpubFontId.ORIGINAL.value
                    reportFontFailure(expectedKey, fallbackFontId)
                }
                logcat(if (loaded) LogPriority.DEBUG else LogPriority.WARN) {
                    "EPUB visible font preparation completed chapterId=$chapterId key=$expectedKey " +
                        "loaded=$loaded elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                }
                if (pendingFontKey == expectedKey) {
                    pendingFontKey = null
                    pendingFontPreferences = null
                }
            }
        } else {
            fontSwitchJob?.cancel()
            fontSwitchJob = null
            pendingFontKey = null
            pendingFontPreferences = null
            appliedFontId = epubLayoutPreferences.selectedFontId.get()
            navigator?.submitPreferences(preferences)
        }
        scheduleImageInteractionsInstall(navigator)
        if (keepContinuousScrollPosition) {
            // Keep the JS-reported Locator when the visible resource is an adjacent iframe. The
            // native navigator still points at the XHTML that owns the continuous-scroll window.
            scheduleContinuousScrollInstall(navigator)
        }
        logcat(LogPriority.DEBUG) {
            "EPUB paragraph indent submitted chapterId=$chapterId " +
                "publisherStyles=${preferences.publisherStyles} " +
                "textAlign=${preferences.textAlign} " +
                "paragraphIndent=${preferences.paragraphIndent} " +
                "paragraphSpacing=${preferences.paragraphSpacing} lineHeight=${preferences.lineHeight}"
        }
        logComputedParagraphIndent()
    }

    private fun reportFontFailure(expectedKey: String, fallbackFontId: String) {
        if (fontPreparation.key != expectedKey || reportedFontFailureKey == expectedKey) return
        reportedFontFailureKey = expectedKey
        appliedFontId = fallbackFontId
        capturedFontRequirementsJson = null
        host?.onVisibleFontRequirementsCaptured(chapterId, null)
        epubLayoutPreferences.selectedFontId.set(fallbackFontId)
        host?.onFontLoadFailed()
    }

    private fun logComputedParagraphIndent() {
        if (!isAdded || view == null) return
        val debugGeneration = ++paragraphIndentDebugGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PARAGRAPH_INDENT_DEBUG_DELAY_MS)
            if (debugGeneration != paragraphIndentDebugGeneration || !isAdded || view == null) return@launch
            val result = readyNavigatorFragment()?.evaluateJavascript(PARAGRAPH_INDENT_DEBUG_SCRIPT)
                ?: return@launch
            logcat(LogPriority.DEBUG) {
                "EPUB paragraph indent computed chapterId=$chapterId result=$result"
            }
        }
    }

    internal fun startPagination(request: EpubPaginationRequest) {
        if (!isAdded || view == null) return
        paginationStartJob?.cancel()
        paginationStartJob = null
        pendingPaginationRequest = request
        val existing = paginationScannerFragment()
        if (!request.shouldScan) {
            pendingPaginationRequest = null
            if (existing != null) {
                childFragmentManager.commitNow { remove(existing) }
            }
            return
        }
        paginationStartJob = viewLifecycleOwner.lifecycleScope.launch {
            fontSwitchJob?.join()
            if (!isAdded || view == null || pendingPaginationRequest?.generation != request.generation) return@launch
            val backgroundWaitStartedAt = SystemClock.elapsedRealtime()
            val backgroundReady = awaitVisibleFontBackground()
            logcat(LogPriority.DEBUG) {
                "EPUB pagination font preparation completed chapterId=$chapterId " +
                    "generation=${request.generation} ready=$backgroundReady " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - backgroundWaitStartedAt}"
            }
            if (!backgroundReady) {
                if (pendingPaginationRequest?.generation == request.generation) pendingPaginationRequest = null
                return@launch
            }
            if (!isAdded || view == null || pendingPaginationRequest?.generation != request.generation) return@launch
            pendingPaginationRequest = null
            awaitReadingIdle()
            if (!isAdded || view == null) return@launch
            launchPaginationScanner(request)
        }
    }

    private fun launchPaginationScanner(request: EpubPaginationRequest) {
        if (!isAdded || view == null || childFragmentManager.isStateSaved) return
        childFragmentManager.commitNow {
            setReorderingAllowed(true)
            replace(
                scannerContainerId,
                EpubPaginationScannerFragment.newInstance(chapterId, sourceId, request),
                PAGINATION_SCANNER_TAG,
            )
        }
    }

    private suspend fun awaitVisibleFontBackground(): Boolean {
        if (!fontPreparation.requiresAsyncLoad) return true
        val expectedKey = fontPreparation.key
        return withTimeoutOrNull(FONT_BACKGROUND_WAIT_TIMEOUT_MS) {
            while (true) {
                if (fontPreparation.key != expectedKey) return@withTimeoutOrNull false
                val key = kotlinx.serialization.json.JsonPrimitive(expectedKey)
                val status = readyNavigatorFragment()?.evaluateJavascript(
                    "(function() { var state = window.__kohariaFontState; " +
                        "if (!state || state.key !== $key) return 'stale'; " +
                        "if (state.status === 'failed') return 'failed'; " +
                        "if (state.status === 'ready' && state.backgroundStatus === 'complete') return 'complete'; " +
                        "return 'waiting'; })()",
                ) ?: return@withTimeoutOrNull false
                when (status) {
                    "\"complete\"" -> return@withTimeoutOrNull true
                    "\"failed\"", "\"stale\"" -> return@withTimeoutOrNull false
                }
                delay(FONT_BACKGROUND_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: true
    }

    fun stopPagination() {
        paginationStartJob?.cancel()
        paginationStartJob = null
        pendingPaginationRequest = null
        paginationScannerFragment()?.let { scanner ->
            if (!childFragmentManager.isStateSaved) {
                childFragmentManager.commitNow { remove(scanner) }
            }
        }
    }

    internal fun onBookPaginationCalculated(
        generation: Long,
        pageCounts: Map<String, Int>,
        isComplete: Boolean,
    ) {
        host?.onBookPaginationChanged(generation, pageCounts, isComplete)
        if (isComplete) {
            view?.post(::stopPagination)
        }
    }

    internal fun updateImageColorPolicy(
        preserveImageColors: Boolean,
        parentColorsInverted: Boolean,
    ) {
        if (this.preserveImageColors == preserveImageColors &&
            this.parentColorsInverted == parentColorsInverted
        ) {
            return
        }
        this.preserveImageColors = preserveImageColors
        this.parentColorsInverted = parentColorsInverted
        refreshDocumentPreparationPolicy()
        val navigator = readyNavigatorFragment()
        scheduleImageInteractionsInstall(navigator)
        scheduleContinuousScrollInstall(navigator)
    }

    private fun refreshDocumentPreparationPolicy() {
        fontPreparation = buildEpubFontPreparationScript(
            fontManager = fontManager,
            selectedFontId = epubLayoutPreferences.selectedFontId.get(),
            publisherStyles = epubLayoutPreferences.publisherStyles.get() || fontOverridesDisabled(),
            capturedRequirementsJson = capturedFontRequirementsJson,
        )
        val documentScript = buildEpubDocumentPreparationScript(
            paragraphIndentOverrideEnabled = paragraphIndentOverrideEnabled,
            textAlignment = textAlignmentOverride,
            tocHrefs = tocHrefs,
            chapterBreaksEnabled = chapterBreaksEnabled,
            preserveImageColors = preserveImageColors,
            parentColorsInverted = parentColorsInverted,
            readerFontScale = readerFontScale,
            longWordWrappingEnabled = !fontOverridesDisabled(),
            paginated = shouldFitStandaloneImage(),
        )
        imageColorPolicyScript = """
            (function() {
                const documentStatus = $documentScript;
                const fontStatus = ${fontPreparation.script};
                return documentStatus === 'pending' ? 'pending' : fontStatus;
            })()
        """.trimIndent()
        imageColorPolicyGeneration++
        installedImageColorPolicies.clear()
        pendingImageColorPolicies.clear()
        imageColorPolicyDrawWaits.clear()
        imageColorPolicyRoot?.postInvalidateOnAnimation()
    }

    private fun observeNavigator(navigator: EpubNavigatorFragment) {
        logcat(LogPriority.DEBUG) {
            "EPUB fragment observe navigator chapterId=$chapterId"
        }
        clearNavigatorInputListener()
        val inputListener = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                lastReadingInteraction = SystemClock.uptimeMillis()
                val width = navigator.publicationView.width.toFloat().takeIf { it > 0f } ?: return false
                val height = navigator.publicationView.height.toFloat().takeIf { it > 0f } ?: return false
                return host?.onTap(
                    positionX = event.point.x / width,
                    positionY = event.point.y / height,
                ) ?: false
            }

            override fun onDrag(event: DragEvent): Boolean {
                lastReadingInteraction = SystemClock.uptimeMillis()
                if (event.type == DragEvent.Type.Start) pageTransitionController?.cancel()
                // R2WebView owns the complete drag and settling animation, including resource boundaries.
                return false
            }
        }
        observedNavigator = navigator
        navigatorInputListener = inputListener
        navigator.addInputListener(inputListener)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    val installedHref = continuousScrollInstalledHref
                    if (installedHref == null || !locator.href.toString().sameEpubResource(installedHref)) {
                        continuousScrollLocator = null
                        continuousScrollInstalledHref = null
                        host?.onLocatorChanged(locator)
                        scheduleContinuousScrollInstall(navigator, locator)
                    }
                    scheduleImageInteractionsInstall(navigator)
                }
            }
        }
    }

    private fun clearNavigatorInputListener() {
        navigatorInputListener?.let { listener ->
            observedNavigator?.removeInputListener(listener)
        }
        navigatorInputListener = null
        observedNavigator = null
    }

    private fun observeNavigatorViewReady(navigator: EpubNavigatorFragment) {
        navigator.viewLifecycleOwnerLiveData.observe(viewLifecycleOwner) { owner ->
            if (owner == null) {
                clearImageColorPolicyDrawGuard()
                return@observe
            }
            if (!isAdded || view == null) return@observe
            installImageColorPolicyDrawGuard(navigator.publicationView)
            host?.onNavigatorReady(this)
            scheduleImageInteractionsInstall(navigator)
            scheduleContinuousScrollInstall(navigator)
        }
    }

    private fun scheduleImageInteractionsInstall(
        navigator: EpubNavigatorFragment? = readyNavigatorFragment(),
    ) {
        imageInteractionInstallJob?.cancel()
        if (navigator == null || !isAdded || view == null) return
        imageColorPolicyRoot?.postInvalidateOnAnimation()
        imageInteractionInstallJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(IMAGE_INTERACTION_INSTALL_DELAY_MS)
            if (!isAdded || view == null || readyNavigatorFragment() !== navigator) return@launch
            val configuration = ViewConfiguration.get(requireContext())
            val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
            try {
                navigator.evaluateJavascript(
                    buildEpubImageInteractionInstallScript(
                        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout(),
                        touchSlopCssPx = configuration.scaledTouchSlop / density,
                        preserveImageColors = preserveImageColors,
                        parentColorsInverted = parentColorsInverted,
                        paginated = shouldFitStandaloneImage(),
                    ) + ";\n" + EPUB_WARM_NEARBY_IMAGES_SCRIPT,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Failed to install EPUB image interactions" }
            }
        }
    }

    private fun installImageColorPolicyDrawGuard(root: View) {
        if (imageColorPolicyRoot === root && imageColorPolicyPreDrawListener != null) {
            root.postInvalidateOnAnimation()
            return
        }
        clearImageColorPolicyDrawGuard()
        imageColorPolicyRoot = root
        imageColorPolicyPreDrawListener = ViewTreeObserver.OnPreDrawListener {
            applyImageColorPolicyBeforeDraw(root)
        }.also { listener ->
            root.viewTreeObserver.addOnPreDrawListener(listener)
        }
        root.postInvalidateOnAnimation()
    }

    private fun clearImageColorPolicyDrawGuard() {
        val root = imageColorPolicyRoot
        val listener = imageColorPolicyPreDrawListener
        if (root != null && listener != null && root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnPreDrawListener(listener)
        }
        imageColorPolicyRoot = null
        imageColorPolicyPreDrawListener = null
        installedImageColorPolicies.clear()
        pendingImageColorPolicies.clear()
        imageColorPolicyDrawWaits.clear()
    }

    private fun applyImageColorPolicyBeforeDraw(root: View): Boolean {
        val generation = imageColorPolicyGeneration
        val script = imageColorPolicyScript
        val expectedFontKey = fontPreparation.key
        val expectedRequiresAsyncFontLoad = fontPreparation.requiresAsyncLoad
        var visiblePolicyPending = false
        var visibleFontPolicyPending = false
        if (fontPreparation.requiresAsyncLoad) {
            root.forEachWebView { webView ->
                val url = webView.url?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return@forEachWebView
                val policyKey = "$generation:$url"
                if (webView.isVisiblyDrawn() && installedImageColorPolicies[webView] != policyKey) {
                    visibleFontPolicyPending = true
                }
            }
        }
        root.forEachWebView { webView ->
            val url = webView.url?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return@forEachWebView
            val policyKey = "$generation:$url"
            val isVisible = webView.isVisiblyDrawn()
            if (visibleFontPolicyPending && !isVisible) return@forEachWebView
            if (webView.progress < 100 && fontPreparation.requiresAsyncLoad) {
                installedImageColorPolicies.remove(webView)
                if (isVisible && shouldBlockImageColorPolicyDraw(root, webView, policyKey)) {
                    visiblePolicyPending = true
                    root.postInvalidateOnAnimation()
                }
                return@forEachWebView
            }
            if (installedImageColorPolicies[webView] == policyKey) {
                imageColorPolicyDrawWaits.remove(webView)
                return@forEachWebView
            }

            if (pendingImageColorPolicies[webView] != policyKey) {
                pendingImageColorPolicies[webView] = policyKey
                val wasVisible = isVisible
                runCatching {
                    webView.evaluateJavascript(script) { result ->
                        if (pendingImageColorPolicies[webView] == policyKey) {
                            pendingImageColorPolicies.remove(webView)
                        }
                        val documentReady = result != "\"pending\""
                        val fontReady = !expectedRequiresAsyncFontLoad ||
                            result == "\"ready\"" || result == "\"failed\""
                        if (result == "\"failed\"" && expectedRequiresAsyncFontLoad && wasVisible &&
                            imageColorPolicyGeneration == generation && fontPreparation.key == expectedFontKey &&
                            pendingFontKey != expectedFontKey
                        ) {
                            val fallbackFontId = appliedFontId.takeUnless {
                                it == epubLayoutPreferences.selectedFontId.get()
                            } ?: EpubFontId.ORIGINAL.value
                            reportFontFailure(expectedFontKey, fallbackFontId)
                        }
                        if (documentReady && fontReady && imageColorPolicyGeneration == generation &&
                            webView.url?.substringBefore('#') == url
                        ) {
                            installedImageColorPolicies[webView] = policyKey
                            webView.evaluateJavascript(EPUB_WARM_NEARBY_IMAGES_SCRIPT, null)
                            imageColorPolicyDrawWaits.remove(webView)
                        }
                        root.postInvalidateOnAnimation()
                    }
                    webView.postDelayed(
                        {
                            if (pendingImageColorPolicies[webView] == policyKey) {
                                pendingImageColorPolicies.remove(webView)
                                if (imageColorPolicyGeneration == generation &&
                                    webView.url?.substringBefore('#') == url
                                ) {
                                    installedImageColorPolicies[webView] = policyKey
                                    imageColorPolicyDrawWaits.remove(webView)
                                }
                                root.postInvalidateOnAnimation()
                            }
                        },
                        currentDrawTimeoutMs(),
                    )
                }.onFailure {
                    pendingImageColorPolicies.remove(webView)
                    root.postInvalidateOnAnimation()
                }
            }
            if (isVisible && shouldBlockImageColorPolicyDraw(root, webView, policyKey)) {
                visiblePolicyPending = true
            }
        }
        return !visiblePolicyPending
    }

    private fun shouldBlockImageColorPolicyDraw(
        root: View,
        webView: WebView,
        policyKey: String,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val currentWait = imageColorPolicyDrawWaits[webView]
        val wait = if (currentWait?.policyKey == policyKey) {
            currentWait
        } else {
            val expectedGeneration = imageColorPolicyGeneration
            val expectedFontKey = fontPreparation.key
            val expectedRequiresAsyncFontLoad = fontPreparation.requiresAsyncLoad
            val wasVisible = webView.isVisiblyDrawn()
            ImageColorPolicyDrawWait(
                policyKey = policyKey,
                expiresAt = now + currentDrawTimeoutMs(),
            ).also { newWait ->
                imageColorPolicyDrawWaits[webView] = newWait
                webView.postDelayed(
                    {
                        if (imageColorPolicyDrawWaits[webView] == newWait) {
                            if (expectedRequiresAsyncFontLoad && wasVisible && webView.isVisiblyDrawn() &&
                                imageColorPolicyGeneration == expectedGeneration &&
                                fontPreparation.key == expectedFontKey && pendingFontKey != expectedFontKey
                            ) {
                                val timedOutKey = kotlinx.serialization.json.JsonPrimitive(expectedFontKey)
                                webView.evaluateJavascript(
                                    "if (window.__kohariaFontState && " +
                                        "window.__kohariaFontState.key === $timedOutKey && " +
                                        "window.__kohariaFontState.status === 'loading') { " +
                                        "window.__kohariaFontState = { key: $timedOutKey + ':cancelled', " +
                                        "status: 'failed', faces: [] }; }",
                                    null,
                                )
                                val fallbackFontId = appliedFontId.takeUnless {
                                    it == epubLayoutPreferences.selectedFontId.get()
                                } ?: EpubFontId.ORIGINAL.value
                                reportFontFailure(expectedFontKey, fallbackFontId)
                            }
                            root.postInvalidateOnAnimation()
                        }
                    },
                    currentDrawTimeoutMs(),
                )
            }
        }
        return now < wait.expiresAt
    }

    private fun View.forEachWebView(action: (WebView) -> Unit) {
        if (this is WebView) {
            action(this)
            return
        }
        if (this !is ViewGroup) return
        repeat(childCount) { index ->
            getChildAt(index).forEachWebView(action)
        }
    }

    private fun WebView.isVisiblyDrawn(): Boolean =
        isShown && width > 0 && height > 0 && getGlobalVisibleRect(Rect())

    private fun currentDrawTimeoutMs(): Long =
        if (fontPreparation.requiresAsyncLoad) FONT_PREPARATION_DRAW_TIMEOUT_MS else IMAGE_COLOR_POLICY_DRAW_TIMEOUT_MS

    private fun fontOverridesDisabled(): Boolean =
        sessionRepository.get(chapterId)?.publication?.metadata?.layout ==
            org.readium.r2.shared.publication.Layout.FIXED

    private fun shouldFitStandaloneImage(): Boolean =
        epubLayoutPreferences.readingMode.get() == EpubLayoutPreferences.ReadingMode.PAGINATED &&
            !fontOverridesDisabled()

    private fun navigateShortUnstitchedResource(forward: Boolean): Boolean {
        if (epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL ||
            continuousScrollInstalledHref != null
        ) {
            return false
        }
        val navigator = readyNavigatorFragment() ?: return false
        val locator = navigator.currentLocator.value
        var shortVisibleResource = false
        navigator.publicationView.forEachWebView { webView ->
            if (webView.isVisiblyDrawn() && webView.progress == 100 &&
                webView.url?.sameEpubResource(locator.href.toString()) == true &&
                !webView.canScrollVertically(-1) && !webView.canScrollVertically(1)
            ) {
                shortVisibleResource = true
            }
        }
        if (!shortVisibleResource) return false
        val order = sessionRepository.get(chapterId)?.publication?.readingOrder ?: return false
        val index = order.indexOfFirst { it.href.toString().sameEpubResource(locator.href.toString()) }
        if (index < 0) return false
        val target = order.getOrNull(index + if (forward) 1 else -1) ?: return false
        return goTo(target)
    }

    private fun scheduleContinuousScrollInstall(
        navigator: EpubNavigatorFragment?,
        locator: Locator? = navigator?.currentLocator?.value,
    ) {
        if (continuousScrollInstallJob?.isActive == true &&
            locator?.href?.toString() == continuousScrollInstallHref
        ) {
            return
        }
        continuousScrollInstallJob?.cancel()
        if (navigator == null || locator == null ||
            epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL
        ) {
            return
        }
        continuousScrollInstallHref = locator.href.toString()
        continuousScrollInstallJob = viewLifecycleOwner.lifecycleScope.launch {
            var attempt = 0
            while (true) {
                delay(if (attempt < 20) CONTINUOUS_SCROLL_INSTALL_DELAY_MS else 1_000L)
                attempt++
                if (!isAdded || view == null || readyNavigatorFragment() !== navigator ||
                    epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL
                ) {
                    return@launch
                }
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) continue
                val currentLocator = navigator.currentLocator.value
                if (!currentLocator.href.toString().sameEpubResource(locator.href.toString())) return@launch
                val session = sessionRepository.get(chapterId) ?: return@launch
                val currentIndex = session.publication.readingOrder.indexOfFirst {
                    it.href.toString().sameEpubResource(locator.href.toString())
                }
                if (currentIndex < 0) return@launch
                val resources = session.publication.readingOrder.mapIndexedNotNull { index, link ->
                    link.readiumServedUrl(session.publication.baseUrl)?.let { servedUrl ->
                        EpubContinuousScrollResource(
                            index = index,
                            href = link.href.toString(),
                            url = servedUrl,
                        )
                    }
                }
                if (resources.size != session.publication.readingOrder.size) return@launch
                val result = try {
                    kotlinx.coroutines.withTimeoutOrNull(2_000) {
                        navigator.evaluateJavascript(
                            buildEpubContinuousScrollInstallScript(
                                resources = resources,
                                currentIndex = currentIndex,
                                initialProgression = currentLocator.locations.progression ?: 0.0,
                                imageInteractionScript = buildEpubImageInteractionInstallScript(
                                    longPressTimeoutMs = ViewConfiguration.getLongPressTimeout(),
                                    touchSlopCssPx = ViewConfiguration.get(requireContext()).scaledTouchSlop /
                                        (requireContext().resources.displayMetrics.density.takeIf { it > 0f } ?: 1f),
                                    preserveImageColors = preserveImageColors,
                                    parentColorsInverted = parentColorsInverted,
                                    paginated = false,
                                ),
                                contentPreparationScript = """
                            (function() {
                                ${buildEpubTypographyPreparationScript(
                                    paragraphIndentOverrideEnabled = paragraphIndentOverrideEnabled,
                                    textAlignment = textAlignmentOverride,
                                    longWordWrappingEnabled = !fontOverridesDisabled(),
                                )};
                                ${buildEpubFootnoteCompatibilityScript(
                                    applyReaderStyles = paragraphIndentOverrideEnabled,
                                    readerFontScale = readerFontScale,
                                )};
                                ${fontPreparation.script};
                                return true;
                            })()
                                """.trimIndent(),
                            ),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (attempt == 1) logcat(LogPriority.WARN, error) { "Continuous EPUB installation failed" }
                    null
                }
                val completedHref = navigator.currentLocator.value.href.toString()
                if (!completedHref.sameEpubResource(locator.href.toString())) return@launch
                if (result == "\"installed\"" || result == "\"ready\"") {
                    if (result == "\"installed\"") {
                        continuousScrollInstalledHref = locator.href.toString()
                        continuousScrollLocator = locator
                    } else {
                        // A preference refresh reuses the existing JS window. Do not replace a newer
                        // iframe Locator with the stale native Locator for the window's owner XHTML.
                        continuousScrollInstalledHref = continuousScrollInstalledHref ?: locator.href.toString()
                        continuousScrollLocator = continuousScrollLocator ?: locator
                    }
                    logcat(LogPriority.DEBUG) {
                        "EPUB continuous resource flow installed chapterId=$chapterId " +
                            "resourceIndex=$currentIndex resources=${resources.size}"
                    }
                    return@launch
                }
                if (attempt == 20) {
                    logcat(LogPriority.WARN) {
                        "Continuous EPUB document is not ready chapterId=$chapterId"
                    }
                }
            }
        }
    }

    private fun clearContinuousScrollState() {
        continuousScrollInstallJob?.cancel()
        continuousScrollInstallJob = null
        continuousScrollInstallHref = null
        continuousScrollInstalledHref = null
        continuousScrollLocator = null
    }

    /** Returns null when native navigation should handle the request, otherwise its result. */
    private fun navigateContinuousScroll(forward: Boolean): Boolean? {
        if (epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL ||
            continuousScrollInstalledHref == null
        ) {
            return null
        }
        val current = continuousScrollLocator ?: return null
        val publication = sessionRepository.get(chapterId)?.publication ?: return null
        val currentIndex = publication.readingOrder.indexOfFirst {
            it.href.toString().sameEpubResource(current.href.toString())
        }
        if (currentIndex < 0) return false
        val targetIndex = currentIndex + if (forward) 1 else -1
        val target = publication.readingOrder.getOrNull(targetIndex) ?: return false
        val locator = createContinuousScrollLocator(
            link = target,
            progression = if (forward) 0.0 else 1.0,
            positions = sessionRepository.get(chapterId)?.positionsController?.currentPositions().orEmpty(),
        ) ?: return false
        return goTo(locator)
    }

    @Suppress("unused")
    private inner class ContinuousScrollJavascriptBridge {
        @JavascriptInterface
        fun onLocationChanged(resourceIndex: Int, progression: Double) {
            view?.post {
                if (!isAdded || view == null || continuousScrollInstalledHref == null ||
                    epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL
                ) {
                    return@post
                }
                val session = sessionRepository.get(chapterId) ?: return@post
                val link = session.publication.readingOrder.getOrNull(resourceIndex) ?: return@post
                val locator = createContinuousScrollLocator(
                    link = link,
                    progression = progression.coerceIn(0.0, 1.0),
                    positions = session.positionsController.currentPositions(),
                ) ?: return@post
                continuousScrollLocator = locator
                host?.onLocatorChanged(locator)
            }
        }

        @JavascriptInterface
        fun onResourceLoadFailed(resourceIndex: Int) {
            view?.post {
                if (!isAdded || view == null || continuousScrollInstalledHref == null ||
                    epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL
                ) {
                    return@post
                }
                val session = sessionRepository.get(chapterId) ?: return@post
                val link = session.publication.readingOrder.getOrNull(resourceIndex) ?: return@post
                val locator = createContinuousScrollLocator(
                    link = link,
                    progression = 0.0,
                    positions = session.positionsController.currentPositions(),
                ) ?: return@post
                logcat(LogPriority.WARN) {
                    "EPUB continuous resource load failed chapterId=$chapterId resourceIndex=$resourceIndex; " +
                        "falling back to native navigation"
                }
                goTo(locator)
            }
        }
    }

    @Suppress("unused")
    private inner class EpubImageJavascriptBridge(
        private val ownerResource: Link,
    ) {
        @JavascriptInterface
        fun onImageInteraction(
            action: String,
            resourceIndex: Int,
            currentSource: String,
            rawSource: String,
            altText: String,
            title: String,
        ) {
            view?.post {
                if (!isAdded || view == null || (currentSource.isBlank() && rawSource.isBlank())) return@post
                val publication = sessionRepository.get(chapterId)?.publication ?: return@post
                val resolvedIndex = resourceIndex.takeIf { it in publication.readingOrder.indices }
                    ?: publication.readingOrder.indexOfFirst { link ->
                        link.href.toString().sameEpubResource(ownerResource.href.toString())
                    }
                val documentHref = publication.readingOrder.getOrNull(resolvedIndex)?.href?.toString()
                    ?: ownerResource.href.toString()
                val interaction = when (action) {
                    "preview" -> EpubImageInteraction.PREVIEW
                    "actions" -> EpubImageInteraction.ACTIONS
                    else -> return@post
                }
                if (interaction == EpubImageInteraction.ACTIONS) {
                    view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                pageTransitionController?.cancel()
                host?.onImageInteraction(
                    reference = EpubImageReference(
                        documentHref = documentHref,
                        resourceIndex = resolvedIndex,
                        currentSource = currentSource,
                        rawSource = rawSource,
                        altText = altText.takeIf(String::isNotBlank),
                        title = title.takeIf(String::isNotBlank),
                    ),
                    interaction = interaction,
                )
            }
        }
    }

    @Suppress("unused")
    private inner class EpubFontJavascriptBridge {
        @JavascriptInterface
        fun getLength(faceKey: String): Long {
            if (faceKey !in fontPreparation.faceKeys) return -1L
            return fontManager.fontLength(faceKey)
        }

        @JavascriptInterface
        fun getChunk(faceKey: String, chunkIndex: Int): String? {
            if (faceKey !in fontPreparation.faceKeys) return null
            return fontManager.fontChunk(faceKey, chunkIndex)
        }
    }

    private fun createContinuousScrollLocator(
        link: Link,
        progression: Double,
        positions: List<Locator>,
    ): Locator? {
        val publication = sessionRepository.get(chapterId)?.publication ?: return null
        val resourcePositions = positions.filter {
            it.href.toString().sameEpubResource(link.href.toString())
        }
        val scaledPosition = progression * (resourcePositions.size - 1).coerceAtLeast(0)
        val lowerIndex = kotlin.math.floor(scaledPosition).toInt()
        val lowerLocator = resourcePositions.getOrNull(lowerIndex)
        val readingOrderIndex = publication.readingOrder.indexOfFirst {
            it.href.toString().sameEpubResource(link.href.toString())
        }
        val resourceStartProgression = resourcePositions.firstOrNull()?.locations?.totalProgression
        val nextResource = if (readingOrderIndex >= 0) {
            publication.readingOrder.getOrNull(readingOrderIndex + 1)
        } else {
            null
        }
        val resourceEndProgression = nextResource?.let { nextLink ->
            positions.firstOrNull {
                it.href.toString().sameEpubResource(nextLink.href.toString())
            }?.locations?.totalProgression
        } ?: 1.0
        val interpolatedTotalProgression = resourceStartProgression?.let { start ->
            start + (resourceEndProgression - start).coerceAtLeast(0.0) * progression
        }
        val baseLocator = publication.locatorFromLink(link) ?: return null
        return baseLocator.copy(
            title = link.title ?: lowerLocator?.title ?: baseLocator.title,
            mediaType = link.mediaType ?: MediaType.XHTML,
            locations = (lowerLocator?.locations ?: baseLocator.locations).copy(
                progression = progression,
                totalProgression = interpolatedTotalProgression
                    ?: lowerLocator?.locations?.totalProgression
                    ?: baseLocator.locations.totalProgression,
            ),
            text = lowerLocator?.text ?: baseLocator.text,
        )
    }

    /** Mirrors Readium WebViewServer.linkToServedUrl; requests stay intercepted by Publication.get. */
    private fun Link.readiumServedUrl(baseUrl: AbsoluteUrl?): String? {
        val url: Url = url()
        return when (url) {
            is AbsoluteUrl -> url.toString()
            is RelativeUrl -> (baseUrl ?: READIUM_PACKAGE_BASE_URL).resolve(url).toString()
        }
    }

    private fun String.sameEpubResource(other: String): Boolean {
        val first = substringBefore('#').substringBefore('?').trimStart('/')
        val second = other.substringBefore('#').substringBefore('?').trimStart('/')
        return first == second || first.endsWith("/$second") || second.endsWith("/$first")
    }

    private fun navigatorFragment(): EpubNavigatorFragment? {
        if (!isAdded) return null
        return childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
    }

    private fun readyNavigatorFragment(): EpubNavigatorFragment? =
        navigatorFragment()?.takeIf { it.view != null }

    /**
     * 抽取 TTS 文本模型：章节纯文本 + 视口顶部文字在该文本中的字符偏移。
     *
     * 与 [ChapterTextExtractor] 对比：
     * - **快**：直接读已渲染 DOM，毫秒级；ChapterTextExtractor 走"读字节→Jsoup 解析"，
     *   在远程 Komga / 大章节上经常卡 10+ 秒，期间 TTS 听不到声音。
     * - **对齐**：`text` 与高亮 JS tree walker 使用**同一顺序拼接文本节点**，
     *   因此 [koharia.tts.Sentence] 的字符偏移天然与高亮一致，不会漂移。
     * - **精确起播**：`startOffset` 是视口顶部文字在该文本中的偏移，
     *   比 Readium `locations.progression` 更可靠（后者在页面切换前可能仍是 0）。
     *
     * 返回 null 表示 WebView 不可用（初始化中 / 已销毁 / JS 报错），调用方应降级。
     */
    suspend fun extractTtsTextModel(): TtsTextModel? {
        val navigator = readyNavigatorFragment() ?: return null
        val raw = runCatching {
            navigator.evaluateJavascript("(" + epubTtsTextModelBody.trimIndent() + ")();")
        }.getOrNull() ?: return null
        return runCatching {
            val payload = (JSONTokener(raw).nextValue() as? String) ?: raw
            val obj = JSONObject(payload)
            if (!obj.optBoolean("ok", false)) return@runCatching null
            val text = obj.optString("text", "")
            if (text.isBlank()) return@runCatching null
            TtsTextModel(
                text = text,
                startOffset = obj.optInt("startOffset", -1),
                snippet = obj.optString("snippet", "").trim(),
                nodeCount = obj.optInt("nodeCount", -1),
            )
        }.getOrNull()
    }

    /**
     * 把 TTS 当前句高亮在 EPUB 资源 DOM 中。
     * 用 [SentenceRef.startOffset]/[SentenceRef.endOffset]（章节纯文本内字符偏移）定位 text node 范围，
     * 包裹 `<mark class="tts-active-sentence">` 并视需要滚动到视口中心。
     * 失败返回 false，调用方应降级为无高亮，不影响朗读本身。
     */
    suspend fun applySentenceHighlight(
        sentenceRef: TtsProgressNotifier.SentenceRef,
        scrollIntoView: Boolean = true,
    ): Boolean {
        val navigator = readyNavigatorFragment()
        if (navigator == null) {
            logcat(LogPriority.WARN) {
                "[EpubReaderFragment] applySentenceHighlight: navigator unavailable; skip idx=${sentenceRef.index}"
            }
            return false
        }
        val start = sentenceRef.startOffset
        val end = sentenceRef.endOffset
        if (start < 0 || end <= start) {
            logcat(LogPriority.WARN) {
                "[EpubReaderFragment] applySentenceHighlight rejected: bad range [$start,$end)"
            }
            return false
        }
        // 直接传对象字面量：JS 端读 window.__ttsHighlightArgs.start / .end / .scroll，
        // 不能 JSON.stringify（那样变成字符串，args.start === undefined，|0 = 0 → 触发 bad-range）。
        // start / end 是 Int（来自 SentenceRef），scroll 是布尔字面量,无注入风险。
        val argsLiteral = "{start:" + start + ",end:" + end + ",scroll:" +
            (if (scrollIntoView) "true" else "false") + "}"
        val script = "window.__ttsHighlightArgs = " + argsLiteral + "; " +
            "(" + EPUB_APPLY_SENTENCE_HIGHLIGHT_BODY.trimIndent() + ")();"
        val raw = runCatching { navigator.evaluateJavascript(script) }.getOrNull() ?: run {
            logcat(LogPriority.WARN) {
                "[EpubReaderFragment] applySentenceHighlight evaluateJavascript returned null"
            }
            return false
        }
        val ok = runCatching {
            val payload = (JSONTokener(raw).nextValue() as? String) ?: raw
            JSONObject(payload).optBoolean("ok", false)
        }.onFailure {
            logcat(LogPriority.WARN, it) {
                "[EpubReaderFragment] applySentenceHighlight parse error raw=$raw"
            }
        }.getOrDefault(false)
        if (!ok) {
            // raw 是 JSON 字符串（如 "{\"ok\":false,\"reason\":\"range-not-found\"}"），
            // WebView evaluateJavascript 会再包裹一层 JSON 字符串。先 unwrap 再取 reason。
            val reason = runCatching {
                val unwrapped = (JSONTokener(raw).nextValue() as? String) ?: raw
                JSONObject(unwrapped).optString("reason", "no-reason-field")
            }.getOrElse { "parse-error: ${it.message}" }
            logcat(LogPriority.WARN) {
                "[EpubReaderFragment] applySentenceHighlight FAILED reason=$reason range=[$start,$end) raw=$raw"
            }
        } else {
            val info = runCatching {
                val unwrapped = (JSONTokener(raw).nextValue() as? String) ?: raw
                val o = JSONObject(unwrapped)
                "marks=${o.optInt("marks", -1)} markLen=${o.optInt("markLen", -1)} " +
                    "walkerTotal=${o.optInt("walkerTotal", -1)} dom='${o.optString("snippet", "")}' " +
                    "scroll=${o.optJSONObject("scroll")}"
            }.getOrDefault("")
            logcat(LogPriority.INFO) {
                "[EpubReaderFragment] applySentenceHighlight OK idx=${sentenceRef.index} " +
                    "range=[$start,$end) $info"
            }
        }
        return ok
    }

    /**
     * 清除当前 TTS 句子的高亮（删除所有 `mark.tts-active-sentence` 并合并文本节点）。
     * 失败返回 false（不影响主流程）。
     */
    suspend fun clearSentenceHighlight(): Boolean {
        val navigator = readyNavigatorFragment() ?: return false
        val raw = runCatching {
            navigator.evaluateJavascript("(" + EPUB_CLEAR_SENTENCE_HIGHLIGHT_BODY.trimIndent() + ")();")
        }.getOrNull() ?: return false
        val ok = runCatching {
            val payload = (JSONTokener(raw).nextValue() as? String) ?: raw
            JSONObject(payload).optBoolean("ok", false)
        }.getOrDefault(false)
        if (!ok) {
            // raw 是 JSON 包裹字符串，先 unwrap 再解析 reason
            val reason = runCatching {
                val unwrapped = (JSONTokener(raw).nextValue() as? String) ?: raw
                JSONObject(unwrapped).optString("reason", "no-reason-field")
            }.getOrElse { "parse-error: ${it.message}" }
            logcat(LogPriority.WARN) {
                "[EpubReaderFragment] clearSentenceHighlight FAILED reason=$reason raw=$raw"
            }
        }
        return ok
    }
    private fun paginationScannerFragment(): EpubPaginationScannerFragment? {
        if (!isAdded) return null
        return childFragmentManager.findFragmentByTag(PAGINATION_SCANNER_TAG) as? EpubPaginationScannerFragment
    }

    /**
     * 通过 JS 构建 TTS 文本模型，返回 `{ok, text, startOffset, snippet, nodeCount}`。
     *
     * - `text`：遍历文本节点（跳过 script/style/nav/[hidden]/display:none 等），
     *   按顺序拼接原始 `nodeValue`，**不加任何分隔符**。
     * - `startOffset`：从视口顶部逐行扫描，第一个命中文字的位置换算为该文本中的字符偏移。
     * - `snippet`：自 `startOffset` 起 80 字符（仅日志 / 降级锚用）。
     *
     * ⚠️ 这里的 `isSkippableEl` 与 [EPUB_APPLY_SENTENCE_HIGHLIGHT_BODY] 内的同名函数是
     * 两份拷贝。修改过滤规则时必须同步更新两处，否则 `text` 偏移与高亮 walker 不再对齐。
     */
    private val epubTtsTextModelBody =
        """
        function() {
            try {
                var body = document.body;
                if (!body) return JSON.stringify({ ok: false, reason: 'no-body' });

                function isSkippableEl(el) {
                    if (!el || el.nodeType !== 1) return false;
                    var tag = el.tagName ? el.tagName.toUpperCase() : '';
                    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return true;
                    if (el.hasAttribute && el.hasAttribute('hidden')) return true;
                    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return true;
                    var epubType = el.getAttribute && el.getAttribute('epub:type');
                    if (epubType === 'toc' || epubType === 'landmarks' || epubType === 'page-list') return true;
                    if (tag === 'NAV') return true;
                    try {
                        var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
                        if (cs) {
                            if (cs.display === 'none') return true;
                            if (cs.visibility === 'hidden') return true;
                        }
                    } catch (e) {}
                    return false;
                }

                function makeTextWalker() {
                    var root = body;
                    return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                        acceptNode: function(node) {
                            var p = node.parentNode;
                            while (p && p !== root) {
                                if (isSkippableEl(p)) return NodeFilter.FILTER_REJECT;
                                p = p.parentNode;
                            }
                            return NodeFilter.FILTER_ACCEPT;
                        }
                    });
                }

                var walker = makeTextWalker();
                var nodeStart = new Map();
                var pieces = [];
                var position = 0;
                var nodeCount = 0;
                var node;
                while ((node = walker.nextNode())) {
                    var value = String(node.nodeValue || '');
                    nodeStart.set(node, position);
                    pieces.push(value);
                    position += value.length;
                    nodeCount += 1;
                }
                var text = pieces.join('');

                var w = window.innerWidth, h = window.innerHeight;
                var xs = [Math.max(8, Math.min(40, Math.floor(w * 0.08))), Math.floor(w / 2)];
                var startOffset = -1;
                var snippet = '';
                for (var y = 4; y < h && startOffset < 0; y += 6) {
                    for (var i = 0; i < xs.length; i++) {
                        var range = null;
                        try { range = document.caretRangeFromPoint(xs[i], y); } catch (e) { range = null; }
                        if (!range) continue;
                        var container = range.startContainer;
                        if (!container || container.nodeType !== 3) continue;
                        if (!nodeStart.has(container)) continue;
                        var within = range.startOffset | 0;
                        var len = String(container.nodeValue || '').length;
                        if (within > len) within = len;
                        startOffset = nodeStart.get(container) + within;
                        snippet = text.slice(startOffset, startOffset + 80);
                        break;
                    }
                }

                return JSON.stringify({
                    ok: true,
                    text: text,
                    startOffset: startOffset,
                    snippet: snippet,
                    nodeCount: nodeCount
                });
            } catch (e) {
                return JSON.stringify({ ok: false, reason: String(e) });
            }
        }
        """.trimIndent()
    companion object {
        private const val ARG_CHAPTER_ID = "chapter_id"
        private const val ARG_SOURCE_ID = "source_id"
        private const val NAVIGATOR_TAG = "epub_navigator"
        private const val PAGINATION_SCANNER_TAG = "epub_pagination_scanner"
        private const val PARAGRAPH_INDENT_DEBUG_DELAY_MS = 600L
        private const val CONTINUOUS_SCROLL_BRIDGE_NAME = "KohariaContinuousScroll"
        private const val EPUB_FONT_BRIDGE_NAME = "KohariaEpubFont"
        private const val CONTINUOUS_SCROLL_INSTALL_DELAY_MS = 180L
        private const val IMAGE_INTERACTION_INSTALL_DELAY_MS = 80L
        private const val IMAGE_COLOR_POLICY_DRAW_TIMEOUT_MS = 250L
        private const val FONT_PREPARATION_DRAW_TIMEOUT_MS = 8_000L
        private const val FONT_PREPARATION_POLL_MS = 40L
        private const val FONT_BACKGROUND_WAIT_TIMEOUT_MS = 6_000L
        private const val FONT_BACKGROUND_POLL_MS = 75L
        private val READIUM_PACKAGE_BASE_URL = AbsoluteUrl("https://readium_package/")!!

        /** TTS 当前句高亮 JS 函数体（IIFE）。读取 window.__ttsHighlightArgs={start,end,scroll}。 */
        private val EPUB_APPLY_SENTENCE_HIGHLIGHT_BODY =
            """
            function() {
                var args = window.__ttsHighlightArgs;
                if (!args) return JSON.stringify({ ok: false, reason: 'no-args' });
                var start = args.start | 0;
                var end = args.end | 0;
                var scroll = !!args.scroll;
                if (start < 0 || end <= start) return JSON.stringify({ ok: false, reason: 'bad-range' });

                function clearExistingMarks() {
                    var marks = document.querySelectorAll('mark.tts-active-sentence');
                    for (var i = 0; i < marks.length; i++) {
                        var m = marks[i];
                        while (m.firstChild) m.parentNode.insertBefore(m.firstChild, m);
                        m.parentNode.removeChild(m);
                    }
                    document.body.normalize();
                }

                function isSkippableEl(el) {
                    if (!el || el.nodeType !== 1) return false;
                    var tag = el.tagName ? el.tagName.toUpperCase() : '';
                    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return true;
                    // [hidden] 属性 / aria-hidden — 与 ChapterTextExtractor.NON_RENDERED_SELECTOR 对齐
                    if (el.hasAttribute && el.hasAttribute('hidden')) return true;
                    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return true;
                    // 已知 Readium 不渲染的导航元素
                    var epubType = el.getAttribute && el.getAttribute('epub:type');
                    if (epubType === 'toc' || epubType === 'landmarks' || epubType === 'page-list') return true;
                    if (tag === 'NAV') return true;
                    // 计算样式：display:none / visibility:hidden 同样跳过
                    // 拿不到 computedStyle 时（head 等）忽略，不影响正文
                    try {
                        var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
                        if (cs) {
                            if (cs.display === 'none') return true;
                            if (cs.visibility === 'hidden') return true;
                        }
                    } catch (e) {}
                    return false;
                }

                function makeTextWalker() {
                    var root = document.body;
                    return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                        acceptNode: function(node) {
                            var p = node.parentNode;
                            while (p && p !== root) {
                                if (isSkippableEl(p)) return NodeFilter.FILTER_REJECT;
                                p = p.parentNode;
                            }
                            return NodeFilter.FILTER_ACCEPT;
                        }
                    });
                }

                function findRangeNodes(startOff, endOff) {
                    var walker = makeTextWalker();
                    var offset = 0;
                    var startNode = null, startNodeOffset = 0;
                    var endNode = null, endNodeOffset = 0;
                    var node;
                    while ((node = walker.nextNode())) {
                        var text = String(node.nodeValue || '');
                        var ns = offset;
                        var nend = offset + text.length;
                        if (startNode === null && startOff >= ns && startOff <= nend) {
                            startNode = node;
                            startNodeOffset = startOff - ns;
                        }
                        if (endNode === null && endOff >= ns && endOff <= nend) {
                            endNode = node;
                            endNodeOffset = endOff - ns;
                        }
                        offset = nend;
                    }
                    return {
                        startNode: startNode,
                        startNodeOffset: startNodeOffset,
                        endNode: endNode,
                        endNodeOffset: endNodeOffset,
                        total: offset
                    };
                }

                function styleMark(mark) {
                    // 不依赖外部 CSS —— Readium 的分页样式可能覆盖 mark 的默认样式。
                    // 直接内联样式保证任何阅读主题/背景色下都可见。
                    mark.style.backgroundColor = 'rgba(255, 200, 0, 0.45)';
                    mark.style.borderRadius = '3px';
                    mark.style.boxShadow = '0 0 0 1px rgba(255, 160, 0, 0.6)';
                    return mark;
                }

                function wrapTextNodeWithMark(node) {
                    var mark = styleMark(document.createElement('mark'));
                    mark.className = 'tts-active-sentence';
                    if (node.parentNode) {
                        node.parentNode.insertBefore(mark, node);
                        mark.appendChild(node);
                    }
                    return mark;
                }

                function scrollMarkIntoView(mark) {
                    if (!scroll || !mark || !mark.getBoundingClientRect) return null;
                    var scroller = document.scrollingElement || document.documentElement;
                    if (!scroller) return null;
                    var vw = Math.max(1, window.innerWidth || scroller.clientWidth || 1);
                    var vh = Math.max(1, window.innerHeight || scroller.clientHeight || 1);
                    var rect = mark.getBoundingClientRect();
                    var hRange = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
                    var vRange = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
                    var info = {
                        vw: vw, vh: vh,
                        hRange: Math.round(hRange), vRange: Math.round(vRange),
                        fromX: Math.round(scroller.scrollLeft), fromY: Math.round(scroller.scrollTop),
                        rl: Math.round(rect.left), rt: Math.round(rect.top),
                        rw: Math.round(rect.width), rh: Math.round(rect.height)
                    };
                    // 不能用 scrollIntoView：
                    //  - 分页（多栏横向）下它按 inline:'nearest' 只滚"刚好露出"的最小距离，
                    //    会停在一栏中间 → 页面看起来"翻不到位"；
                    //  - block:'center' 又把文字纵向推上去，分页下导致最后一行被切。
                    // Readium 分页的翻页本质就是 WebView 的 scrollX，这里直接按整栏对齐。
                    if (hRange > 1) {
                        var dir = 'ltr';
                        try { dir = String(window.getComputedStyle(scroller).direction || 'ltr'); } catch (e) {}
                        // 用"相对当前视口的栏偏移"而非绝对栏号：RTL 下 scrollLeft 为负值也能成立
                        var anchor = dir === 'rtl' ? rect.right : rect.left;
                        var delta = dir === 'rtl' ? (Math.ceil(anchor / vw) - 1) : Math.floor(anchor / vw);
                        // 一次最多翻一页（防被强行拽回），且不回翻——
                        // 当句子起点落在上一栏时回翻一页、下一句起点又落在后一栏，
                        // 会出现"回翻 → 再前翻"的抖动；直接钳为 0 只前进。
                        if (delta > 1) delta = 1;
                        if (delta < 0) delta = 0;
                        if (delta !== 0) scroller.scrollLeft = scroller.scrollLeft + delta * vw;
                        // 归零纵向残留，否则分页下底部会一直缺一行
                        if (vRange > 1 && scroller.scrollTop !== 0) scroller.scrollTop = 0;
                        info.mode = 'paged';
                        info.dir = dir;
                        info.delta = delta;
                    } else if (vRange > 1) {
                        // 连续滚动：把整句放到视口垂直中心
                        var target = scroller.scrollTop + rect.top - (vh - rect.height) / 2;
                        if (target < 0) target = 0;
                        if (target > vRange) target = vRange;
                        scroller.scrollTop = target;
                        info.mode = 'scroll';
                    } else {
                        info.mode = 'static';
                    }
                    info.toX = Math.round(scroller.scrollLeft);
                    info.toY = Math.round(scroller.scrollTop);
                    return info;
                }

                // 1) 先清掉之前的 mark + normalize
                clearExistingMarks();

                // 2) 找到 start/end text node
                var r = findRangeNodes(start, end);
                if (!r.startNode || !r.endNode) {
                    return JSON.stringify({ ok: false, reason: 'range-not-found' });
                }

                // 3) 构造覆盖完整文本节点的 range。
                var range = document.createRange();
                if (r.startNode === r.endNode) {
                    // 特判：整句落在**同一个文本节点**内。
                    // 之前的实现先 split startNode 再 split endNode，但 split 后
                    // r.startNode 已经指向新节点，r.endNode 仍指向旧节点 → range 反转 →
                    // surroundContents 产出空 mark（用户看到一条竖线光标）。
                    // 正确做法：先按 a 切出后半段 [a,end)，再在这段上按 (b-a) 切出 [a,b)。
                    var node = r.startNode;
                    var a = r.startNodeOffset;
                    var b = r.endNodeOffset;
                    if (a > 0) {
                        node = node.splitText(a);
                        b = b - a;
                    }
                    if (b < node.nodeValue.length) {
                        node.splitText(b);
                    }
                    range.setStart(node, 0);
                    range.setEnd(node, Math.min(b, node.nodeValue.length));
                } else {
                    // 跨文本节点：startNode 只留 [startOffset, ...)，
                    // endNode 只留 [0, endNodeOffset)。
                    if (r.startNodeOffset > 0 && r.startNodeOffset < r.startNode.nodeValue.length) {
                        r.startNode = r.startNode.splitText(r.startNodeOffset);
                    }
                    if (r.endNodeOffset > 0 && r.endNodeOffset < r.endNode.nodeValue.length) {
                        r.endNode.splitText(r.endNodeOffset);
                    }
                    range.setStart(r.startNode, 0);
                    range.setEnd(r.endNode, r.endNode.nodeValue.length);
                }

                var marks = [];
                var useSurround = (function() {
                    try {
                        var m = styleMark(document.createElement('mark'));
                        m.className = 'tts-active-sentence';
                        range.surroundContents(m);
                        marks.push(m);
                        return true;
                    } catch (e) {
                        return false;
                    }
                })();

                if (!useSurround) {
                    // 跨元素边界：surroundContents 失败。逐个 text node 包 mark。
                    // comparePoint 返回 -1 (在 range 之前)、0 (在 range 内)、1 (在 range 之后)
                    // n 与 range 相交的条件：n 起点不晚于 range 末点，且 n 末点不早于 range 起点
                    var inner = document.createTreeWalker(
                        range.commonAncestorContainer,
                        NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(n) {
                                var len = (n.nodeValue || '').length;
                                try {
                                    var cmpStart = range.comparePoint(n, 0);
                                    if (cmpStart === 1) return NodeFilter.FILTER_REJECT;
                                    var cmpEnd = range.comparePoint(n, len);
                                    if (cmpEnd === -1) return NodeFilter.FILTER_REJECT;
                                    return NodeFilter.FILTER_ACCEPT;
                                } catch (e) {
                                    return NodeFilter.FILTER_SKIP;
                                }
                            }
                        }
                    );
                    var collected = [];
                    var tn;
                    while ((tn = inner.nextNode())) collected.push(tn);
                    for (var i = 0; i < collected.length; i++) {
                        var t = collected[i];
                        if (!t.parentNode || t.parentNode.nodeName === 'MARK') continue;
                        marks.push(wrapTextNodeWithMark(t));
                    }
                }

                if (marks.length === 0) {
                    return JSON.stringify({ ok: false, reason: 'no-mark-created' });
                }
                var scrollInfo = scrollMarkIntoView(marks[0]);
                // 诊断信息：命中的 DOM 文本 + 长度 + walker 全文字符总数 + 滚动结果。
                // walkerTotal 应与文本模型 text.length 相等；不等说明两处文本模型不一致。
                var markText = String(marks[0].textContent || '');
                return JSON.stringify({
                    ok: true,
                    marks: marks.length,
                    markLen: markText.length,
                    walkerTotal: r.total,
                    snippet: markText.slice(0, 60),
                    scroll: scrollInfo
                });
            }
            """

        /** 清除当前 TTS 句子的高亮（删除所有 mark.tts-active-sentence 并合并文本节点）。 */
        private val EPUB_CLEAR_SENTENCE_HIGHLIGHT_BODY =
            """
            function() {
                try {
                    var marks = document.querySelectorAll('mark.tts-active-sentence');
                    for (var i = 0; i < marks.length; i++) {
                        var m = marks[i];
                        while (m.firstChild) m.parentNode.insertBefore(m.firstChild, m);
                        m.parentNode.removeChild(m);
                    }
                    document.body.normalize();
                    return JSON.stringify({ ok: true });
                } catch (e) {
                    return JSON.stringify({ ok: false, reason: String(e) });
                }
            }
            """
        private val PARAGRAPH_INDENT_DEBUG_SCRIPT =
            """
            (function() {
                var root = document.documentElement;
                var rootStyle = window.getComputedStyle(root);
                function describe(element) {
                    var style = window.getComputedStyle(element);
                    return {
                        tag: element.tagName,
                        className: String(element.className || ''),
                        inlineStyle: element.getAttribute('style') || '',
                        textIndent: style.textIndent,
                        textAlign: style.textAlign,
                        direction: style.direction,
                        alignmentTarget: element.hasAttribute('$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE'),
                        rightIndentSpacer: element.hasAttribute('$EPUB_RIGHT_INDENT_SPACER_ATTRIBUTE'),
                        display: style.display,
                        firstChildTag: element.firstElementChild ? element.firstElementChild.tagName : '',
                        text: String(element.textContent || '').trim().slice(0, 24)
                    };
                }
                var paragraphs = Array.from(document.querySelectorAll('p'));
                return JSON.stringify({
                    href: location.href,
                    rootInlineStyle: root.getAttribute('style') || '',
                    advancedSettings: rootStyle.getPropertyValue('--USER__advancedSettings').trim(),
                    paragraphIndentVariable: rootStyle.getPropertyValue('--USER__paraIndent').trim(),
                    paragraphCount: paragraphs.length,
                    chapterBreakCount: document.querySelectorAll('[$EPUB_CHAPTER_BREAK_TARGET_ATTRIBUTE]').length,
                    paragraphs: paragraphs.slice(0, 8).map(describe),
                    bodyBlocks: Array.from(document.body ? document.body.children : []).slice(0, 8).map(describe)
                });
            })()
            """.trimIndent()

        fun createArguments(chapterId: Long, sourceId: Long): Bundle {
            return Bundle().apply {
                putLong(ARG_CHAPTER_ID, chapterId)
                putLong(ARG_SOURCE_ID, sourceId)
            }
        }

        fun newInstance(chapterId: Long, sourceId: Long): EpubReaderFragment {
            return EpubReaderFragment().apply { arguments = createArguments(chapterId, sourceId) }
        }
    }

    private data class ImageColorPolicyDrawWait(
        val policyKey: String,
        val expiresAt: Long,
    )
}
