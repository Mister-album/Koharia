package koharia.connection.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.util.ifSourcesLoaded
import koharia.connection.ConnectionOrganizationAdapter
import koharia.connection.ConnectionOrganizationEntry
import koharia.connection.ConnectionOrganizationNavigation
import koharia.connection.ConnectionOrganizationPage
import koharia.connection.ConnectionPreferences
import koharia.connection.LocalOrganizationScreenOwner
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.presentation.util.Tab as HomeTab

sealed class ConnectionOrganizationTab(private val page: ConnectionOrganizationPage?) : HomeTab {
    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = if (page == ConnectionOrganizationPage.READ_LISTS) 4u else 3u,
            title = stringResource(page?.titleResource() ?: MR.strings.label_organizations),
            icon = rememberVectorPainter(
                when (page) {
                    ConnectionOrganizationPage.COLLECTIONS -> Icons.Outlined.FolderSpecial
                    ConnectionOrganizationPage.READ_LISTS -> Icons.AutoMirrored.Outlined.PlaylistPlay
                    null -> Icons.AutoMirrored.Outlined.ListAlt
                },
            ),
        )

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }
        val id by Injekt.get<ConnectionPreferences>().activeConnectionId.collectAsState()
        val source = Injekt.get<SourceManager>().get(id) as? ConnectionOrganizationAdapter ?: return
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val collections by preferences.showCollections.collectAsState()
        val readLists by preferences.showReadLists.collectAsState()
        val merge by preferences.mergeOrganizationPages.collectAsState()
        val navigation = ConnectionOrganizationNavigation.create(
            source.organizationPages,
            collections,
            readLists,
            merge,
        )
        val pages = if (page == null) navigation.pages else listOfNotNull(page.takeIf { it in navigation.pages })
        if (pages.isEmpty()) return
        val directory = remember(source, source.organizationNamespace) { source.organizationDirectory() }
        val directoryModel = rememberScreenModel(tag = "directory:${directory.namespace}:$pages") {
            ConnectionOrganizationDirectoryModel(directory, pages)
        }
        val directoryState by directoryModel.state.collectAsState()
        CompositionLocalProvider(LocalOrganizationScreenOwner provides this) {
            OrganizationContentHost(
                directory.namespace,
                pages,
                directoryState,
                onReload = { directoryModel.load(refresh = true) },
            ) { selected, entryId, pageTabs ->
                source.OrganizationContent(selected, entryId, pageTabs) { directoryModel.load(refresh = true) }
            }
        }
    }
}

@Composable
internal fun OrganizationContentHost(
    sessionKey: String,
    pages: List<ConnectionOrganizationPage>,
    directory: ConnectionOrganizationDirectoryState,
    onReload: () -> Unit,
    content: @Composable (ConnectionOrganizationPage, String?, @Composable () -> Unit) -> Unit,
) {
    if (pages.isEmpty()) return
    var selectedPage by rememberSaveable(sessionKey) { mutableStateOf(pages.first().name) }
    var selectedEntryKey by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
    val selected = pages.firstOrNull { it.name == selectedPage } ?: pages.first()
    val entries = directory.entries.filter { it.page == selected }
    val entry = entries.firstOrNull { it.key == selectedEntryKey }
    LaunchedEffect(pages) { selectedPage = selected.name }
    LaunchedEffect(directory.loaded, entries) {
        if (directory.loaded && entries.none { it.key == selectedEntryKey }) selectedEntryKey = null
    }
    key(sessionKey, selected, entry?.id) {
        content(selected, entry?.id) {
            if (pages.size > 1) {
                OrganizationPageTabs(pages, selected) {
                    if (it != selected) {
                        selectedEntryKey = null
                        selectedPage = it.name
                    }
                }
            }
            OrganizationNameTabs(
                directory.copy(entries = entries),
                entry?.key,
                onSelect = { selectedEntryKey = it.key },
                onSelectAll = { selectedEntryKey = null },
                onReload = onReload,
            )
        }
    }
}

@Composable
internal fun OrganizationNameTabs(
    directory: ConnectionOrganizationDirectoryState,
    selectedKey: String?,
    onSelect: (ConnectionOrganizationEntry) -> Unit,
    onSelectAll: () -> Unit,
    onReload: () -> Unit,
) {
    Column {
        ConnectionLibraryTabs(
            entries = directory.entries,
            key = { it.key },
            label = { it.name },
            isSelected = { it.key == selectedKey },
            onSelect = onSelect,
            allSelected = selectedKey == null,
            onSelectAll = onSelectAll,
            showAllWhenEmpty = true,
        )
        if (directory.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (directory.error != null) {
            TextButton(onClick = onReload) { Text(stringResource(MR.strings.action_retry)) }
        }
    }
}

@Composable
internal fun OrganizationPageTabs(
    pages: List<ConnectionOrganizationPage>,
    selected: ConnectionOrganizationPage,
    onSelect: (ConnectionOrganizationPage) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = pages.indexOf(selected).coerceAtLeast(0)) {
        pages.forEach { page ->
            Tab(
                selected = selected == page,
                onClick = { onSelect(page) },
                text = {
                    Box(Modifier.padding(bottom = 8.dp)) {
                        TabText(stringResource(page.titleResource()))
                    }
                },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun ConnectionOrganizationPage.titleResource() = when (this) {
    ConnectionOrganizationPage.COLLECTIONS -> MR.strings.komga_collections
    ConnectionOrganizationPage.READ_LISTS -> MR.strings.komga_filter_read_lists
}

internal fun organizationNavigationTabs(navigation: ConnectionOrganizationNavigation): List<HomeTab> {
    if (navigation.combined) return listOf(OrganizationsTab)
    return navigation.pages.map { page ->
        when (page) {
            ConnectionOrganizationPage.COLLECTIONS -> CollectionsTab
            ConnectionOrganizationPage.READ_LISTS -> ReadListsTab
        }
    }
}

data object CollectionsTab : ConnectionOrganizationTab(ConnectionOrganizationPage.COLLECTIONS)

data object ReadListsTab : ConnectionOrganizationTab(ConnectionOrganizationPage.READ_LISTS)

data object OrganizationsTab : ConnectionOrganizationTab(null)
