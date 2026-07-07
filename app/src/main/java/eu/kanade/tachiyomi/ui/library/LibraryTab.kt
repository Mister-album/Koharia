package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import koharia.komga.ui.library.KomgaLibraryScreen
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaServerProfilesScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object LibraryTab : Tab {

    private var komgaBrowseScreen = KomgaLibraryScreen(
        sourceId = KomgaServerPreferences.NO_ACTIVE_SERVER,
        listingQuery = null,
        showNavigationUp = false,
    )

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        komgaBrowseScreen.refresh()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val komgaServerPreferences = remember { Injekt.get<KomgaServerPreferences>() }
        val activeServerId by komgaServerPreferences.activeServerId.collectAsState()
        if (activeServerId == KomgaServerPreferences.NO_ACTIVE_SERVER) {
            Scaffold { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(MR.strings.komga_no_servers_title))
                    Text(text = stringResource(MR.strings.komga_no_servers_summary))
                    TextButton(onClick = { navigator.push(KomgaServerProfilesScreen()) }) {
                        Text(text = stringResource(MR.strings.pref_server_management))
                    }
                }
            }
            return
        }
        val activeScreen = remember(activeServerId) {
            KomgaLibraryScreen(
                sourceId = activeServerId,
                listingQuery = null,
                showNavigationUp = false,
            )
        }
        val isSelected = LocalTabNavigator.current.current.key == key
        var hasEntered by remember { mutableStateOf(false) }

        SideEffect {
            komgaBrowseScreen = activeScreen
        }

        LaunchedEffect(isSelected) {
            if (isSelected) {
                if (hasEntered) {
                    komgaBrowseScreen.refresh()
                } else {
                    hasEntered = true
                }
            }
        }

        activeScreen.Content()
    }

    suspend fun search(query: String) = komgaBrowseScreen.search(query)

    suspend fun searchGenre(name: String) = komgaBrowseScreen.searchGenre(name)
}
