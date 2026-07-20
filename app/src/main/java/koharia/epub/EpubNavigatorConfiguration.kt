package koharia.epub

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Keeps the visible navigator and the pagination scanner on the same Readium CSS geometry.
 *
 * Readium defaults the document body to a 40rem maximum line length. That is effectively
 * full-width on phones, but leaves a narrow centered column on portrait tablets and can make
 * chapter headings wrap prematurely. A wider cap lets tablet pages use their reading area while
 * Readium's page-gutter preference still provides the user-selected horizontal margins.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun epubNavigatorConfiguration(): EpubNavigatorFragment.Configuration {
    return EpubNavigatorFragment.Configuration(
        readiumCssRsProperties = RsProperties(
            maxLineLength = Length.Rem(EPUB_MAX_LINE_LENGTH_REM),
        ),
        shouldApplyInsetsPadding = false,
    )
}

private const val EPUB_MAX_LINE_LENGTH_REM = 80.0
