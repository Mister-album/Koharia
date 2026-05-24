package koharia.source.komga

import androidx.compose.runtime.Composable
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.source.SourcePreferencesScreen

class KomgaServerSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        SourcePreferencesScreen(KomgaSource.ID).Content()
    }
}
