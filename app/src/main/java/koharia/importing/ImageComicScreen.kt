package koharia.importing

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

data class ImageComicScreen(
    private val uris: List<String>,
    private val sourceId: Long,
    private val preferredShelfId: String?,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ImageComicScreenModel(context.applicationContext, uris) }
        val state by model.state.collectAsState()
        BackHandler(enabled = state.building) {}
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.image_comic_merge),
                    navigateUp = { if (!state.building) navigator.pop() },
                    scrollBehavior = it,
                )
            },
            bottomBar = {
                Button(
                    onClick = model::build,
                    enabled = state.canBuild,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text(stringResource(MR.strings.image_comic_continue)) }
            },
        ) { padding ->
            ScrollbarLazyColumn(contentPadding = padding) {
                if (state.loading || state.building) item { EInkLinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.invalidSelection) {
                    item { Text(stringResource(MR.strings.image_comic_selection_required), Modifier.padding(24.dp)) }
                } else if (!state.loading) {
                    item {
                        OutlinedTextField(
                            value = state.title,
                            onValueChange = model::title,
                            enabled = !state.building,
                            label = { Text(stringResource(MR.strings.name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                    item {
                        Text(
                            stringResource(MR.strings.image_comic_order_hint),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (state.building) {
                        item {
                            Text(
                                stringResource(
                                    MR.strings.image_comic_progress,
                                    state.completedPages,
                                    state.images.size,
                                ),
                                Modifier.padding(16.dp),
                            )
                        }
                    }
                    itemsIndexed(state.images, key = { _, item -> item.uri }) { index, item ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AsyncImage(
                                model = Uri.parse(item.uri),
                                contentDescription = item.displayName,
                                modifier = Modifier.size(56.dp),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f)) {
                                Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall)
                                Text(item.displayName, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(enabled = !state.building && index > 0, onClick = { model.move(index, -1) }) {
                                Icon(Icons.Outlined.ArrowUpward, stringResource(MR.strings.image_comic_move_up))
                            }
                            IconButton(
                                enabled = !state.building && index < state.images.lastIndex,
                                onClick = { model.move(index, 1) },
                            ) {
                                Icon(Icons.Outlined.ArrowDownward, stringResource(MR.strings.image_comic_move_down))
                            }
                            IconButton(enabled = !state.building, onClick = { model.remove(index) }) {
                                Icon(Icons.Outlined.Delete, stringResource(MR.strings.action_remove))
                            }
                        }
                    }
                    if (state.images.size < 2) {
                        item {
                            Text(stringResource(MR.strings.image_comic_selection_required), Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
        LaunchedEffect(model) {
            model.events.collect { event ->
                when (event) {
                    ImageComicScreenModel.Event.Ready -> model.takeArchive()?.let { file ->
                        navigator.replace(
                            ExternalMediaImportScreen(
                                uriValues = listOf(file.toURI().toString()),
                                startAtImportConfiguration = true,
                                restrictedConnectionId = sourceId,
                                preferredShelfId = preferredShelfId,
                                returnToCallerAfterImport = true,
                                generatedComicPath = file.absolutePath,
                            ),
                        )
                    }
                    ImageComicScreenModel.Event.Failed -> context.toast(MR.strings.image_comic_failed)
                }
            }
        }
    }
}
