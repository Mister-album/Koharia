package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import koharia.connection.ConnectionBrowseAdapter
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryContentScope
import koharia.connection.NO_ACTIVE_CONNECTION
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.connection.ui.LibraryConnectionSetupPrompt
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient

sealed class ConnectionLibraryTab(
    private val libraryScope: LibraryContentScope,
    private val tabIndex: UShort,
) : Tab {

    // Voyager serializes Tab instances when the host Activity is stopped. The
    // browse screen owns coroutine channels and must therefore stay runtime-only.
    @Transient
    @Volatile
    private var runtimeBrowseScreen: ConnectionBrowseScreen? = null

    private fun browseScreen(
        activeConnectionId: Long = Injekt.get<ConnectionPreferences>().activeConnectionId.get(),
    ): ConnectionBrowseScreen? {
        if (activeConnectionId == NO_ACTIVE_CONNECTION) return null
        runtimeBrowseScreen?.takeIf { it.sourceId == activeConnectionId }?.let { return it }
        return synchronized(this) {
            runtimeBrowseScreen
                ?.takeIf { it.sourceId == activeConnectionId }
                ?: newScreen(activeConnectionId)?.also { runtimeBrowseScreen = it }
        }
    }

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val title = when (libraryScope) {
                LibraryContentScope.ALL -> stringResource(MR.strings.label_library)
                LibraryContentScope.COMIC -> stringResource(MR.strings.label_comics)
                LibraryContentScope.BOOK -> stringResource(MR.strings.label_books)
            }
            val icon = if (libraryScope == LibraryContentScope.BOOK) {
                painterResource(R.drawable.ic_book_24dp)
            } else {
                val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
                rememberAnimatedVectorPainter(image, isSelected)
            }
            return TabOptions(
                index = tabIndex,
                title = title,
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        browseScreen()?.takeIf { it.refreshOnReselect }?.refresh()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val activeServerId by connectionPreferences.activeConnectionId.collectAsState()
        if (activeServerId == NO_ACTIVE_CONNECTION) {
            Scaffold { contentPadding ->
                LibraryConnectionSetupPrompt(
                    onConfigureConnection = { navigator.push(LibraryConnectionProfilesScreen(openAddDialog = true)) },
                    modifier = Modifier.padding(contentPadding),
                )
            }
            return
        }
        // Keep the same screen instance while opening details or a reader. Its ScreenModel owns
        // the selected shelf, filters, search, and paging cache.
        val activeScreen = remember(activeServerId) { browseScreen(activeServerId) }
        if (activeScreen == null) {
            tachiyomi.presentation.core.screens.EmptyScreen(
                stringRes = MR.strings.connection_unavailable,
            )
            return
        }
        val isSelected = LocalTabNavigator.current.current.key == key
        var hasEntered by remember { mutableStateOf(false) }

        LaunchedEffect(isSelected) {
            if (isSelected) {
                if (hasEntered) {
                    browseScreen()?.takeIf { it.refreshOnReselect }?.refresh()
                } else {
                    hasEntered = true
                }
            }
        }

        activeScreen.Content()
    }

    suspend fun search(query: String) = browseScreen()?.search(query)

    suspend fun searchGenre(name: String) = browseScreen()?.searchGenre(name)

    private fun clearRuntimeState() {
        val cachedSourceId = synchronized(this) {
            runtimeBrowseScreen?.sourceId.also { runtimeBrowseScreen = null }
        }
        val activeSourceId = Injekt.get<ConnectionPreferences>().activeConnectionId.get()
        setOfNotNull(
            cachedSourceId,
            activeSourceId.takeUnless { it == NO_ACTIVE_CONNECTION },
        ).forEach { sourceId ->
            (Injekt.get<SourceManager>().get(sourceId) as? ConnectionBrowseAdapter)
                ?.clearBrowseSession(libraryScope)
        }
    }

    private fun newScreen(sourceId: Long): ConnectionBrowseScreen? {
        val source = Injekt.get<SourceManager>().get(sourceId) ?: return null
        return (source as? ConnectionBrowseAdapter)?.createBrowseScreen(
            scope = libraryScope,
            listingQuery = null,
            showNavigationUp = false,
        )
    }

    companion object {
        fun clearAllRuntimeState() {
            listOf<ConnectionLibraryTab>(LibraryTab, ComicsTab, BooksTab)
                .forEach(ConnectionLibraryTab::clearRuntimeState)
        }
    }
}

data object LibraryTab : ConnectionLibraryTab(LibraryContentScope.ALL, 0u)

data object ComicsTab : ConnectionLibraryTab(LibraryContentScope.COMIC, 0u)

data object BooksTab : ConnectionLibraryTab(LibraryContentScope.BOOK, 1u)
