package koharia.source.komga

import androidx.compose.runtime.Composable
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.source.SourcePreferencesScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class KomgaServerSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        SourcePreferencesScreen(
            sourceId = KomgaSource.ID,
            titleOverride = stringResource(MR.strings.pref_komga_server),
        ).Content()
    }
}
