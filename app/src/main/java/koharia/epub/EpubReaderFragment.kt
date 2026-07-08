package koharia.epub

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import koharia.epub.session.EpubReaderSessionRepository
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
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
        fun onCenterTap()

        fun onLocatorChanged(locator: Locator)

        fun onExternalLinkActivated(url: AbsoluteUrl)
    }

    private val sessionRepository: EpubReaderSessionRepository = Injekt.get()
    private val chapterId: Long
        get() = requireArguments().getLong(ARG_CHAPTER_ID)

    private var host: Host? = null
    private var containerId: Int = View.NO_ID

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
            readingOrder = session.publication.readingOrder,
            listener = navigatorListener,
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
        if (childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) == null && sessionRepository.get(chapterId) != null) {
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
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    fun goTo(link: Link): Boolean {
        val navigator = navigatorFragment() ?: return false
        return navigator.go(link)
    }

    private fun observeNavigator() {
        val navigator = navigatorFragment() ?: return
        logcat(LogPriority.DEBUG) {
            "EPUB fragment observe navigator chapterId=$chapterId"
        }
        navigator.addInputListener(
            DirectionalNavigationAdapter(
                navigator = navigator,
                handleTapsWhileScrolling = true,
            ),
        )
        navigator.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    val width = navigator.publicationView.width.toFloat()
                    val edgeSize = width * 0.3f
                    if (event.point.x > edgeSize && event.point.x < width - edgeSize) {
                        host?.onCenterTap()
                        return true
                    }
                    return false
                }
            },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    host?.onLocatorChanged(locator)
                }
            }
        }
    }

    private fun navigatorFragment(): EpubNavigatorFragment? =
        childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment

    companion object {
        private const val ARG_CHAPTER_ID = "chapter_id"
        private const val NAVIGATOR_TAG = "epub_navigator"

        fun newInstance(chapterId: Long): EpubReaderFragment {
            return EpubReaderFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHAPTER_ID, chapterId)
                }
            }
        }
    }
}
