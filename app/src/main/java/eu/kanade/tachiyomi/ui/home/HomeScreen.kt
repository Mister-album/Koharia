package eu.kanade.tachiyomi.ui.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.eink.EInkRefreshReason
import eu.kanade.presentation.eink.LocalEInkAppRefreshController
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.BooksTab
import eu.kanade.tachiyomi.ui.library.ComicsTab
import eu.kanade.tachiyomi.ui.library.ConnectionLibraryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import koharia.connection.ConnectionContentScopeController
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryContentScope
import koharia.source.suwayomi.SuwayomiSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.NavigationBar
import tachiyomi.presentation.core.components.material.NavigationRail
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.motion.EInkAnimatedContent
import tachiyomi.presentation.core.motion.EInkAnimatedVisibility
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val libraryGenreSearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    private val showBottomNavEvent = Channel<Boolean>()

    @Suppress("ConstPropertyName")
    private const val TabFadeDuration = 200

    @Suppress("ConstPropertyName")
    private const val TabNavigatorKey = "HomeTabs"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val contentScopeController = remember { Injekt.get<ConnectionContentScopeController>() }
        val contentScopes by remember(contentScopeController) {
            contentScopeController.activeScopesChanges()
        }.collectAsState(contentScopeController.activeScopes())
        val classificationEnabled = LibraryContentScope.COMIC in contentScopes &&
            LibraryContentScope.BOOK in contentScopes
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val activeConnectionId by connectionPreferences.activeConnectionId.collectPreferenceAsState()
        val registeredSources by
            Injekt.get<SourceManager>().catalogueSources.collectAsState(emptyList())
        val suwayomiActive =
            remember(activeConnectionId, registeredSources) {
                Injekt.get<SourceManager>().get(activeConnectionId) is SuwayomiSource
            }
        val organizationPages =
            remember(activeConnectionId, registeredSources) {
                (
                    Injekt.get<SourceManager>().get(activeConnectionId)
                        as? koharia.connection.ConnectionOrganizationAdapter
                    )
                    ?.organizationPages
                    .orEmpty()
            }
        val libraryPreferences = remember { Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>() }
        val showCollections by libraryPreferences.showCollections.collectPreferenceAsState()
        val showReadLists by libraryPreferences.showReadLists.collectPreferenceAsState()
        val mergeOrganizations by libraryPreferences.mergeOrganizationPages.collectPreferenceAsState()
        val organizationNavigation = koharia.connection.ConnectionOrganizationNavigation.create(
            organizationPages,
            showCollections,
            showReadLists,
            mergeOrganizations,
        )
        val organizationTabs = koharia.connection.ui.organizationNavigationTabs(organizationNavigation)
        val defaultLibraryTab: ConnectionLibraryTab = if (classificationEnabled) ComicsTab else LibraryTab
        val tabs = if (classificationEnabled) {
            buildList<eu.kanade.presentation.util.Tab> {
                add(ComicsTab)
                add(BooksTab)
                if (suwayomiActive) add(SuwayomiBrowseTab)
                addAll(organizationTabs)
                add(HistoryTab)
                add(MoreTab)
            }
        } else {
            buildList<eu.kanade.presentation.util.Tab> {
                add(LibraryTab)
                if (suwayomiActive) add(SuwayomiBrowseTab)
                addAll(organizationTabs)
                add(HistoryTab)
                add(MoreTab)
            }
        }
        TabNavigator(tab = defaultLibraryTab, key = TabNavigatorKey) { tabNavigator ->
            val activity = LocalContext.current as? Activity

            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                Scaffold(
                    startBar = {
                        if (isTabletUi()) {
                            NavigationRail {
                                tabs.fastForEach {
                                    NavigationRailItem(it)
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!isTabletUi()) {
                            val bottomNavVisible by produceState(initialValue = true) {
                                showBottomNavEvent.receiveAsFlow().collectLatest { value = it }
                            }
                            EInkAnimatedVisibility(
                                visible = bottomNavVisible,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                BottomNavigationBar(tabs)
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { contentPadding ->
                    Box(
                        modifier = Modifier
                            .padding(contentPadding)
                            .consumeWindowInsets(contentPadding),
                    ) {
                        EInkAnimatedContent(
                            targetState = tabNavigator.current,
                            transitionSpec = {
                                materialFadeThroughIn(initialScale = 1f, durationMillis = TabFadeDuration) togetherWith
                                    materialFadeThroughOut(durationMillis = TabFadeDuration)
                            },
                            label = "tabContent",
                        ) {
                            tabNavigator.saveableState(key = "currentTab", it) {
                                it.Content()
                            }
                        }
                    }
                }
            }

            val goToLibraryTab = { tabNavigator.current = defaultLibraryTab }
            val refreshController = LocalEInkAppRefreshController.current
            LaunchedEffect(tabNavigator.current.key) {
                refreshController?.request(EInkRefreshReason.TAB, tabNavigator.current.key)
            }

            BackHandler(enabled = tabNavigator.current != defaultLibraryTab, onBack = goToLibraryTab)
            // Backgrounding, tab changes, and nested screens keep the browse session. Only a
            // back press from the root library screen starts a fresh session on the next launch.
            BackHandler(
                enabled = activity != null &&
                    navigator.lastItem is HomeScreen &&
                    tabNavigator.current == defaultLibraryTab,
            ) {
                ConnectionLibraryTab.clearAllRuntimeState()
                activity?.finish()
            }

            LaunchedEffect(classificationEnabled, suwayomiActive, organizationNavigation) {
                tabNavigator.current =
                    when {
                        tabNavigator.current !in tabs -> {
                            if (tabNavigator.current is koharia.connection.ui.ConnectionOrganizationTab) {
                                organizationTabs.firstOrNull() ?: defaultLibraryTab
                            } else {
                                defaultLibraryTab
                            }
                        }
                        !suwayomiActive && tabNavigator.current == SuwayomiBrowseTab -> defaultLibraryTab
                        classificationEnabled && tabNavigator.current == LibraryTab -> ComicsTab
                        !classificationEnabled &&
                            (tabNavigator.current == ComicsTab || tabNavigator.current == BooksTab) -> LibraryTab
                        else -> tabNavigator.current
                    }
            }

            LaunchedEffect(classificationEnabled, suwayomiActive) {
                launch {
                    librarySearchEvent.receiveAsFlow().collectLatest {
                        goToLibraryTab()
                        defaultLibraryTab.search(it)
                    }
                }
                launch {
                    libraryGenreSearchEvent.receiveAsFlow().collectLatest {
                        goToLibraryTab()
                        defaultLibraryTab.searchGenre(it)
                    }
                }
                launch {
                    openTabEvent.receiveAsFlow().collectLatest {
                        tabNavigator.current = when (it) {
                            is Tab.Library -> defaultLibraryTab
                            Tab.Updates -> if (classificationEnabled) BooksTab else LibraryTab
                            Tab.History -> HistoryTab
                            is Tab.More -> MoreTab
                            Tab.Browse -> if (suwayomiActive) SuwayomiBrowseTab else defaultLibraryTab
                        }

                        if (it is Tab.Library && it.mangaIdToOpen != null) {
                            navigator.push(
                                MangaScreen(
                                    mangaId = it.mangaIdToOpen,
                                    fromSource = it.fromSource,
                                    sourceId = it.sourceId,
                                    mangaUrl = it.mangaUrl,
                                ),
                            )
                        }
                        if (it is Tab.More && it.toDownloads) {
                            navigator.push(DownloadQueueScreen)
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun BottomNavigationBar(tabs: List<eu.kanade.presentation.util.Tab>) {
        NavigationBar(
            minimumContentWidth = if (tabs.size > 5) 64.dp * tabs.size else 0.dp,
        ) {
            tabs.fastForEach { NavigationBarItem(it) }
        }
    }

    @Composable
    private fun RowScope.NavigationBarItem(tab: eu.kanade.presentation.util.Tab) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current.key == tab.key
        val bringIntoView = remember { BringIntoViewRequester() }
        LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
        NavigationBarItem(
            modifier = Modifier.bringIntoViewRequester(bringIntoView),
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = { NavigationIconItem(tab) },
            label = {
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            alwaysShowLabel = true,
        )
    }

    @Composable
    fun NavigationRailItem(tab: eu.kanade.presentation.util.Tab) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current.key == tab.key
        NavigationRailItem(
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = { NavigationIconItem(tab) },
            label = {
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            alwaysShowLabel = true,
        )
    }

    @Composable
    private fun NavigationIconItem(tab: eu.kanade.presentation.util.Tab) {
        Icon(
            painter = tab.options.icon!!,
            contentDescription = tab.options.title,
        )
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun searchGenre(name: String) {
        libraryGenreSearchEvent.send(name)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    sealed interface Tab {
        data class Library(
            val mangaIdToOpen: Long? = null,
            val fromSource: Boolean = false,
            val sourceId: Long? = null,
            val mangaUrl: String? = null,
        ) : Tab
        data object Updates : Tab
        data object History : Tab
        data object Browse : Tab
        data class More(val toDownloads: Boolean) : Tab
    }
}
