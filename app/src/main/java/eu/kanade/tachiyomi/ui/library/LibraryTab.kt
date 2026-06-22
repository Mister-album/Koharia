package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import koharia.komga.ui.library.KomgaLibraryScreen
import koharia.source.komga.KomgaSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object LibraryTab : Tab {

    private val komgaBrowseScreen = KomgaLibraryScreen(
        sourceId = KomgaSource.ID,
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
        val isSelected = LocalTabNavigator.current.current.key == key
        var hasEntered by remember { mutableStateOf(false) }

        LaunchedEffect(isSelected) {
            if (isSelected) {
                if (hasEntered) {
                    komgaBrowseScreen.refresh()
                } else {
                    hasEntered = true
                }
            }
        }

        komgaBrowseScreen.Content()
    }

    suspend fun search(query: String) = komgaBrowseScreen.search(query)

    suspend fun searchGenre(name: String) = komgaBrowseScreen.searchGenre(name)
}
