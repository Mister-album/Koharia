package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubReaderPreferences
import koharia.epub.settings.EpubBackgroundSettingsPreference
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.LocalConfigMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat
import kotlin.math.roundToInt

object SettingsComicReaderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_comic_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        return SettingsReaderScreen.comicPreferences(readerPref)
    }
}

object SettingsBookReaderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_book_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        val epubReaderPref = remember { Injekt.get<EpubReaderPreferences>() }
        val epubLayoutPreferences = remember { Injekt.get<EpubLayoutPreferences>() }
        val serverPreferences = remember { Injekt.get<KomgaServerPreferences>() }
        val localConfigMode by serverPreferences.localConfigMode.collectAsState()
        val activeServerId by serverPreferences.activeServerId.collectAsState()

        val profileName = remember(activeServerId) {
            serverPreferences.getProfiles()
                .firstOrNull { it.id == activeServerId }
                ?.name
                ?: activeServerId.toString()
        }
        val scopeSummary = when {
            activeServerId == KomgaServerPreferences.NO_ACTIVE_SERVER ->
                stringResource(MR.strings.komga_scoped_settings_disabled_summary)
            localConfigMode == LocalConfigMode.Shared ->
                stringResource(MR.strings.pref_server_scope_shared)
            else ->
                stringResource(MR.strings.pref_server_scope_server, profileName)
        }

        return SettingsReaderScreen.bookPreferences(
            readerPreferences = readerPref,
            epubReaderPreferences = epubReaderPref,
            epubLayoutPreferences = epubLayoutPreferences,
            scopeSummary = scopeSummary,
        )
    }
}

object SettingsReaderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        val epubReaderPref = remember { Injekt.get<EpubReaderPreferences>() }
        val epubLayoutPreferences = remember { Injekt.get<EpubLayoutPreferences>() }

        return comicPreferences(readerPref) + bookPreferences(
            readerPreferences = readerPref,
            epubReaderPreferences = epubReaderPref,
            epubLayoutPreferences = epubLayoutPreferences,
            scopeSummary = stringResource(MR.strings.pref_server_scope_shared),
        )
    }

    @Composable
    internal fun comicPreferences(readerPreferences: ReaderPreferences): List<Preference> {
        return listOf(
            Preference.PreferenceItem.ListPreference(
                preference = readerPreferences.defaultReadingMode,
                entries = ReadingMode.entries.drop(1)
                    .associate { it.flagValue to stringResource(it.stringRes) }
                    .toImmutableMap(),
                title = stringResource(MR.strings.pref_viewer_type),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = readerPreferences.doubleTapAnimSpeed,
                entries = persistentMapOf(
                    1 to stringResource(MR.strings.double_tap_anim_speed_0),
                    500 to stringResource(MR.strings.double_tap_anim_speed_normal),
                    250 to stringResource(MR.strings.double_tap_anim_speed_fast),
                ),
                title = stringResource(MR.strings.pref_double_tap_anim_speed),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPreferences.showReadingMode,
                title = stringResource(MR.strings.pref_show_reading_mode),
                subtitle = stringResource(MR.strings.pref_show_reading_mode_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPreferences.showNavigationOverlayOnStart,
                title = stringResource(MR.strings.pref_show_navigation_mode),
                subtitle = stringResource(MR.strings.pref_show_navigation_mode_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPreferences.pageTransitions,
                title = stringResource(MR.strings.pref_page_transitions),
            ),
            getDisplayGroup(readerPreferences = readerPreferences),
            getEInkGroup(readerPreferences = readerPreferences),
            getReadingGroup(readerPreferences = readerPreferences),
            getPagedGroup(readerPreferences = readerPreferences),
            getWebtoonGroup(readerPreferences = readerPreferences),
            getNavigationGroup(readerPreferences = readerPreferences),
            getActionsGroup(readerPreferences = readerPreferences),
        )
    }

    @Composable
    internal fun bookPreferences(
        readerPreferences: ReaderPreferences,
        epubReaderPreferences: EpubReaderPreferences,
        epubLayoutPreferences: EpubLayoutPreferences,
        scopeSummary: String,
    ): List<Preference> {
        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_server_scope),
                subtitle = scopeSummary,
            ),
            getEpubReaderGroup(epubReaderPreferences),
            getEpubReadingModeGroup(readerPreferences, epubLayoutPreferences),
            getEpubTypographyGroup(readerPreferences, epubLayoutPreferences),
            getEpubNavigationGroup(readerPreferences, epubLayoutPreferences),
            getEpubDisplayGroup(readerPreferences),
            getEpubFilterGroup(readerPreferences),
        )
    }

    @Composable
    private fun getEpubReadingModeGroup(
        readerPreferences: ReaderPreferences,
        epubLayoutPreferences: EpubLayoutPreferences,
    ): Preference.PreferenceGroup {
        val readingMode by epubLayoutPreferences.readingMode.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reading_mode),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = epubLayoutPreferences.readingMode,
                    entries = persistentMapOf(
                        EpubLayoutPreferences.ReadingMode.PAGINATED to
                            stringResource(MR.strings.pref_epub_layout_paginated),
                        EpubLayoutPreferences.ReadingMode.SCROLL to
                            stringResource(MR.strings.pref_epub_layout_scrolled),
                    ),
                    title = stringResource(MR.strings.pref_epub_layout_mode),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = epubLayoutPreferences.pageDirection,
                    entries = persistentMapOf(
                        EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT to
                            stringResource(MR.strings.left_to_right_viewer),
                        EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT to
                            stringResource(MR.strings.right_to_left_viewer),
                    ),
                    title = stringResource(MR.strings.pref_epub_page_direction),
                    enabled = readingMode == EpubLayoutPreferences.ReadingMode.PAGINATED,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.defaultOrientationType,
                    entries = ReaderOrientation.entries
                        .filterNot { it == ReaderOrientation.FREE }
                        .associate { it.flagValue to stringResource(it.stringRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_rotation_type),
                ),
            ),
        )
    }

    @Composable
    private fun getEpubTypographyGroup(
        readerPreferences: ReaderPreferences,
        epubLayoutPreferences: EpubLayoutPreferences,
    ): Preference.PreferenceGroup {
        val fontSizeScale by epubLayoutPreferences.fontSize.collectAsState()
        val fontSize = EpubLayoutPreferences.fontSizeFromScale(fontSizeScale)
        val spacingMode by epubLayoutPreferences.spacingMode.collectAsState()
        val publisherStyles by epubLayoutPreferences.publisherStyles.collectAsState()
        val lineHeight by epubLayoutPreferences.lineHeight.collectAsState()
        val paragraphSpacing by epubLayoutPreferences.paragraphSpacing.collectAsState()
        val paragraphIndent by epubLayoutPreferences.paragraphIndent.collectAsState()
        val verticalMargins by epubLayoutPreferences.verticalMargins.collectAsState()
        val horizontalMargins by epubLayoutPreferences.pageMargins.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_epub_typography_and_appearance),
            preferenceItems = buildList<Preference.PreferenceItem<out Any, out Any>> {
                add(
                    Preference.PreferenceItem.SliderPreference(
                        value = fontSize,
                        valueRange = EpubLayoutPreferences.MIN_FONT_SIZE..EpubLayoutPreferences.MAX_FONT_SIZE,
                        title = stringResource(MR.strings.pref_epub_font_size),
                        valueString = fontSize.toString(),
                        onValueChanged = {
                            epubLayoutPreferences.fontSize.set(EpubLayoutPreferences.scaleFromFontSize(it))
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = epubLayoutPreferences.fontFamily,
                        entries = persistentMapOf(
                            EpubLayoutPreferences.FontFamily.ORIGINAL to
                                stringResource(MR.strings.pref_epub_font_original),
                            EpubLayoutPreferences.FontFamily.SERIF to
                                stringResource(MR.strings.pref_epub_font_serif),
                            EpubLayoutPreferences.FontFamily.SANS_SERIF to
                                stringResource(MR.strings.pref_epub_font_sans_serif),
                            EpubLayoutPreferences.FontFamily.MONOSPACE to
                                stringResource(MR.strings.pref_epub_font_monospace),
                            EpubLayoutPreferences.FontFamily.OPEN_DYSLEXIC to
                                stringResource(MR.strings.pref_epub_font_open_dyslexic),
                        ),
                        title = stringResource(MR.strings.pref_epub_font_family),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = epubLayoutPreferences.publisherStyles,
                        title = stringResource(MR.strings.pref_epub_publisher_styles),
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = epubLayoutPreferences.spacingMode,
                        entries = persistentMapOf(
                            EpubLayoutPreferences.SpacingMode.COMPACT to
                                stringResource(MR.strings.pref_epub_spacing_compact),
                            EpubLayoutPreferences.SpacingMode.STANDARD to
                                stringResource(MR.strings.pref_epub_spacing_standard),
                            EpubLayoutPreferences.SpacingMode.RELAXED to
                                stringResource(MR.strings.pref_epub_spacing_relaxed),
                            EpubLayoutPreferences.SpacingMode.NONE to
                                stringResource(MR.strings.pref_epub_spacing_none),
                            EpubLayoutPreferences.SpacingMode.CUSTOM to
                                stringResource(MR.strings.pref_epub_spacing_custom),
                        ),
                        title = stringResource(MR.strings.pref_epub_spacing),
                        enabled = !publisherStyles,
                        onValueChanged = { mode ->
                            epubLayoutPreferences.applySpacingMode(mode)
                            true
                        },
                    ),
                )
                if (spacingMode == EpubLayoutPreferences.SpacingMode.CUSTOM && !publisherStyles) {
                    add(
                        epubLayoutSliderPreference(
                            value = lineHeight,
                            valueRange = 100..200 step 10,
                            title = stringResource(MR.strings.pref_epub_line_height),
                            onValueChanged = epubLayoutPreferences.lineHeight::set,
                        ),
                    )
                    add(
                        epubLayoutSliderPreference(
                            value = paragraphSpacing,
                            valueRange = 0..200 step 10,
                            title = stringResource(MR.strings.pref_epub_paragraph_spacing),
                            onValueChanged = epubLayoutPreferences.paragraphSpacing::set,
                        ),
                    )
                    add(
                        epubLayoutSliderPreference(
                            value = paragraphIndent,
                            valueRange = 0..300 step 20,
                            title = stringResource(MR.strings.pref_epub_paragraph_indent),
                            onValueChanged = epubLayoutPreferences.paragraphIndent::set,
                        ),
                    )
                    add(
                        epubLayoutSliderPreference(
                            value = verticalMargins,
                            valueRange = 0..200 step 10,
                            title = stringResource(MR.strings.pref_epub_vertical_margins),
                            onValueChanged = epubLayoutPreferences.verticalMargins::set,
                        ),
                    )
                    add(
                        epubLayoutSliderPreference(
                            value = horizontalMargins,
                            valueRange = 0..400 step 10,
                            title = stringResource(MR.strings.pref_epub_horizontal_margins),
                            onValueChanged = epubLayoutPreferences.pageMargins::set,
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_epub_theme),
                    ) {
                        EpubBackgroundSettingsPreference(epubLayoutPreferences, readerPreferences)
                    },
                )
            }.toImmutableList(),
        )
    }

    private fun epubLayoutSliderPreference(
        value: Float,
        valueRange: IntProgression,
        title: String,
        onValueChanged: (Float) -> Unit,
    ): Preference.PreferenceItem.SliderPreference {
        return Preference.PreferenceItem.SliderPreference(
            value = (value * 100).roundToInt().coerceIn(valueRange.first, valueRange.last),
            valueRange = valueRange,
            steps = (valueRange.count() - 2).coerceAtLeast(0),
            title = title,
            valueString = value.toString(),
            onValueChanged = { onValueChanged(it / 100f) },
        )
    }

    @Composable
    private fun getEpubNavigationGroup(
        readerPreferences: ReaderPreferences,
        epubLayoutPreferences: EpubLayoutPreferences,
    ): Preference.PreferenceGroup {
        val readingMode by epubLayoutPreferences.readingMode.collectAsState()
        val navigationModePreference = when (readingMode) {
            EpubLayoutPreferences.ReadingMode.PAGINATED -> readerPreferences.navigationModePager
            EpubLayoutPreferences.ReadingMode.SCROLL -> readerPreferences.navigationModeWebtoon
        }
        val invertModePreference = when (readingMode) {
            EpubLayoutPreferences.ReadingMode.PAGINATED -> readerPreferences.pagerNavInverted
            EpubLayoutPreferences.ReadingMode.SCROLL -> readerPreferences.webtoonNavInverted
        }
        val navigationMode by navigationModePreference.collectAsState()
        val readWithVolumeKeys by readerPreferences.readWithVolumeKeys.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithVolumeKeys,
                    title = stringResource(MR.strings.pref_read_with_volume_keys),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithVolumeKeysInverted,
                    title = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
                    enabled = readWithVolumeKeys,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = navigationModePreference,
                    entries = ReaderPreferences.TapZones
                        .mapIndexed { index, entry -> index to stringResource(entry) }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_viewer_nav),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = invertModePreference,
                    entries = ReaderPreferences.TappingInvertMode.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                    enabled = navigationMode != 5,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.showNavigationOverlayOnStart,
                    title = stringResource(MR.strings.pref_show_navigation_mode),
                    subtitle = stringResource(MR.strings.pref_show_navigation_mode_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getEpubDisplayGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val fullscreen by readerPreferences.fullscreen.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.showPageNumber,
                    title = stringResource(MR.strings.epub_reader_show_reading_progress),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.fullscreen,
                    title = stringResource(MR.strings.pref_fullscreen),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.drawUnderCutout,
                    title = stringResource(MR.strings.pref_cutout_short),
                    enabled = LocalView.current.hasDisplayCutout() && fullscreen,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.keepScreenOn,
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
            ),
        )
    }

    @Composable
    private fun getEpubFilterGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val colorFilterEnabled by readerPreferences.colorFilter.collectAsState()
        val colorValue by readerPreferences.colorFilterValue.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.custom_filter),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.colorFilter,
                    title = stringResource(MR.strings.pref_custom_color_filter),
                ),
                epubColorChannelPreference(
                    colorValue = colorValue,
                    shift = 16,
                    title = stringResource(MR.strings.color_filter_r_value),
                    enabled = colorFilterEnabled,
                    readerPreferences = readerPreferences,
                ),
                epubColorChannelPreference(
                    colorValue = colorValue,
                    shift = 8,
                    title = stringResource(MR.strings.color_filter_g_value),
                    enabled = colorFilterEnabled,
                    readerPreferences = readerPreferences,
                ),
                epubColorChannelPreference(
                    colorValue = colorValue,
                    shift = 0,
                    title = stringResource(MR.strings.color_filter_b_value),
                    enabled = colorFilterEnabled,
                    readerPreferences = readerPreferences,
                ),
                epubColorChannelPreference(
                    colorValue = colorValue,
                    shift = 24,
                    title = stringResource(MR.strings.color_filter_a_value),
                    enabled = colorFilterEnabled,
                    readerPreferences = readerPreferences,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.colorFilterMode,
                    entries = ReaderPreferences.ColorFilterMode
                        .mapIndexed { index, mode -> index to stringResource(mode.first) }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_color_filter_mode),
                    enabled = colorFilterEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.grayscale,
                    title = stringResource(MR.strings.pref_grayscale),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.invertedColors,
                    title = stringResource(MR.strings.pref_inverted_colors),
                ),
            ),
        )
    }

    private fun epubColorChannelPreference(
        colorValue: Int,
        shift: Int,
        title: String,
        enabled: Boolean,
        readerPreferences: ReaderPreferences,
    ): Preference.PreferenceItem.SliderPreference {
        val channelValue = (colorValue ushr shift) and 0xFF
        return Preference.PreferenceItem.SliderPreference(
            value = channelValue,
            valueRange = 0..255,
            steps = 0,
            title = title,
            enabled = enabled,
            onValueChanged = { value ->
                val mask = 0xFF shl shift
                val currentColor = readerPreferences.colorFilterValue.get()
                readerPreferences.colorFilterValue.set(
                    (currentColor and mask.inv()) or ((value and 0xFF) shl shift),
                )
            },
        )
    }

    @Composable
    private fun getDisplayGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val fullscreenPref = readerPreferences.fullscreen
        val fullscreen by fullscreenPref.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.defaultOrientationType,
                    entries = ReaderOrientation.entries.drop(1)
                        .associate { it.flagValue to stringResource(it.stringRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_rotation_type),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.readerTheme,
                    entries = persistentMapOf(
                        1 to stringResource(MR.strings.black_background),
                        2 to stringResource(MR.strings.gray_background),
                        0 to stringResource(MR.strings.white_background),
                        3 to stringResource(MR.strings.automatic_background),
                    ),
                    title = stringResource(MR.strings.pref_reader_theme),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = fullscreenPref,
                    title = stringResource(MR.strings.pref_fullscreen),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.drawUnderCutout,
                    title = stringResource(MR.strings.pref_cutout_short),
                    enabled = LocalView.current.hasDisplayCutout() && fullscreen,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.keepScreenOn,
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.showPageNumber,
                    title = stringResource(MR.strings.pref_show_page_number),
                ),
            ),
        )
    }

    @Composable
    private fun getEInkGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val flashPageState by readerPreferences.flashOnPageChange.collectAsState()

        val flashMillisPref = readerPreferences.flashDurationMillis
        val flashMillis by flashMillisPref.collectAsState()

        val flashIntervalPref = readerPreferences.flashPageInterval
        val flashInterval by flashIntervalPref.collectAsState()

        val flashColorPref = readerPreferences.flashColor

        return Preference.PreferenceGroup(
            title = "E-Ink",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.flashOnPageChange,
                    title = stringResource(MR.strings.pref_flash_page),
                    subtitle = stringResource(MR.strings.pref_flash_page_summ),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
                    valueRange = 1..15,
                    title = stringResource(MR.strings.pref_flash_duration),
                    valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
                    enabled = flashPageState,
                    onValueChanged = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashInterval,
                    valueRange = 1..10,
                    title = stringResource(MR.strings.pref_flash_page_interval),
                    valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
                    enabled = flashPageState,
                    onValueChanged = { flashIntervalPref.set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = flashColorPref,
                    entries = persistentMapOf(
                        ReaderPreferences.FlashColor.BLACK to stringResource(MR.strings.pref_flash_style_black),
                        ReaderPreferences.FlashColor.WHITE to stringResource(MR.strings.pref_flash_style_white),
                        ReaderPreferences.FlashColor.WHITE_BLACK
                            to stringResource(MR.strings.pref_flash_style_white_black),
                    ),
                    title = stringResource(MR.strings.pref_flash_with),
                    enabled = flashPageState,
                ),
            ),
        )
    }

    @Composable
    private fun getReadingGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reading),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipRead,
                    title = stringResource(MR.strings.pref_skip_read_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipFiltered,
                    title = stringResource(MR.strings.pref_skip_filtered_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.alwaysShowChapterTransition,
                    title = stringResource(MR.strings.pref_always_show_chapter_transition),
                ),
            ),
        )
    }

    @Composable
    private fun getPagedGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val navModePref = readerPreferences.navigationModePager
        val imageScaleTypePref = readerPreferences.imageScaleType
        val dualPageSplitPref = readerPreferences.dualPageSplitPaged
        val rotateToFitPref = readerPreferences.dualPageRotateToFit

        val navMode by navModePref.collectAsState()
        val imageScaleType by imageScaleTypePref.collectAsState()
        val dualPageSplit by dualPageSplitPref.collectAsState()
        val rotateToFit by rotateToFitPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pager_viewer),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = navModePref,
                    entries = ReaderPreferences.TapZones
                        .mapIndexed { index, it -> index to stringResource(it) }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_viewer_nav),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.pagerNavInverted,
                    entries = persistentListOf(
                        ReaderPreferences.TappingInvertMode.NONE,
                        ReaderPreferences.TappingInvertMode.HORIZONTAL,
                        ReaderPreferences.TappingInvertMode.VERTICAL,
                        ReaderPreferences.TappingInvertMode.BOTH,
                    )
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = imageScaleTypePref,
                    entries = ReaderPreferences.ImageScaleType
                        .mapIndexed { index, it -> index + 1 to stringResource(it) }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_image_scale_type),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.zoomStart,
                    entries = ReaderPreferences.ZoomStart
                        .mapIndexed { index, it -> index + 1 to stringResource(it) }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_zoom_start),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.cropBorders,
                    title = stringResource(MR.strings.pref_crop_borders),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.landscapeZoom,
                    title = stringResource(MR.strings.pref_landscape_zoom),
                    enabled = imageScaleType == 1,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.navigateToPan,
                    title = stringResource(MR.strings.pref_navigate_pan),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = dualPageSplitPref,
                    title = stringResource(MR.strings.pref_dual_page_split),
                    onValueChanged = {
                        rotateToFitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageInvertPaged,
                    title = stringResource(MR.strings.pref_dual_page_invert),
                    subtitle = stringResource(MR.strings.pref_dual_page_invert_summary),
                    enabled = dualPageSplit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rotateToFitPref,
                    title = stringResource(MR.strings.pref_page_rotate),
                    onValueChanged = {
                        dualPageSplitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageRotateToFitInvert,
                    title = stringResource(MR.strings.pref_page_rotate_invert),
                    enabled = rotateToFit,
                ),
            ),
        )
    }

    @Composable
    private fun getWebtoonGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val numberFormat = remember { NumberFormat.getPercentInstance() }

        val navModePref = readerPreferences.navigationModeWebtoon
        val dualPageSplitPref = readerPreferences.dualPageSplitWebtoon
        val rotateToFitPref = readerPreferences.dualPageRotateToFitWebtoon
        val webtoonSidePaddingPref = readerPreferences.webtoonSidePadding

        val navMode by navModePref.collectAsState()
        val dualPageSplit by dualPageSplitPref.collectAsState()
        val rotateToFit by rotateToFitPref.collectAsState()
        val webtoonSidePadding by webtoonSidePaddingPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.webtoon_viewer),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = navModePref,
                    entries = ReaderPreferences.TapZones
                        .mapIndexed { index, it -> index to stringResource(it) }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_viewer_nav),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.webtoonNavInverted,
                    entries = persistentListOf(
                        ReaderPreferences.TappingInvertMode.NONE,
                        ReaderPreferences.TappingInvertMode.HORIZONTAL,
                        ReaderPreferences.TappingInvertMode.VERTICAL,
                        ReaderPreferences.TappingInvertMode.BOTH,
                    )
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = webtoonSidePadding,
                    valueRange = ReaderPreferences.let {
                        it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX
                    },
                    title = stringResource(MR.strings.pref_webtoon_side_padding),
                    valueString = numberFormat.format(webtoonSidePadding / 100f),
                    onValueChanged = { webtoonSidePaddingPref.set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.readerHideThreshold,
                    entries = persistentMapOf(
                        ReaderPreferences.ReaderHideThreshold.HIGHEST to stringResource(MR.strings.pref_highest),
                        ReaderPreferences.ReaderHideThreshold.HIGH to stringResource(MR.strings.pref_high),
                        ReaderPreferences.ReaderHideThreshold.LOW to stringResource(MR.strings.pref_low),
                        ReaderPreferences.ReaderHideThreshold.LOWEST to stringResource(MR.strings.pref_lowest),
                    ),
                    title = stringResource(MR.strings.pref_hide_threshold),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.cropBordersWebtoon,
                    title = stringResource(MR.strings.pref_crop_borders),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = dualPageSplitPref,
                    title = stringResource(MR.strings.pref_dual_page_split),
                    onValueChanged = {
                        rotateToFitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageInvertWebtoon,
                    title = stringResource(MR.strings.pref_dual_page_invert),
                    subtitle = stringResource(MR.strings.pref_dual_page_invert_summary),
                    enabled = dualPageSplit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rotateToFitPref,
                    title = stringResource(MR.strings.pref_page_rotate),
                    onValueChanged = {
                        dualPageSplitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageRotateToFitInvertWebtoon,
                    title = stringResource(MR.strings.pref_page_rotate_invert),
                    enabled = rotateToFit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.webtoonDoubleTapZoomEnabled,
                    title = stringResource(MR.strings.pref_double_tap_zoom),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.webtoonDisableZoomOut,
                    title = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
                ),
            ),
        )
    }

    @Composable
    private fun getNavigationGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val readWithVolumeKeysPref = readerPreferences.readWithVolumeKeys
        val readWithVolumeKeys by readWithVolumeKeysPref.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readWithVolumeKeysPref,
                    title = stringResource(MR.strings.pref_read_with_volume_keys),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithVolumeKeysInverted,
                    title = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
                    enabled = readWithVolumeKeys,
                ),
            ),
        )
    }

    @Composable
    private fun getActionsGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_actions),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithLongTap,
                    title = stringResource(MR.strings.pref_read_with_long_tap),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.folderPerManga,
                    title = stringResource(MR.strings.pref_create_folder_per_manga),
                    subtitle = stringResource(MR.strings.pref_create_folder_per_manga_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getEpubReaderGroup(epubReaderPreferences: EpubReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_epub_reader),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = epubReaderPreferences.preferLocalFile,
                    title = stringResource(MR.strings.pref_prefer_local_epub_file),
                    subtitle = stringResource(MR.strings.pref_prefer_local_epub_file_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = epubReaderPreferences.syncProgressionToKomga,
                    title = stringResource(MR.strings.pref_sync_epub_progression_komga),
                    subtitle = stringResource(MR.strings.pref_sync_epub_progression_komga_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = epubReaderPreferences.completionThresholdPercent,
                    entries = persistentMapOf(
                        90 to "90%",
                        95 to "95%",
                        98 to "98%",
                        100 to "100%",
                    ),
                    title = stringResource(MR.strings.pref_epub_completion_threshold),
                    subtitle = stringResource(MR.strings.pref_epub_completion_threshold_summary),
                ),
            ),
        )
    }

}
