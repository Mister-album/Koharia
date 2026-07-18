package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import koharia.source.komga.KomgaLibraryClassificationManager
import koharia.source.komga.KomgaLibraryKind
import koharia.source.komga.KomgaServerPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsLibraryScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.pref_category_library

    @Composable
    override fun getPreferences(): List<Preference> {
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }

        return listOf(
            getContentClassificationGroup(),
            getDisplayGroup(libraryPreferences),
            getGlobalUpdateGroup(libraryPreferences),
            getChapterSettingsGroup(libraryPreferences),
            getBehaviorGroup(libraryPreferences),
        )
    }

    @Composable
    private fun getContentClassificationGroup(): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { Injekt.get<KomgaLibraryClassificationManager>() }
        val serverPreferences = remember { Injekt.get<KomgaServerPreferences>() }
        val enabled by manager.enabled.collectAsState()
        val activeServerId by serverPreferences.activeServerId.collectAsState()
        val profiles by remember(serverPreferences) {
            serverPreferences.profilesChanges()
        }.collectAsState(initial = serverPreferences.getProfiles())
        val libraries by remember(activeServerId) {
            manager.classificationsChanges(activeServerId)
        }.collectAsState(initial = manager.getLibraries(activeServerId))
        val activeServer = profiles.firstOrNull { it.id == activeServerId }
        val hasServer = activeServer != null
        val comicCount = libraries.count { it.kind == KomgaLibraryKind.COMIC }
        val bookCount = libraries.count { it.kind == KomgaLibraryKind.BOOK }
        var showEnableConfirmation by remember { mutableStateOf(false) }

        if (showEnableConfirmation) {
            AlertDialog(
                onDismissRequest = { showEnableConfirmation = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            manager.enableClassification()
                            showEnableConfirmation = false
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEnableConfirmation = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(stringResource(MR.strings.komga_library_classification_confirm_title)) },
                text = { Text(stringResource(MR.strings.komga_library_classification_confirm_message)) },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.komga_library_classification_group),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.komga_library_classification_enable),
                ) {
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.komga_library_classification_enable),
                        subtitle = stringResource(
                            if (hasServer) {
                                MR.strings.komga_library_classification_summary
                            } else {
                                MR.strings.komga_library_classification_no_server
                            },
                        ),
                        checked = enabled,
                        enabled = hasServer,
                        onCheckedChanged = { checked ->
                            if (checked) {
                                showEnableConfirmation = true
                            } else {
                                manager.disableClassification()
                            }
                        },
                    )
                },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.komga_library_classification_configure),
                    subtitle = activeServer?.let {
                        stringResource(
                            MR.strings.komga_library_classification_counts,
                            it.name,
                            comicCount,
                            bookCount,
                        )
                    } ?: stringResource(MR.strings.komga_library_classification_no_server),
                    enabled = hasServer,
                    onClick = { navigator.push(KomgaLibraryClassificationScreen()) }.takeIf { hasServer },
                ),
            ),
        )
    }

    @Composable
    private fun getDisplayGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val columnsPref = libraryPreferences.portraitColumns
        val columns by columnsPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SliderPreference(
                    value = columns.coerceIn(LibraryColumnsRange),
                    title = stringResource(MR.strings.pref_library_columns),
                    valueString = libraryColumnsValueString(columns),
                    valueRange = LibraryColumnsRange,
                    onValueChanged = {
                        columnsPref.set(it)
                        libraryPreferences.landscapeColumns.set(it)
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getGlobalUpdateGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current

        val autoUpdateIntervalPref = libraryPreferences.autoUpdateInterval

        val autoUpdateInterval by autoUpdateIntervalPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_library_update),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = autoUpdateIntervalPref,
                    entries = persistentMapOf(
                        0 to stringResource(MR.strings.update_never),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        72 to stringResource(MR.strings.update_72hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_library_update_interval),
                    onValueChanged = {
                        LibraryUpdateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = libraryPreferences.autoUpdateDeviceRestrictions,
                    entries = persistentMapOf(
                        DEVICE_ONLY_ON_WIFI to stringResource(MR.strings.connected_to_wifi),
                        DEVICE_NETWORK_NOT_METERED to stringResource(MR.strings.network_not_metered),
                        DEVICE_CHARGING to stringResource(MR.strings.charging),
                    ),
                    title = stringResource(MR.strings.pref_library_update_restriction),
                    subtitle = stringResource(MR.strings.restrictions),
                    enabled = autoUpdateInterval > 0,
                    onValueChanged = {
                        // Post to event looper to allow the preference to be updated.
                        ContextCompat.getMainExecutor(context).execute { LibraryUpdateJob.setupTask(context) }
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoUpdateMetadata,
                    title = stringResource(MR.strings.pref_library_update_refresh_metadata),
                    subtitle = stringResource(MR.strings.pref_library_update_refresh_metadata_summary),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = libraryPreferences.autoUpdateMangaRestrictions,
                    entries = persistentMapOf(
                        MANGA_HAS_UNREAD to stringResource(MR.strings.pref_update_only_completely_read),
                        MANGA_NON_READ to stringResource(MR.strings.pref_update_only_started),
                        MANGA_NON_COMPLETED to stringResource(MR.strings.pref_update_only_non_completed),
                    ),
                    title = stringResource(MR.strings.pref_library_update_smart_update),
                ),
            ),
        )
    }

    @Composable
    private fun getChapterSettingsGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val displayChapterByNameOrNumberEntries = persistentMapOf(
            Manga.CHAPTER_DISPLAY_NAME to stringResource(MR.strings.show_title),
            Manga.CHAPTER_DISPLAY_NUMBER to stringResource(MR.strings.show_chapter_number),
        )
        val displayChapterByNameOrNumber by libraryPreferences.displayChapterByNameOrNumber.collectAsState()
        val chapterCoverDisplayModeEntries = persistentMapOf(
            Manga.CHAPTER_COVER_DISPLAY_TEXT to stringResource(MR.strings.action_display_chapter_text_only),
            Manga.CHAPTER_COVER_DISPLAY_COVER to stringResource(MR.strings.action_display_chapter_cover_only),
            Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE to
                stringResource(MR.strings.action_display_chapter_cover_and_title),
        )
        val chapterCoverDisplayMode by libraryPreferences.chapterCoverDisplayMode.collectAsState()
        val chapterCoverGridColumnsPref = libraryPreferences.chapterCoverGridColumns
        val chapterCoverGridColumns by chapterCoverGridColumnsPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.chapter_settings),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.displayChapterByNameOrNumber,
                    entries = displayChapterByNameOrNumberEntries,
                    title = stringResource(MR.strings.chapter_title_display_mode),
                    subtitle = displayChapterByNameOrNumberEntries[displayChapterByNameOrNumber],
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.chapterCoverDisplayMode,
                    entries = chapterCoverDisplayModeEntries,
                    title = stringResource(MR.strings.pref_default_chapter_list_style),
                    subtitle = chapterCoverDisplayModeEntries[chapterCoverDisplayMode],
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = chapterCoverGridColumns,
                    title = stringResource(MR.strings.pref_chapter_grid_columns),
                    valueString = stringResource(MR.strings.chapter_grid_columns, chapterCoverGridColumns),
                    valueRange = 2..6,
                    enabled = chapterCoverDisplayMode != Manga.CHAPTER_COVER_DISPLAY_TEXT,
                    onValueChanged = { chapterCoverGridColumnsPref.set(it) },
                ),
            ),
        )
    }

    @Composable
    private fun getBehaviorGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_behavior),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.swipeToStartAction,
                    entries = persistentMapOf(
                        LibraryPreferences.ChapterSwipeAction.Disabled to
                            stringResource(MR.strings.disabled),
                        LibraryPreferences.ChapterSwipeAction.ToggleBookmark to
                            stringResource(MR.strings.action_bookmark),
                        LibraryPreferences.ChapterSwipeAction.ToggleRead to
                            stringResource(MR.strings.action_mark_as_read),
                        LibraryPreferences.ChapterSwipeAction.Download to
                            stringResource(MR.strings.action_download),
                    ),
                    title = stringResource(MR.strings.pref_chapter_swipe_start),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.swipeToEndAction,
                    entries = persistentMapOf(
                        LibraryPreferences.ChapterSwipeAction.Disabled to
                            stringResource(MR.strings.disabled),
                        LibraryPreferences.ChapterSwipeAction.ToggleBookmark to
                            stringResource(MR.strings.action_bookmark),
                        LibraryPreferences.ChapterSwipeAction.ToggleRead to
                            stringResource(MR.strings.action_mark_as_read),
                        LibraryPreferences.ChapterSwipeAction.Download to
                            stringResource(MR.strings.action_download),
                    ),
                    title = stringResource(MR.strings.pref_chapter_swipe_end),
                ),
            ),
        )
    }
}

private val LibraryColumnsRange = 0..10

@Composable
private fun libraryColumnsValueString(columns: Int): String {
    return if (columns > 0) {
        columns.toString()
    } else {
        stringResource(MR.strings.label_auto)
    }
}
