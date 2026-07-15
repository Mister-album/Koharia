package koharia.epub.settings

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import kotlin.math.roundToInt

class EpubLayoutPreferences(
    preferenceStore: PreferenceStore,
) {

    val readingMode: Preference<ReadingMode> =
        preferenceStore.getEnum("epub_layout_reading_mode", ReadingMode.PAGINATED)

    val pageDirection: Preference<PageDirection> =
        preferenceStore.getEnum("epub_layout_page_direction", PageDirection.LEFT_TO_RIGHT)

    val theme: Preference<Theme> =
        preferenceStore.getEnum("epub_layout_theme", Theme.LIGHT)

    val customBackgroundColor: Preference<Int> =
        preferenceStore.getInt("epub_layout_custom_background_color", DEFAULT_CUSTOM_BACKGROUND_COLOR)

    val customBackgroundColors: Preference<String> =
        preferenceStore.getString("epub_layout_custom_background_colors", "")

    val backgroundColors: Preference<String> =
        preferenceStore.getString("epub_layout_background_colors", "")

    val fontSize: Preference<Float> =
        preferenceStore.getFloat("epub_layout_font_size", 1.0f)

    val lineHeight: Preference<Float> =
        preferenceStore.getFloat("epub_layout_line_height", 1.2f)

    val paragraphSpacing: Preference<Float> =
        preferenceStore.getFloat("epub_layout_paragraph_spacing", 0.0f)

    val paragraphIndent: Preference<Float> =
        preferenceStore.getFloat("epub_layout_paragraph_indent", 2.0f)

    val pageMargins: Preference<Float> =
        preferenceStore.getFloat("epub_layout_page_margins", 1.0f)

    val verticalMargins: Preference<Float> =
        preferenceStore.getFloat("epub_layout_vertical_margins", 1.0f)

    val spacingMode: Preference<SpacingMode> =
        preferenceStore.getEnum("epub_layout_spacing_mode", SpacingMode.CUSTOM)

    val fontFamily: Preference<FontFamily> =
        preferenceStore.getEnum("epub_layout_font_family", FontFamily.ORIGINAL)

    val publisherStyles: Preference<Boolean> =
        preferenceStore.getBoolean("epub_layout_publisher_styles", true)

    val readWithVolumeKeys: Preference<Boolean> =
        preferenceStore.getBoolean("epub_layout_volume_keys", true)

    val readWithVolumeKeysInverted: Preference<Boolean> =
        preferenceStore.getBoolean("epub_layout_volume_keys_inverted", false)

    enum class ReadingMode {
        PAGINATED,
        SCROLL,
    }

    enum class PageDirection {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT,
    }

    enum class Theme {
        LIGHT,
        DARK,
        SEPIA,
        MINT,
        BLUE,
        PINK,
        GRAY,
        CUSTOM,
    }

    enum class FontFamily {
        ORIGINAL,
        SERIF,
        SANS_SERIF,
        MONOSPACE,
        OPEN_DYSLEXIC,
    }

    enum class SpacingMode(
        val lineHeight: Float?,
        val paragraphSpacing: Float?,
        val paragraphIndent: Float?,
        val verticalMargins: Float?,
        val horizontalMargins: Float?,
    ) {
        COMPACT(1.55f, 0.0f, 2.0f, 0.85f, 0.9f),
        STANDARD(1.7f, 0.05f, 2.0f, 1.0f, 1.0f),
        RELAXED(1.85f, 0.15f, 2.0f, 1.1f, 1.1f),
        NONE(1.2f, 0.0f, 2.0f, 0.0f, 0.0f),
        CUSTOM(null, null, null, null, null),
    }

    fun applySpacingMode(mode: SpacingMode) {
        spacingMode.set(mode)
        publisherStyles.set(false)
        if (mode == SpacingMode.CUSTOM) return
        lineHeight.set(checkNotNull(mode.lineHeight))
        paragraphSpacing.set(checkNotNull(mode.paragraphSpacing))
        paragraphIndent.set(checkNotNull(mode.paragraphIndent))
        verticalMargins.set(checkNotNull(mode.verticalMargins))
        pageMargins.set(checkNotNull(mode.horizontalMargins))
    }

    companion object {
        const val BASE_FONT_SIZE = 16
        const val MIN_FONT_SIZE = 13
        const val MAX_FONT_SIZE = 32
        const val MAX_CUSTOM_BACKGROUND_COLORS = 5
        const val MAX_BACKGROUND_COLORS = 12
        val DEFAULT_CUSTOM_BACKGROUND_COLOR = 0xFFF5E6CC.toInt()
        val DEFAULT_BACKGROUND_COLORS = listOf(
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFFFBF0D9.toInt(),
            0xFFC4EDC8.toInt(),
            0xFFE0F0FC.toInt(),
            0xFFFBE4EE.toInt(),
            0xFFF1F3F5.toInt(),
        )

        val FONT_SIZE_VALUES = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)
        const val VERTICAL_MARGIN_BASE_DP = 12f
        val LINE_HEIGHT_VALUES = listOf(1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)
        val PARAGRAPH_SPACING_VALUES = listOf(0.0f, 0.3f, 0.6f, 1.0f, 1.5f, 2.0f)
        val PARAGRAPH_INDENT_VALUES = listOf(0.0f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
        val VERTICAL_MARGIN_VALUES = listOf(0.0f, 0.5f, 1.0f, 1.3f, 1.6f, 2.0f)
        val PAGE_MARGIN_VALUES = listOf(0.0f, 0.5f, 1.0f, 1.4f, 2.0f, 3.0f, 4.0f)

        fun fontSizeFromScale(scale: Float): Int {
            return (scale * BASE_FONT_SIZE).roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        }

        fun scaleFromFontSize(fontSize: Int): Float {
            return fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE).toFloat() / BASE_FONT_SIZE
        }

        fun decodeCustomBackgroundColors(serialized: String): List<Int> {
            return serialized
                .split(',')
                .mapNotNull { value -> value.toLongOrNull(16)?.toInt() }
                .distinct()
                .take(MAX_CUSTOM_BACKGROUND_COLORS)
        }

        fun encodeCustomBackgroundColors(colors: List<Int>): String {
            return colors
                .distinct()
                .take(MAX_CUSTOM_BACKGROUND_COLORS)
                .joinToString(",") { color -> "%08X".format(color) }
        }

        fun initialBackgroundColors(legacyCustomColors: String): List<Int> {
            return (DEFAULT_BACKGROUND_COLORS + decodeCustomBackgroundColors(legacyCustomColors))
                .distinct()
                .take(MAX_BACKGROUND_COLORS)
        }

        fun decodeBackgroundColors(serialized: String): List<Int> {
            return serialized
                .split(',')
                .mapNotNull { value -> value.toLongOrNull(16)?.toInt() }
                .distinct()
                .take(MAX_BACKGROUND_COLORS)
        }

        fun encodeBackgroundColors(colors: List<Int>): String {
            return colors
                .distinct()
                .take(MAX_BACKGROUND_COLORS)
                .joinToString(",") { color -> "%08X".format(color) }
        }
    }
}
