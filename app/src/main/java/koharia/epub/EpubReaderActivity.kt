package koharia.epub

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.view.setComposeContent
import koharia.epub.model.EpubTocEntry
import koharia.epub.settings.EpubReaderPreferences
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.toUri
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderActivity : BaseActivity(), EpubReaderFragment.Host {

    companion object {
        private const val READER_FRAGMENT_TAG = "epub_reader_fragment"

        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?): Intent {
            return Intent(context, EpubReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val viewModel by viewModels<EpubReaderViewModel>()
    private val epubReaderPreferences = Injekt.get<EpubReaderPreferences>()
    private val readerPreferences = Injekt.get<ReaderPreferences>()
    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        setComposeContent {
            val state by viewModel.state.collectAsState()
            val tocEntries = remember(state.chapterId, state.isReady) { viewModel.tableOfContents() }
            var showToc by rememberSaveable { mutableStateOf(false) }
            val subtitle = remember(state.chapterTitle, state.currentSectionTitle, state.progressionPercent) {
                listOfNotNull(
                    state.chapterTitle,
                    state.currentSectionTitle,
                    state.progressionPercent?.let { "$it%" },
                ).joinToString(" / ").takeIf { it.isNotBlank() }
            }

            Scaffold(
                topBar = {
                    if (state.menuVisible) {
                        AppBar(
                            title = state.mangaTitle,
                            subtitle = subtitle,
                            navigateUp = ::finish,
                            actions = {
                                if (tocEntries.isNotEmpty()) {
                                    IconButton(onClick = { showToc = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.List,
                                            contentDescription = stringResource(MR.strings.epub_reader_toc),
                                        )
                                    }
                                }
                            },
                        )
                    }
                },
            ) { contentPadding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.isReady && state.chapterId > 0) {
                        ReaderFragmentContainer(
                            fragmentManager = supportFragmentManager,
                            chapterId = state.chapterId,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(if (state.menuVisible) contentPadding else PaddingValues(0.dp)),
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
                            )
                        }
                    }
                }

                if (showToc) {
                    TocDialog(
                        entries = tocEntries,
                        onDismissRequest = { showToc = false },
                        onSelect = { entry ->
                            (supportFragmentManager.findFragmentByTag(READER_FRAGMENT_TAG) as? EpubReaderFragment)
                                ?.goTo(entry.link)
                            showToc = false
                        },
                    )
                }
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

        epubReaderPreferences.enableNativeReader.changes()
            .onEach {
                if (!it) finish()
            }
            .launchIn(lifecycleScope)

        setKeepScreenOn(readerPreferences.keepScreenOn.get())
        readerPreferences.keepScreenOn.changes()
            .onEach(::setKeepScreenOn)
            .launchIn(lifecycleScope)

        readerPreferences.fullscreen.changes()
            .onEach { updateSystemBars(viewModel.state.value.menuVisible) }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.menuVisible }
            .distinctUntilChanged()
            .onEach(::updateSystemBars)
            .launchIn(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        updateSystemBars(viewModel.state.value.menuVisible)
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

    override fun onCenterTap() {
        viewModel.showMenus(!viewModel.state.value.menuVisible)
    }

    override fun onLocatorChanged(locator: Locator) {
        viewModel.updateLocator(locator)
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        openInBrowser(url.toUri(), forceDefaultBrowser = false)
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

    @Composable
    private fun ReaderFragmentContainer(
        fragmentManager: FragmentManager,
        chapterId: Long,
        modifier: Modifier = Modifier,
    ) {
        val initialized = remember(chapterId) { mutableStateOf(false) }
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = modifier,
            factory = { context ->
                FragmentContainerView(context).apply {
                    id = android.view.View.generateViewId()
                }
            },
            update = { view ->
                if (!initialized.value) {
                    fragmentManager.commit {
                        setReorderingAllowed(true)
                        replace(view.id, EpubReaderFragment.newInstance(chapterId), READER_FRAGMENT_TAG)
                    }
                    initialized.value = true
                } else {
                    fragmentManager.onContainerAvailable(view)
                }
                applyInsets(view, viewModel.state.value.menuVisible)
            },
        )
    }

    @Composable
    private fun ErrorContent(
        message: String,
        onRetry: () -> Unit,
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
        }
    }

    @Composable
    private fun TocDialog(
        entries: List<EpubTocEntry>,
        onDismissRequest: () -> Unit,
        onSelect: (EpubTocEntry) -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {},
            dismissButton = {},
            title = { Text(text = stringResource(MR.strings.epub_reader_toc)) },
            text = {
                LazyColumn {
                    items(entries) { entry ->
                        Text(
                            text = entry.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(entry) }
                                .padding(start = (entry.depth * 16).dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            },
        )
    }

    private fun FragmentManager.onContainerAvailable(view: FragmentContainerView) {
        val method = FragmentManager::class.java.getDeclaredMethod(
            "onContainerAvailable",
            FragmentContainerView::class.java,
        )
        method.isAccessible = true
        method.invoke(this, view)
    }

    private fun applyInsets(
        view: android.view.View,
        menuVisible: Boolean,
    ) {
        val insets = if (menuVisible || !readerPreferences.fullscreen.get()) {
            ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: Insets.NONE
        } else {
            Insets.NONE
        }
        view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }
}
