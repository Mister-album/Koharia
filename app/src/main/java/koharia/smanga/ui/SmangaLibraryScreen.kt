package koharia.smanga.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.util.Screen
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ui.ConnectionPagedShelf
import koharia.connection.ui.ConnectionPagedShelfState
import koharia.connection.ui.ConnectionSearchSortOption
import koharia.connection.ui.ConnectionShelfError
import koharia.connection.ui.ConnectionShelfTab
import koharia.source.smanga.SmangaSettingsScreen
import koharia.source.smanga.SmangaSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient

class SmangaLibraryScreen(
    override val sourceId: Long,
    private val initialQuery: String?,
    private val showNavigationUp: Boolean,
) : Screen(), ConnectionBrowseScreen {
    override val refreshOnReselect = false

    @Transient private var runtimeModel: SmangaLibraryScreenModel? = null
    override suspend fun search(query: String) {
        runtimeModel?.search(query)
    }
    override suspend fun searchGenre(name: String) {
        runtimeModel?.search(name)
    }
    override suspend fun refresh() {
        runtimeModel?.refresh()
    }

    @Composable
    override fun Content() {
        val source = Injekt.get<SourceManager>().get(sourceId) as? SmangaSource
        if (source == null || !source.hasValidConnection()) {
            EmptyScreen(stringRes = MR.strings.connection_unavailable)
            return
        }
        val epoch by source.epoch.collectAsState()
        val model = rememberScreenModel(tag = "${source.instanceKey}:$epoch") {
            SmangaLibraryScreenModel(source, initialQuery)
        }
        runtimeModel = model
        val state by model.state.collectAsState()
        val pages = model.pages.collectAsLazyPagingItems()
        val context = LocalContext.current
        val sorts = listOf(
            "mangaName" to MR.strings.smanga_sort_name,
            "updateTime" to MR.strings.smanga_sort_updated,
            "createTime" to MR.strings.smanga_sort_created,
        ).map { (value, label) ->
            ConnectionSearchSortOption(value, stringResource(label), defaultAscending = value == "mangaName")
        }
        key(model) {
            ConnectionPagedShelf(
                source = source,
                state = ConnectionPagedShelfState(
                    state.query, state.toolbarQuery, state.order, state.displayMode, state.downloadedOnly,
                    state.refreshing, state.mediaLoaded, state.error,
                    state.media.map { ConnectionShelfTab(it.id, it.name) }, state.selectedMedia, 0,
                    persistentFilters = state.persistentFilters,
                ),
                pages = pages, sortOptions = sorts, showNavigationUp = showNavigationUp,
                settings = { SmangaSettingsScreen(sourceId) }, errorMessage = context::smangaError,
                materialize = source::materialize, onQueryChange = model::setToolbarQuery,
                onSearch = model::search, onCloseSearch = model::exitSearch,
                onDisplayModeChange = model::setDisplayMode, onSelectTab = model::selectMedia,
                onSelectSort = model::selectSearchSort, onRefresh = model::refresh,
                onFilterStates = { _, order, downloadedOnly, persistent ->
                    model.applyLibraryFilters(order, downloadedOnly, persistent)
                },
            )
        }
    }
}

@Composable
internal fun SmangaShelfError(error: Throwable, onRetry: () -> Unit, onSettings: () -> Unit) {
    ConnectionShelfError(LocalContext.current.smangaError(error), onRetry, onSettings)
}
