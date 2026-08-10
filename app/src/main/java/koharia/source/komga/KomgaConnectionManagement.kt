package koharia.source.komga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun KomgaManagementPreferencesContent() {
    val preferences = remember { Injekt.get<KomgaServerPreferences>() }
    val downloadDirectoryMode by preferences.downloadDirectoryMode.collectAsState()
    ListPreferenceWidget(
        value = downloadDirectoryMode,
        title = stringResource(MR.strings.komga_download_directory_mode),
        subtitle = stringResource(
            if (downloadDirectoryMode == DownloadDirectoryMode.PerServer) {
                MR.strings.komga_download_directory_mode_per_server
            } else {
                MR.strings.komga_download_directory_mode_shared
            },
        ),
        icon = null,
        entries = mapOf(
            DownloadDirectoryMode.PerServer to
                stringResource(MR.strings.komga_download_directory_mode_per_server),
            DownloadDirectoryMode.Shared to
                stringResource(MR.strings.komga_download_directory_mode_shared),
        ).toImmutableMap(),
        onValueChange = { preferences.downloadDirectoryMode.set(it) },
    )
}

@Composable
internal fun KomgaManagementHelpContent() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(MR.strings.komga_download_directory_mode))
        Text(text = stringResource(MR.strings.komga_download_directory_mode_per_server_explanation))
        Text(text = stringResource(MR.strings.komga_download_directory_mode_shared_explanation))
    }
}
