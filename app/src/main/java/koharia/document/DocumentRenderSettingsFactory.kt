package koharia.document

import androidx.core.graphics.ColorUtils
import koharia.epub.font.EpubFontId
import koharia.epub.font.EpubFontManager
import koharia.epub.settings.EpubLayoutPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Converts the shared EPUB preference model into values supported by bitmap document engines. */
fun EpubLayoutPreferences.toDocumentRenderSettings(
    fontManager: EpubFontManager = Injekt.get(),
): DocumentRenderSettings {
    val customBackgroundColor = customBackgroundColor.get()
    val theme = theme.get()
    val backgroundColor = when (theme) {
        EpubLayoutPreferences.Theme.LIGHT -> 0xFFFFFFFF.toInt()
        EpubLayoutPreferences.Theme.DARK -> 0xFF000000.toInt()
        EpubLayoutPreferences.Theme.SEPIA -> 0xFFFAF4E8.toInt()
        EpubLayoutPreferences.Theme.MINT -> 0xFFC4EDC8.toInt()
        EpubLayoutPreferences.Theme.BLUE -> 0xFFE0F0FC.toInt()
        EpubLayoutPreferences.Theme.PINK -> 0xFFFBE4EE.toInt()
        EpubLayoutPreferences.Theme.GRAY -> 0xFFF1F3F5.toInt()
        EpubLayoutPreferences.Theme.CUSTOM -> customBackgroundColor
    }.let { it or 0xFF000000.toInt() }

    val fontId = EpubFontId.fromPreference(selectedFontId.get())
    val typeface = fontManager.previewTypeface(fontId) ?: when (fontId) {
        EpubFontId.SERIF -> android.graphics.Typeface.SERIF
        EpubFontId.SANS_SERIF -> android.graphics.Typeface.SANS_SERIF
        EpubFontId.MONOSPACE -> android.graphics.Typeface.MONOSPACE
        EpubFontId.CURSIVE -> android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
        else -> android.graphics.Typeface.DEFAULT
    }

    return DocumentRenderSettings(
        backgroundColor = backgroundColor,
        textColor = if (ColorUtils.calculateLuminance(backgroundColor) < 0.35) {
            0xFFFEFEFE.toInt()
        } else {
            0xFF121212.toInt()
        },
        fontSizeScale = fontSize.get(),
        lineHeight = lineHeight.get(),
        paragraphSpacing = paragraphSpacing.get(),
        paragraphIndent = paragraphIndent.get(),
        pageMargins = pageMargins.get(),
        verticalMargins = verticalMargins.get(),
        textAlignment = when (textAlignment.get()) {
            EpubLayoutPreferences.TextAlignment.START -> DocumentRenderSettings.TextAlignment.START
            EpubLayoutPreferences.TextAlignment.LEFT -> DocumentRenderSettings.TextAlignment.LEFT
            EpubLayoutPreferences.TextAlignment.RIGHT -> DocumentRenderSettings.TextAlignment.RIGHT
            EpubLayoutPreferences.TextAlignment.JUSTIFY -> DocumentRenderSettings.TextAlignment.JUSTIFY
        },
        publisherStyles = publisherStyles.get(),
        typeface = typeface,
    )
}
