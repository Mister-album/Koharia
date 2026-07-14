package koharia.epub

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubPreferencesBridge
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalReadiumApi::class)
internal class EpubPaginationScannerFragment : Fragment() {

    private val sessionRepository: EpubReaderSessionRepository = Injekt.get()
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get()
    private val epubPreferencesBridge = EpubPreferencesBridge()
    private val chapterId: Long
        get() = requireArguments().getLong(ARG_CHAPTER_ID)
    private val sourceId: Long
        get() = requireArguments().getLong(ARG_SOURCE_ID, -1L)
    private val generation: Long
        get() = requireArguments().getLong(ARG_GENERATION)
    private val epubLayoutPreferences by lazy {
        (activity as? EpubReaderActivity)?.sessionEpubLayoutPreferences() ?: if (sourceId > 0L) {
            scopedPreferenceStoreFactory.epubLayoutPreferences(sourceId)
        } else {
            Injekt.get<EpubLayoutPreferences>()
        }
    }
    private var containerId: Int = View.NO_ID
    private var scanIndex = 0
    private var scanStarted = false
    private var awaitingMeasuredCallback = false
    private var readinessJob: Job? = null
    private val beginScanRunnable = Runnable {
        if (!isAdded || view == null) return@Runnable
        beginScan()
    }
    private val pageCounts by lazy {
        requireArguments().getString(ARG_INITIAL_PAGE_COUNTS).orEmpty().toPageCounts().toMutableMap()
    }

    @Suppress("DEPRECATION")
    private val paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
            if (!isAdded || view == null) return
            if (!scanStarted) return
            val expectedLink = readingOrder().getOrNull(scanIndex) ?: return
            if (!expectedLink.href.toString().isSameResourceHref(locator.href.toString())) return

            if (awaitingMeasuredCallback) {
                awaitingMeasuredCallback = false
                recordPageCount(totalPages, locator)
            } else if (readinessJob == null) {
                readinessJob = viewLifecycleOwner.lifecycleScope.launch {
                    if (!epubLayoutPreferences.publisherStyles.get()) {
                        navigatorFragment()?.evaluateJavascript(APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT)
                    }
                    val ready = awaitStableLayout()
                    readinessJob = null
                    if (!ready) {
                        reportProgress(isComplete = false)
                        scanStarted = false
                        return@launch
                    }
                    awaitingMeasuredCallback = true
                    navigatorFragment()?.go(expectedLink)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val session = sessionRepository.get(chapterId)
        val firstMissingLink = session?.publication?.readingOrder?.firstOrNull { link ->
            pageCounts.keys.none { cachedHref -> cachedHref.isSameResourceHref(link.href.toString()) }
        } ?: session?.publication?.readingOrder?.firstOrNull()
        val firstLocator = session?.let { readerSession ->
            firstMissingLink?.let(readerSession.publication::locatorFromLink)
        }
        childFragmentManager.fragmentFactory = session?.navigatorFactory?.createFragmentFactory(
            initialLocator = firstLocator,
            initialPreferences = epubPreferencesBridge.toReadiumPreferences(epubLayoutPreferences),
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
        return FragmentContainerView(requireContext()).apply {
            id = View.generateViewId()
            containerId = id
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) == null) {
            val navigator = childFragmentManager.fragmentFactory.instantiate(
                requireContext().classLoader,
                EpubNavigatorFragment::class.java.name,
            )
            childFragmentManager.commitNow {
                setReorderingAllowed(true)
                replace(containerId, navigator, NAVIGATOR_TAG)
            }
        }
        view.post(beginScanRunnable)
    }

    override fun onDestroyView() {
        view?.removeCallbacks(beginScanRunnable)
        readinessJob?.cancel()
        readinessJob = null
        scanStarted = false
        awaitingMeasuredCallback = false
        super.onDestroyView()
    }

    private fun beginScan() {
        scanStarted = true
        advancePastCachedResources()
        val nextLink = readingOrder().getOrNull(scanIndex)
        if (nextLink == null) {
            reportProgress(isComplete = true)
        } else {
            navigatorFragment()?.go(nextLink)
        }
    }

    private suspend fun awaitStableLayout(): Boolean {
        val navigator = navigatorFragment() ?: return false
        val loaded = withTimeoutOrNull(RESOURCE_READY_TIMEOUT_MS) {
            while (true) {
                val result = navigator.evaluateJavascript(RESOURCE_READY_SCRIPT)
                if (result == "true") break
                delay(RESOURCE_READY_POLL_MS)
            }
            true
        } ?: false
        if (loaded) delay(STABLE_LAYOUT_DELAY_MS)
        return loaded
    }

    private fun recordPageCount(totalPages: Int, locator: Locator) {
        if (totalPages <= 0) return
        val expectedLink = readingOrder().getOrNull(scanIndex) ?: return
        val expectedHref = expectedLink.href.toString().resourceKey()
        if (!expectedHref.isSameResourceHref(locator.href.toString())) return

        pageCounts[expectedHref] = totalPages
        reportProgress(isComplete = false)
        scanIndex += 1
        advancePastCachedResources()
        val nextLink = readingOrder().getOrNull(scanIndex)
        if (nextLink != null) {
            navigatorFragment()?.go(nextLink)
        } else {
            reportProgress(isComplete = true)
        }
    }

    private fun advancePastCachedResources() {
        val order = readingOrder()
        while (scanIndex < order.size) {
            val href = order[scanIndex].href.toString()
            if (pageCounts.keys.none { it.isSameResourceHref(href) }) break
            scanIndex += 1
        }
    }

    private fun reportProgress(isComplete: Boolean) {
        (parentFragment as? EpubReaderFragment)?.onBookPaginationCalculated(
            generation = generation,
            pageCounts = pageCounts.toMap(),
            isComplete = isComplete,
        )
    }

    private fun readingOrder() = sessionRepository.get(chapterId)?.publication?.readingOrder.orEmpty()

    private fun navigatorFragment(): EpubNavigatorFragment? {
        if (!isAdded) return null
        return childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
    }

    private fun String.isSameResourceHref(other: String): Boolean {
        val first = resourceKey()
        val second = other.resourceKey()
        return first == second || first.endsWith("/$second") || second.endsWith("/$first")
    }

    private fun String.resourceKey(): String =
        substringBefore('#')
            .substringBefore('?')
            .trimStart('/')

    companion object {
        private const val ARG_CHAPTER_ID = "chapter_id"
        private const val ARG_SOURCE_ID = "source_id"
        private const val ARG_GENERATION = "generation"
        private const val ARG_INITIAL_PAGE_COUNTS = "initial_page_counts"
        private const val NAVIGATOR_TAG = "epub_pagination_navigator"
        private const val RESOURCE_READY_TIMEOUT_MS = 8_000L
        private const val RESOURCE_READY_POLL_MS = 75L
        private const val STABLE_LAYOUT_DELAY_MS = 40L
        private const val RESOURCE_READY_SCRIPT =
            "document.fonts.status === 'loaded' && " +
                "Array.from(document.images).every(function(image) { return image.complete; })"

        fun newInstance(
            chapterId: Long,
            sourceId: Long,
            request: EpubPaginationRequest,
        ): EpubPaginationScannerFragment {
            return EpubPaginationScannerFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHAPTER_ID, chapterId)
                    putLong(ARG_SOURCE_ID, sourceId)
                    putLong(ARG_GENERATION, request.generation)
                    putString(ARG_INITIAL_PAGE_COUNTS, request.initialPageCounts.toPageCountsJson())
                }
            }
        }
    }
}
