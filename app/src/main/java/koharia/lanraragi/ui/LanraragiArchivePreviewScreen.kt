package koharia.lanraragi.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.motion.eInkAnimationSpec
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LanraragiArchivePreviewScreen(private val mangaId: Long, private val sourceId: Long) : Screen() {
    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? LanraragiSource
        if (source == null) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val model = rememberScreenModel { LanraragiArchivePreviewModel(mangaId, source) }
        val state by model.state.collectAsState()
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val loadPreviews = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val gridState = rememberLazyGridState()
        val layoutDirection = LocalLayoutDirection.current
        val headerVisible by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
        val atTop by remember {
            derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 }
        }
        val backgroundAlpha by animateFloatAsState(
            if (atTop) 0f else 1f,
            animationSpec = eInkAnimationSpec(spring()),
            label = "Preview toolbar background",
        )
        val titleAlpha by animateFloatAsState(
            if (headerVisible) 0f else 1f,
            animationSpec = eInkAnimationSpec(spring()),
            label = "Preview toolbar title",
        )
        var cover by remember { mutableStateOf(false) }
        var opening by remember { mutableStateOf(false) }
        fun open(page: Int?) {
            if (opening) return
            val manga = state.manga ?: return
            opening = true
            scope.launch {
                try {
                    val chapter = withContext(Dispatchers.IO) { model.currentChapter() }
                    context.startActivity(model.opener.readerIntent(context, manga, chapter, page))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    snackbar.showSnackbar(context.lanraragiError(error))
                } finally {
                    opening = false
                }
            }
        }
        Scaffold(
            topBar = {
                AppBar(
                    titleContent = { AppBarTitle(state.manga?.title, modifier = Modifier.alpha(titleAlpha)) },
                    backgroundColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                        3.dp,
                    ).copy(alpha = backgroundAlpha),
                    navigateUp = { navigator.pop() },
                    actions = {
                        IconButton(onClick = model::download, enabled = state.chapter != null) {
                            Icon(Icons.Default.Download, stringResource(MR.strings.action_download))
                        }
                        IconButton(onClick = { model.refresh() }, enabled = !state.loading) {
                            Icon(Icons.Default.Refresh, stringResource(MR.strings.lanraragi_refresh))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (state.chapter != null) {
                    val chapter = state.chapter
                    val label = if (chapter != null && !chapter.read && chapter.lastPageRead > 0) {
                        MR.strings.action_resume
                    } else {
                        MR.strings.action_start
                    }
                    val actionLabel = stringResource(label)
                    ExtendedFloatingActionButton(
                        modifier = Modifier.semantics { contentDescription = actionLabel },
                        onClick = { open(null) },
                        icon = { Icon(Icons.Default.PlayArrow, null) },
                        text = { Text(actionLabel) },
                    )
                }
            },
        ) { padding ->
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize().padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                ),
                contentPadding = PaddingValues(bottom = maxOf(96.dp, padding.calculateBottomPadding())),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    state.manga?.let { manga ->
                        Column {
                            MangaInfoBox(
                                isTabletUi = false,
                                appBarPadding = padding.calculateTopPadding(),
                                manga = manga,
                                sourceName = source.name,
                                isStubSource = false,
                                onCoverClick = { cover = true },
                                doSearch = { query, _ ->
                                    navigator.push(LanraragiLibraryScreen(sourceId, query, true))
                                },
                            )
                            ExpandableMangaDescription(
                                description = manga.description,
                                tagsProvider = { manga.genre },
                                notes = manga.notes,
                                onTagSearch = { navigator.push(LanraragiLibraryScreen(sourceId, it, true)) },
                                onCopyTagToClipboard = { context.copyToClipboard(it, it) },
                                onEditNotes = { navigator.push(MangaNotesScreen(manga)) },
                            )
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(MR.strings.lanraragi_page_previews, state.pages.size))
                        if (state.loading) EInkLinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let {
                            Text(context.lanraragiError(it))
                            TextButton(onClick = { model.refresh() }) { Text(stringResource(MR.strings.action_retry)) }
                        }
                    }
                }
                items(state.pages, key = { it.page.index }) { image ->
                    if (loadPreviews) {
                        PreviewTile(image) { open(image.page.index) }
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.7f))
                    }
                }
            }
        }
        if (cover) {
            AlertDialog(
                onDismissRequest = { cover = false },
                confirmButton = {
                    TextButton(onClick = { cover = false }) { Text(stringResource(MR.strings.action_close)) }
                },
                text = { AsyncImage(state.manga, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) },
            )
        }
    }
}

@Composable
private fun PreviewTile(image: LanraragiPreviewImage, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(image.cacheKey) {
        ImageRequest.Builder(context).data(image).memoryCacheKey(image.cacheKey).build()
    }
    val label = stringResource(MR.strings.lanraragi_page_number, image.page.index + 1)
    Column(Modifier.padding(horizontal = 4.dp).clickable(onClick = onClick)) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = label,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
            contentScale = ContentScale.Fit,
            loading = { Box(contentAlignment = Alignment.Center) { EInkCircularProgressIndicator() } },
            error = {
                val imagePainter = painter
                Box(contentAlignment = Alignment.Center) {
                    TextButton(onClick = { imagePainter.restart() }) { Text(stringResource(MR.strings.action_retry)) }
                }
            },
        )
        Text(label, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
