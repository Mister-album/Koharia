package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.toPersistentMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTrackingScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_tracking

    @Composable
    override fun getPreferences(): List<Preference> {
        val trackPreferences = remember { Injekt.get<TrackPreferences>() }

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = trackPreferences.autoUpdateTrack,
                title = stringResource(MR.strings.pref_auto_update_manga_sync),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = trackPreferences.autoUpdateTrackOnMarkRead,
                entries = AutoTrackState.entries
                    .associateWith { stringResource(it.titleRes) }
                    .toPersistentMap(),
                title = stringResource(MR.strings.pref_auto_update_manga_on_mark_read),
            ),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.enhanced_tracking_info)),
        )
    }
}
