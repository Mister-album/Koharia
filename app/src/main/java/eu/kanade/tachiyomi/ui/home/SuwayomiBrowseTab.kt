package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.ui.SuwayomiBrowseScreen
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object SuwayomiBrowseTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            3u,
            stringResource(MR.strings.browse),
            painterResource(R.drawable.ic_extension_24dp),
        )

    @Composable
    override fun Content() {
        val sourceId = Injekt.get<koharia.connection.ConnectionPreferences>().activeConnectionId.get()
        val source = Injekt.get<SourceManager>().get(sourceId) as? SuwayomiSource
        if (source != null) {
            SuwayomiBrowseScreen(sourceId).Content()
        } else {
            tachiyomi.presentation.core.screens.EmptyScreen(stringRes = MR.strings.connection_unavailable)
        }
    }
}
