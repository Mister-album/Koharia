package koharia.epub

import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.tachiyomi.R
import koharia.epub.locator.toNavigatorLocator
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubPreferencesBridge
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

        fun onNavigatorReady()

        fun onSessionMissing(chapterId: Long)
    }

    private val sessionRepository: EpubReaderSessionRepository = Injekt.get()
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
    private var paragraphIndentDebugGeneration = 0L
    private var paragraphIndentOverrideEnabled = false

    private val navigatorListener = object : EpubNavigatorFragment.Listener {
        override fun onExternalLinkActivated(url: AbsoluteUrl) {
            host?.onExternalLinkActivated(url)
        }

        override fun shouldFollowInternalLink(
            link: Link,
            context: HyperlinkNavigator.LinkContext?,
        ): Boolean {
            return true
        }
    }

    @Suppress("DEPRECATION")
    private val paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
            host?.onPageChanged(pageIndex, totalPages, locator)
            applyParagraphIndentOverride()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val session = sessionRepository.get(chapterId)
        logcat(LogPriority.DEBUG) {
            "EPUB fragment onCreate chapterId=$chapterId hasSession=${session != null}"
        }
        childFragmentManager.fragmentFactory = session?.navigatorFactory?.createFragmentFactory(
            initialLocator = session.initialLocator,
            initialPreferences = epubPreferencesBridge.toReadiumPreferences(epubLayoutPreferences),
            listener = navigatorListener,
            paginationListener = paginationListener,
            configuration = EpubNavigatorFragment.Configuration(
                shouldApplyInsetsPadding = false,
            ),
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
        if (childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) == null) {
            logcat(LogPriority.DEBUG) {
                "EPUB fragment create navigator chapterId=$chapterId containerId=$containerId"
            }
            val navigatorFragment = childFragmentManager.fragmentFactory.instantiate(
                requireContext().classLoader,
                EpubNavigatorFragment::class.java.name,
            )
            childFragmentManager.commitNow {
                setReorderingAllowed(true)
                replace(containerId, navigatorFragment, NAVIGATOR_TAG)
            }
        }
        observeNavigator()
        if (navigatorFragment() != null) {
            host?.onNavigatorReady()
        }
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

    fun goTo(link: Link): Boolean {
        val navigator = navigatorFragment() ?: return false
        return navigator.go(link)
    }

    fun goTo(locator: Locator): Boolean {
        val navigator = navigatorFragment() ?: return false
        val publication = sessionRepository.get(chapterId)?.publication ?: return false
        return navigator.go(publication.toNavigatorLocator(locator))
    }

    fun goForward(): Boolean {
        val navigator = navigatorFragment() ?: return false
        return navigator.goForward()
    }

    fun goBackward(): Boolean {
        val navigator = navigatorFragment() ?: return false
        return navigator.goBackward()
    }

    fun submitPreferences(preferences: EpubPreferences) {
        paragraphIndentOverrideEnabled = preferences.publisherStyles == false
        navigatorFragment()?.submitPreferences(preferences)
        applyParagraphIndentOverride()
        logcat(LogPriority.DEBUG) {
            "EPUB paragraph indent submitted chapterId=$chapterId " +
                "publisherStyles=${preferences.publisherStyles} " +
                "paragraphIndent=${preferences.paragraphIndent} " +
                "paragraphSpacing=${preferences.paragraphSpacing} lineHeight=${preferences.lineHeight}"
        }
        logComputedParagraphIndent()
    }

    private fun applyParagraphIndentOverride() {
        if (!isAdded || view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val navigator = navigatorFragment() ?: return@launch
            navigator.evaluateJavascript(
                if (paragraphIndentOverrideEnabled) {
                    APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT
                } else {
                    REMOVE_EPUB_PARAGRAPH_INDENT_SCRIPT
                },
            )
        }
    }

    private fun logComputedParagraphIndent() {
        if (!isAdded || view == null) return
        val debugGeneration = ++paragraphIndentDebugGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PARAGRAPH_INDENT_DEBUG_DELAY_MS)
            if (debugGeneration != paragraphIndentDebugGeneration || !isAdded || view == null) return@launch
            val result = navigatorFragment()?.evaluateJavascript(PARAGRAPH_INDENT_DEBUG_SCRIPT) ?: return@launch
            logcat(LogPriority.DEBUG) {
                "EPUB paragraph indent computed chapterId=$chapterId result=$result"
            }
        }
    }

    internal fun startPagination(request: EpubPaginationRequest) {
        val existing = paginationScannerFragment()
        if (!request.shouldScan) {
            if (existing != null) {
                childFragmentManager.commitNow { remove(existing) }
            }
            return
        }
        childFragmentManager.commitNow {
            setReorderingAllowed(true)
            replace(
                scannerContainerId,
                EpubPaginationScannerFragment.newInstance(chapterId, sourceId, request),
                PAGINATION_SCANNER_TAG,
            )
        }
    }

    fun stopPagination() {
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

    private fun observeNavigator() {
        val navigator = navigatorFragment() ?: return
        logcat(LogPriority.DEBUG) {
            "EPUB fragment observe navigator chapterId=$chapterId"
        }
        navigator.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    val width = navigator.publicationView.width.toFloat().takeIf { it > 0f } ?: return false
                    val height = navigator.publicationView.height.toFloat().takeIf { it > 0f } ?: return false
                    return host?.onTap(
                        positionX = event.point.x / width,
                        positionY = event.point.y / height,
                    ) ?: false
                }
            },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    host?.onLocatorChanged(locator)
                    applyParagraphIndentOverride()
                }
            }
        }
    }

    private fun navigatorFragment(): EpubNavigatorFragment? =
        childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment

    private fun paginationScannerFragment(): EpubPaginationScannerFragment? =
        childFragmentManager.findFragmentByTag(PAGINATION_SCANNER_TAG) as? EpubPaginationScannerFragment

    companion object {
        private const val ARG_CHAPTER_ID = "chapter_id"
        private const val ARG_SOURCE_ID = "source_id"
        private const val NAVIGATOR_TAG = "epub_navigator"
        private const val PAGINATION_SCANNER_TAG = "epub_pagination_scanner"
        private const val PARAGRAPH_INDENT_DEBUG_DELAY_MS = 600L
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
}
