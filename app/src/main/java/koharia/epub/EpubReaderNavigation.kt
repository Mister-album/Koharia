package koharia.epub

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import koharia.epub.settings.EpubLayoutPreferences

internal fun createEpubNavigation(
    readingMode: EpubLayoutPreferences.ReadingMode,
    navigationMode: Int,
    invertMode: ReaderPreferences.TappingInvertMode,
): ViewerNavigation {
    val navigator = when (navigationMode) {
        0 -> defaultEpubNavigation(readingMode)
        1 -> LNavigation()
        2 -> KindlishNavigation()
        3 -> EdgeNavigation()
        4 -> RightAndLeftNavigation()
        5 -> DisabledNavigation()
        else -> defaultEpubNavigation(readingMode)
    }
    navigator.invertMode = invertMode
    return navigator
}

internal fun defaultEpubNavigation(
    readingMode: EpubLayoutPreferences.ReadingMode,
): ViewerNavigation {
    return if (readingMode == EpubLayoutPreferences.ReadingMode.SCROLL) {
        LNavigation()
    } else {
        RightAndLeftNavigation()
    }
}
