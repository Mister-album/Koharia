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
import eu.kanade.tachiyomi.R
import koharia.epub.font.EpubFontId
import koharia.epub.font.EpubFontManager
import koharia.epub.locator.toNavigatorLocator
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubPreferencesBridge
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
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

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : Fragment() {

    interface Host {
        fun onTap(positionX: Float, positionY: Float): Boolean

        fun onLocatorChanged(locator: Locator)

        fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator)

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
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get()
    private val epubPreferencesBridge = EpubPreferencesBridge()
    private val chapterId: Long
        get() = requireArguments().getLong(ARG_CHAPTER_ID)
    private val sourceId: Long
        get() = requireArguments().getLong(ARG_SOURCE_ID, -1L)
    private val epubLayoutPreferences by lazy {
        (activity as? EpubReaderActivity)?.sessionEpubLayoutPreferences() ?: if (sourceId > 0L) {
            scopedPreferenceStoreFactory.epubLayoutPreferences(sourceId)
        } else {
            Injekt.get<EpubLayoutPreferences>()
        }
    }
    private var host: Host? = null
    private var containerId: Int = View.NO_ID
    private var scannerContainerId: Int = View.NO_ID
    private var observedNavigator: EpubNavigatorFragment? = null
    private var navigatorInputListener: InputListener? = null
    private var paragraphIndentDebugGeneration = 0L
    private var paragraphIndentOverrideEnabled = false
    private var readerFontScale = 1f
    private var textAlignmentOverride: EpubLayoutPreferences.TextAlignment? = null
    private var tocHrefs: List<String> = emptyList()
    private var chapterBreaksEnabled = false
    private var continuousScrollInstallJob: Job? = null
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
            currentHost.onFootnoteActivated(link, contentHtml)
            return false
        }
    }

    @Suppress("DEPRECATION")
    private val paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
            host?.onPageChanged(pageIndex, totalPages, locator)
            scheduleImageInteractionsInstall()
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
        return FrameLayout(requireContext()).apply {
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
        viewportView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0 ||
                (width == oldRight - oldLeft && height == oldBottom - oldTop)
            ) {
                return@addOnLayoutChangeListener
            }
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
        clearContinuousScrollState()
        return navigator.go(link)
    }

    fun goTo(locator: Locator): Boolean {
        val navigator = readyNavigatorFragment() ?: return false
        val publication = sessionRepository.get(chapterId)?.publication ?: return false
        clearContinuousScrollState()
        return navigator.go(publication.toNavigatorLocator(locator))
    }

    fun goForward(): Boolean {
        val navigator = readyNavigatorFragment() ?: return false
        navigateContinuousScroll(forward = true)?.let { return it }
        clearContinuousScrollState()
        return navigator.goForward()
    }

    fun goBackward(): Boolean {
        val navigator = readyNavigatorFragment() ?: return false
        navigateContinuousScroll(forward = false)?.let { return it }
        clearContinuousScrollState()
        return navigator.goBackward()
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
        )
        imageColorPolicyScript = """
            (function() {
                $documentScript;
                return ${fontPreparation.script};
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
                val width = navigator.publicationView.width.toFloat().takeIf { it > 0f } ?: return false
                val height = navigator.publicationView.height.toFloat().takeIf { it > 0f } ?: return false
                return host?.onTap(
                    positionX = event.point.x / width,
                    positionY = event.point.y / height,
                ) ?: false
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
                    ),
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
            if (webView.progress < 100) {
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
                        if (fontReady && imageColorPolicyGeneration == generation &&
                            webView.url?.substringBefore('#') == url
                        ) {
                            installedImageColorPolicies[webView] = policyKey
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

    private fun scheduleContinuousScrollInstall(
        navigator: EpubNavigatorFragment?,
        locator: Locator? = navigator?.currentLocator?.value,
    ) {
        continuousScrollInstallJob?.cancel()
        if (navigator == null || locator == null ||
            epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL
        ) {
            return
        }
        continuousScrollInstallJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(CONTINUOUS_SCROLL_INSTALL_DELAY_MS)
            if (!isAdded || view == null || readyNavigatorFragment() !== navigator ||
                epubLayoutPreferences.readingMode.get() != EpubLayoutPreferences.ReadingMode.SCROLL
            ) {
                return@launch
            }
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
            val result = runCatching {
                navigator.evaluateJavascript(
                    buildEpubContinuousScrollInstallScript(
                        resources = resources,
                        currentIndex = currentIndex,
                        initialProgression = locator.locations.progression ?: 0.0,
                        imageInteractionScript = buildEpubImageInteractionInstallScript(
                            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout(),
                            touchSlopCssPx = ViewConfiguration.get(requireContext()).scaledTouchSlop /
                                (requireContext().resources.displayMetrics.density.takeIf { it > 0f } ?: 1f),
                            preserveImageColors = preserveImageColors,
                            parentColorsInverted = parentColorsInverted,
                        ),
                        contentPreparationScript = """
                            (function() {
                                ${buildEpubTypographyPreparationScript(
                            paragraphIndentOverrideEnabled = paragraphIndentOverrideEnabled,
                            textAlignment = textAlignmentOverride,
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
            }.getOrNull()
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
            }
        }
    }

    private fun clearContinuousScrollState() {
        continuousScrollInstallJob?.cancel()
        continuousScrollInstallJob = null
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

    private fun paginationScannerFragment(): EpubPaginationScannerFragment? {
        if (!isAdded) return null
        return childFragmentManager.findFragmentByTag(PAGINATION_SCANNER_TAG) as? EpubPaginationScannerFragment
    }

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
