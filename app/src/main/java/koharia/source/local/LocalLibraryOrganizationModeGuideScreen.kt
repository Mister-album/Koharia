package koharia.source.local

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class LocalLibraryOrganizationModeGuideScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.local_library_organization_guide_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    PreferenceGroupHeader(title = stringResource(MR.strings.local_library_mode_series))
                }
                item {
                    GuideText(stringResource(MR.strings.local_library_mode_series_guide))
                }
                item {
                    PreferenceGroupHeader(title = stringResource(MR.strings.local_library_mode_individual))
                }
                item {
                    GuideText(stringResource(MR.strings.local_library_mode_individual_guide))
                }
                item {
                    PreferenceGroupHeader(
                        title = stringResource(MR.strings.local_library_organization_note_title),
                    )
                }
                item {
                    GuideText(stringResource(MR.strings.local_library_organization_locked))
                }
            }
        }
    }
}

@Composable
private fun GuideText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
