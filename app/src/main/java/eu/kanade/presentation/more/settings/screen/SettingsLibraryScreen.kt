package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import koharia.connection.ConnectionLibrarySettingsAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRegistry
import koharia.connection.EntryOpenMode
import koharia.connection.EntryOpenPreferences
import koharia.source.komga.KomgaConnectionProvider
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
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val connectionRegistry = remember { Injekt.get<ConnectionRegistry>() }
        val activeConnectionId by connectionPreferences.activeConnectionId.collectAsState()
        val profiles by remember(connectionPreferences) {
            connectionPreferences.profilesChanges()
        }.collectAsState(initial = connectionPreferences.getProfiles())
        val activeProvider = profiles
            .firstOrNull { it.id == activeConnectionId }
            ?.let { connectionRegistry.provider(it.providerId) }
        val providerSettings = (activeProvider as? ConnectionLibrarySettingsAdapter)
            ?.connectionLibrarySettings()
            .orEmpty()

        return providerSettings + listOfNotNull(
            getDisplayGroup(libraryPreferences),
            getEntryOpeningGroup(
                isLocal = activeConnectionId == ConnectionPreferences.LOCAL_CONNECTION_ID,
                isKomga = activeProvider?.id == KomgaConnectionProvider.ID,
            ),
            getChapterSettingsGroup(libraryPreferences),
            getGlobalUpdateGroup(libraryPreferences),
            getBehaviorGroup(libraryPreferences),
        )
    }

    @Composable
    private fun getEntryOpeningGroup(isLocal: Boolean, isKomga: Boolean): Preference.PreferenceGroup? {
        if (!isLocal && !isKomga) return null
        val preferences = remember { Injekt.get<EntryOpenPreferences>() }
        val entries = persistentMapOf(
            EntryOpenMode.READER.name to stringResource(MR.strings.entry_open_reader),
            EntryOpenMode.PAGE_PREVIEW.name to stringResource(MR.strings.entry_open_page_preview),
            EntryOpenMode.DETAILS.name to stringResource(MR.strings.entry_open_details),
        )
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.entry_open_group),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = if (isLocal) preferences.localSingleComic else preferences.komgaSingleBook,
                    entries = entries,
                    title = stringResource(
                        if (isLocal) MR.strings.entry_open_local_single else MR.strings.entry_open_komga_book,
                    ),
                ),
            ),
        )
    }

    @Composable
    private fun getDisplayGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val portraitColumns by libraryPreferences.portraitColumns.collectAsState()
        val landscapeColumns by libraryPreferences.landscapeColumns.collectAsState()
        var showColumnsDialog by rememberSaveable { mutableStateOf(false) }
        if (showColumnsDialog) {
            LibraryColumnsDialog(
                portraitColumns = portraitColumns,
                landscapeColumns = landscapeColumns,
                onDismiss = { showColumnsDialog = false },
                onSave = { portrait, landscape ->
                    libraryPreferences.portraitColumns.set(portrait)
                    libraryPreferences.landscapeColumns.set(landscape)
                    showColumnsDialog = false
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_library_columns_dialog_title),
                    subtitle = if (portraitColumns == 0 && landscapeColumns == 0) {
                        stringResource(MR.strings.pref_library_columns_all_auto)
                    } else {
                        stringResource(
                            MR.strings.pref_library_columns_summary,
                            libraryColumnsValueString(portraitColumns),
                            libraryColumnsValueString(landscapeColumns),
                        )
                    },
                    onClick = { showColumnsDialog = true },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.showLibraryReadProgress,
                    title = stringResource(MR.strings.pref_show_library_read_progress),
                    subtitle = stringResource(MR.strings.pref_show_library_read_progress_summary),
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
            Manga.CHAPTER_DISPLAY_FILE_NAME to stringResource(MR.strings.show_original_file_name),
        )
        val displayChapterByNameOrNumber by libraryPreferences.displayChapterByNameOrNumber.collectAsState()
        val chapterCoverDisplayModeEntries = persistentMapOf(
            Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE to
                stringResource(MR.strings.action_display_comfortable_grid),
            Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE to stringResource(MR.strings.action_display_grid),
            Manga.CHAPTER_COVER_DISPLAY_COVER to stringResource(MR.strings.action_display_cover_only_grid),
            Manga.CHAPTER_COVER_DISPLAY_TEXT to stringResource(MR.strings.action_display_list),
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
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.showChapterReadProgress,
                    title = stringResource(MR.strings.pref_show_chapter_read_progress),
                    subtitle = stringResource(MR.strings.pref_show_chapter_read_progress_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.showChapterFileSize,
                    title = stringResource(MR.strings.pref_show_chapter_file_size),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.hideMissingChapters,
                    title = stringResource(MR.strings.pref_hide_missing_chapter_indicators),
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

@Composable
private fun LibraryColumnsDialog(
    portraitColumns: Int,
    landscapeColumns: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var portrait by rememberSaveable { mutableIntStateOf(portraitColumns.coerceIn(LibraryColumnsRange)) }
    var landscape by rememberSaveable { mutableIntStateOf(landscapeColumns.coerceIn(LibraryColumnsRange)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.pref_library_columns_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(MR.strings.pref_library_columns_auto_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LibraryColumnsRow(stringResource(MR.strings.pref_library_columns_portrait), portrait) { portrait = it }
                LibraryColumnsRow(stringResource(MR.strings.pref_library_columns_landscape), landscape) {
                    landscape = it
                }
                TextButton(onClick = {
                    portrait = 0
                    landscape = 0
                }, enabled = portrait != 0 || landscape != 0) {
                    Text(stringResource(MR.strings.pref_library_columns_all_auto))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(portrait, landscape) }) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun LibraryColumnsRow(title: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onChange(value - 1) }, enabled = value > LibraryColumnsRange.first) {
                Icon(Icons.Outlined.Remove, stringResource(MR.strings.pref_library_columns_decrease))
            }
            Text(libraryColumnsValueString(value), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onChange(value + 1) }, enabled = value < LibraryColumnsRange.last) {
                Icon(Icons.Outlined.Add, stringResource(MR.strings.pref_library_columns_increase))
            }
        }
    }
}
