package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import koharia.source.komga.KomgaSource
import koharia.komga.ui.library.KomgaLibraryScreen
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

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        komgaBrowseScreen.Content()
    }

    suspend fun search(query: String) = komgaBrowseScreen.search(query)
}
