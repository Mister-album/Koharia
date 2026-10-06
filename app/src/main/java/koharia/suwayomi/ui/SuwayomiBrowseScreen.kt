package koharia.suwayomi.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.SuwayomiApi
import koharia.suwayomi.SuwayomiExtension
import koharia.suwayomi.SuwayomiSourceInfo
import koharia.suwayomi.SuwayomiSourceMangaType
import okhttp3.OkHttpClient
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private enum class SuwayomiBrowseTab(val label: StringResource, val key: String) {
    SOURCES(MR.strings.suwayomi_browse_sources, "sources"),
    EXTENSIONS(MR.strings.suwayomi_browse_extensions, "extensions"),
    MIGRATION(MR.strings.suwayomi_browse_migration, "migration"),
}

/**
 * The connection's browse tab. Its structure follows the server's own clients: the source
 * catalogue, the extension catalogue and the migration entry point, each with its own search.
 * The screen keeps only the connection id so Voyager can persist it across process death.
 */
class SuwayomiBrowseScreen(private val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val model = navigator.lastItem.rememberScreenModel(tag = "suwayomi-browse-${source.instanceKey}:$epoch") {
            SuwayomiBrowseScreenModel(source)
        }
        val state by model.state.collectAsState()
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        val session = remember(source, epoch) { source.session() }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var sourceQuery by rememberSaveable { mutableStateOf("") }
        var extensionQuery by rememberSaveable { mutableStateOf("") }
        val current = SuwayomiBrowseTab.entries[tab]

        LaunchedEffect(state.error) {
            state.error?.let { snackbar.showSnackbar(context.suwayomiError(it)) }
        }

        Scaffold(
            topBar = {
                Column {
                    AppBar(
                        title = stringResource(MR.strings.browse),
                        actions = {
                            when (current) {
                                SuwayomiBrowseTab.SOURCES -> IconButton(onClick = model::refresh) {
                                    Icon(Icons.Outlined.Refresh, stringResource(MR.strings.suwayomi_refresh))
                                }
                                SuwayomiBrowseTab.EXTENSIONS -> IconButton(onClick = model::refreshExtensions) {
                                    Icon(Icons.Outlined.Refresh, stringResource(MR.strings.check_for_updates))
                                }
                                SuwayomiBrowseTab.MIGRATION -> Unit
                            }
                        },
                    )
                    PrimaryTabRow(selectedTabIndex = tab) {
                        SuwayomiBrowseTab.entries.forEachIndexed { index, entry ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { TabText(stringResource(entry.label)) },
                            )
                        }
                    }
                    when (current) {
                        SuwayomiBrowseTab.SOURCES -> SuwayomiSearchField(
                            value = sourceQuery,
                            onValueChange = { sourceQuery = it },
                            placeholder = stringResource(MR.strings.suwayomi_source_search_hint),
                        )
                        SuwayomiBrowseTab.EXTENSIONS -> SuwayomiSearchField(
                            value = extensionQuery,
                            onValueChange = { extensionQuery = it },
                            placeholder = stringResource(MR.strings.suwayomi_extension_search_hint),
                        )
                        SuwayomiBrowseTab.MIGRATION -> Unit
                    }
                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (current) {
                    SuwayomiBrowseTab.SOURCES -> if (!state.sourceState.loaded) {
                        SuwayomiBrowseInitialState(state.sourceState.error, model::refresh)
                    } else {
                        SuwayomiSourcesTab(
                            state = state,
                            query = sourceQuery,
                            context = context,
                            client = session.api.client,
                            api = session.api,
                            onSelect = { info, mode ->
                                navigator.push(SuwayomiSourceScreen(source.id, info.id, mode))
                            },
                            onTogglePin = model::togglePin,
                        )
                    }
                    SuwayomiBrowseTab.EXTENSIONS -> if (!state.extensionState.loaded) {
                        SuwayomiBrowseInitialState(state.extensionState.error, model::refresh)
                    } else {
                        SuwayomiExtensionsTab(
                            state = state,
                            query = extensionQuery,
                            client = session.api.client,
                            api = session.api,
                            onInstall = model::install,
                            onUpdate = model::update,
                            onUninstall = model::uninstall,
                        )
                    }
                    SuwayomiBrowseTab.MIGRATION -> SuwayomiMigrationTab(source, Modifier.fillMaxSize())
                }
                val refreshing = when (current) {
                    SuwayomiBrowseTab.SOURCES -> state.sourceState.loaded && state.sourceState.loading
                    SuwayomiBrowseTab.EXTENSIONS -> state.extensionState.loaded && state.extensionState.loading
                    SuwayomiBrowseTab.MIGRATION -> false
                }
                if (refreshing ||
                    state.actionLoading
                ) {
                    tachiyomi.presentation.core.components.EInkLinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SuwayomiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
    )
}

@Composable
private fun SuwayomiSourcesTab(
    state: SuwayomiBrowseScreenModel.State,
    query: String,
    context: Context,
    client: OkHttpClient,
    api: SuwayomiApi,
    onSelect: (SuwayomiSourceInfo, SuwayomiSourceMangaType) -> Unit,
    onTogglePin: (SuwayomiSourceInfo) -> Unit,
) {
    val pinnedLabel = stringResource(MR.strings.pinned_sources)
    val localLabel = stringResource(MR.strings.local_source)
    val groups = remember(state.sources, query, pinnedLabel, localLabel) {
        groupSuwayomiSources(
            sources = state.sources,
            query = query,
            pinnedLabel = pinnedLabel,
            languageLabel = { lang ->
                if (lang.equals(LOCAL_SOURCE_LANGUAGE, ignoreCase = true)) {
                    localLabel
                } else {
                    LocaleHelper.getLocalizedDisplayName(lang)
                }
            },
        )
    }
    if (groups.isEmpty()) {
        EmptyScreen(message = stringResource(MR.strings.suwayomi_no_sources))
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        groups.forEach { group ->
            item(key = "sources-${group.label}") { SuwayomiSectionHeader(group.label) }
            items(group.entries, key = { it.id }) { info ->
                SuwayomiSourceRow(
                    info = info,
                    language = LocaleHelper.getSourceDisplayName(info.lang, context),
                    pinned = info.isPinned,
                    client = client,
                    api = api,
                    onClick = { onSelect(info, SuwayomiSourceMangaType.POPULAR) },
                    onBrowseLatest = { onSelect(info, SuwayomiSourceMangaType.LATEST) },
                    onTogglePin = { onTogglePin(info) },
                )
            }
        }
    }
}

@Composable
private fun SuwayomiSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.medium,
            top = MaterialTheme.padding.medium,
            bottom = MaterialTheme.padding.small,
        ),
    )
}

@Composable
private fun SuwayomiSourceRow(
    info: SuwayomiSourceInfo,
    language: String,
    pinned: Boolean,
    client: OkHttpClient,
    api: SuwayomiApi,
    onClick: () -> Unit,
    onBrowseLatest: () -> Unit,
    onTogglePin: () -> Unit,
) {
    BaseBrowseItem(
        onClickItem = onClick,
        icon = {
            SuwayomiIcon(
                url = info.iconUrl.takeIf(String::isNotBlank)?.let(api::resourceUrlOrNull),
                description = info.name,
                client = client,
                api = api,
            )
        },
        content = {
            Column(Modifier.padding(horizontal = MaterialTheme.padding.medium).weight(1f)) {
                Text(
                    text = info.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = language,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
        },
        action = {
            if (info.supportsLatest) {
                TextButton(onClick = onBrowseLatest) {
                    Text(stringResource(MR.strings.latest), maxLines = 1)
                }
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = stringResource(
                        if (pinned) MR.strings.action_unpin else MR.strings.action_pin,
                    ),
                )
            }
        },
    )
}

@Composable
private fun SuwayomiExtensionsTab(
    state: SuwayomiBrowseScreenModel.State,
    query: String,
    client: OkHttpClient,
    api: SuwayomiApi,
    onInstall: (SuwayomiExtension) -> Unit,
    onUpdate: (SuwayomiExtension) -> Unit,
    onUninstall: (SuwayomiExtension) -> Unit,
) {
    val visible = remember(state.extensions, query) {
        if (query.isBlank()) state.extensions else state.extensions.filter { it.name.contains(query, true) }
    }
    val updates = visible.filter { it.isInstalled && it.hasUpdate }
    val installed = visible.filter { it.isInstalled && !it.hasUpdate }
    val available = visible.filter { !it.isInstalled }
    if (updates.isEmpty() && installed.isEmpty() && available.isEmpty()) {
        EmptyScreen(message = stringResource(MR.strings.no_results_found))
        return
    }
    val busy = state.actionLoading || state.extensionState.loading
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        extensionSection(MR.strings.ext_updates_pending, updates, client, api) { extension ->
            TextButton(onClick = { onUpdate(extension) }, enabled = !busy) {
                Text(stringResource(MR.strings.ext_update))
            }
        }
        extensionSection(MR.strings.ext_installed, installed, client, api) { extension ->
            TextButton(onClick = { onUninstall(extension) }, enabled = !busy) {
                Text(stringResource(MR.strings.ext_uninstall))
            }
            IconButton(onClick = { onInstall(extension) }, enabled = !busy) {
                Icon(
                    imageVector = Icons.Outlined.Replay,
                    contentDescription = stringResource(MR.strings.suwayomi_extension_reinstall),
                )
            }
        }
        extensionSection(MR.strings.suwayomi_extension_available, available, client, api) { extension ->
            TextButton(onClick = { onInstall(extension) }, enabled = !busy) {
                Text(stringResource(MR.strings.ext_install))
            }
        }
    }
}

private fun LazyListScope.extensionSection(
    title: StringResource,
    entries: List<SuwayomiExtension>,
    client: OkHttpClient,
    api: SuwayomiApi,
    actions: @Composable RowScope.(SuwayomiExtension) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "extensions-${entries.first().pkgName}-${entries.size}") {
        SuwayomiSectionHeader(stringResource(title))
    }
    items(entries, key = { it.pkgName }) { extension ->
        SuwayomiExtensionRow(extension, client, api, actions)
    }
}

@Composable
private fun SuwayomiExtensionRow(
    extension: SuwayomiExtension,
    client: OkHttpClient,
    api: SuwayomiApi,
    actions: @Composable RowScope.(SuwayomiExtension) -> Unit,
) {
    val nsfw = stringResource(MR.strings.ext_nsfw_short)
    val meta = listOfNotNull(
        extension.lang.takeIf(String::isNotBlank),
        extension.versionName.takeIf(String::isNotBlank),
        nsfw.takeIf { extension.contentWarning != null },
    ).joinToString(" ")
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SuwayomiIcon(
            url = extension.iconUrl.takeIf(String::isNotBlank)?.let(api::resourceUrlOrNull),
            description = extension.name,
            client = client,
            api = api,
        )
        Column(Modifier.padding(horizontal = MaterialTheme.padding.medium).weight(1f)) {
            Text(extension.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
        actions(extension)
    }
}

@Composable
private fun SuwayomiBrowseInitialState(error: Throwable?, retry: () -> Unit) {
    if (error == null) {
        LoadingScreen()
    } else {
        val context = LocalContext.current
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(context.suwayomiError(error))
            TextButton(onClick = retry) { Text(stringResource(MR.strings.action_retry)) }
        }
    }
}
