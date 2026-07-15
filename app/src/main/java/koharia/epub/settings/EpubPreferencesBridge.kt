package koharia.epub.settings

import androidx.core.graphics.ColorUtils
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme

@OptIn(ExperimentalReadiumApi::class)
class EpubPreferencesBridge {

    fun toReadiumPreferences(preferences: EpubLayoutPreferences): EpubPreferences {
        return toReadiumPreferences(
            readingMode = preferences.readingMode.get(),
            pageDirection = preferences.pageDirection.get(),
            theme = preferences.theme.get(),
            fontSize = preferences.fontSize.get(),
            lineHeight = preferences.lineHeight.get(),
            paragraphSpacing = preferences.paragraphSpacing.get(),
            paragraphIndent = preferences.paragraphIndent.get(),
            pageMargins = preferences.pageMargins.get(),
            fontFamily = preferences.fontFamily.get(),
            publisherStyles = preferences.publisherStyles.get(),
            customBackgroundColor = preferences.customBackgroundColor.get(),
        )
    }

    fun toReadiumPreferences(
        readingMode: EpubLayoutPreferences.ReadingMode,
        pageDirection: EpubLayoutPreferences.PageDirection,
        theme: EpubLayoutPreferences.Theme,
        fontSize: Float,
        lineHeight: Float,
        paragraphSpacing: Float,
        paragraphIndent: Float,
        pageMargins: Float,
        fontFamily: EpubLayoutPreferences.FontFamily,
        publisherStyles: Boolean,
        customBackgroundColor: Int = EpubLayoutPreferences.DEFAULT_CUSTOM_BACKGROUND_COLOR,
    ): EpubPreferences {
        return EpubPreferences(
            scroll = readingMode == EpubLayoutPreferences.ReadingMode.SCROLL,
            readingProgression = pageDirection.toReadiumReadingProgression(),
            theme = theme.toReadiumTheme(customBackgroundColor),
            backgroundColor = ReadiumColor(theme.backgroundColor(customBackgroundColor)),
            textColor = ReadiumColor(theme.textColor(customBackgroundColor)),
            fontSize = fontSize.toDouble(),
            lineHeight = lineHeight.toDouble(),
            paragraphSpacing = paragraphSpacing.toDouble(),
            paragraphIndent = paragraphIndent.toDouble(),
            pageMargins = pageMargins.toDouble(),
            fontFamily = fontFamily.toReadiumFontFamily(),
            publisherStyles = publisherStyles,
        )
    }

    private fun EpubLayoutPreferences.Theme.toReadiumTheme(customBackgroundColor: Int): ReadiumTheme {
        return when (this) {
            EpubLayoutPreferences.Theme.LIGHT -> ReadiumTheme.LIGHT
            EpubLayoutPreferences.Theme.DARK -> ReadiumTheme.DARK
            EpubLayoutPreferences.Theme.SEPIA -> ReadiumTheme.SEPIA
            EpubLayoutPreferences.Theme.MINT,
            EpubLayoutPreferences.Theme.BLUE,
            EpubLayoutPreferences.Theme.PINK,
            EpubLayoutPreferences.Theme.GRAY,
            -> ReadiumTheme.LIGHT
            EpubLayoutPreferences.Theme.CUSTOM -> if (customBackgroundColor.isDark()) {
                ReadiumTheme.DARK
            } else {
                ReadiumTheme.LIGHT
            }
        }
    }

    private fun EpubLayoutPreferences.Theme.backgroundColor(customBackgroundColor: Int): Int {
        return when (this) {
            EpubLayoutPreferences.Theme.LIGHT -> 0xFFFFFFFF.toInt()
            EpubLayoutPreferences.Theme.DARK -> 0xFF000000.toInt()
            EpubLayoutPreferences.Theme.SEPIA -> 0xFFFAF4E8.toInt()
            EpubLayoutPreferences.Theme.MINT -> 0xFFC4EDC8.toInt()
            EpubLayoutPreferences.Theme.BLUE -> 0xFFE0F0FC.toInt()
            EpubLayoutPreferences.Theme.PINK -> 0xFFFBE4EE.toInt()
            EpubLayoutPreferences.Theme.GRAY -> 0xFFF1F3F5.toInt()
            EpubLayoutPreferences.Theme.CUSTOM -> customBackgroundColor
        }
    }

    private fun EpubLayoutPreferences.Theme.textColor(customBackgroundColor: Int): Int {
        val backgroundColor = backgroundColor(customBackgroundColor)
        return if (backgroundColor.isDark()) 0xFFFEFEFE.toInt() else 0xFF121212.toInt()
    }

    private fun Int.isDark(): Boolean = ColorUtils.calculateLuminance(this) < 0.35

    private fun EpubLayoutPreferences.FontFamily.toReadiumFontFamily(): ReadiumFontFamily? {
        return when (this) {
            EpubLayoutPreferences.FontFamily.ORIGINAL -> null
            EpubLayoutPreferences.FontFamily.SERIF -> ReadiumFontFamily.SERIF
            EpubLayoutPreferences.FontFamily.SANS_SERIF -> ReadiumFontFamily.SANS_SERIF
            EpubLayoutPreferences.FontFamily.MONOSPACE -> ReadiumFontFamily.MONOSPACE
            EpubLayoutPreferences.FontFamily.OPEN_DYSLEXIC -> ReadiumFontFamily.OPEN_DYSLEXIC
        }
    }
}

internal fun EpubLayoutPreferences.PageDirection.toReadiumReadingProgression(): ReadingProgression {
    return when (this) {
        EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT -> ReadingProgression.LTR
        EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT -> ReadingProgression.RTL
    }
}
