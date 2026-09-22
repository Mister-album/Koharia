package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.setting.PageLayout

internal object PagerLayoutPolicy {
    data class Layout(val doublePages: Boolean, val automaticSplit: Boolean, val manualSplit: Boolean) {
        val splitsWidePages: Boolean get() = automaticSplit || manualSplit
    }

    fun resolve(pageLayout: PageLayout, splitPages: Boolean, horizontal: Boolean, wideViewport: Boolean): Layout {
        val automaticDouble = horizontal && pageLayout == PageLayout.AUTOMATIC_DOUBLE_PAGES
        val doublePages = horizontal && pageLayout.usesDoublePages &&
            (automaticDouble || !splitPages) && (!automaticDouble || wideViewport)
        return Layout(
            doublePages = doublePages,
            automaticSplit = pageLayout.automaticallySplitsWidePages || (automaticDouble && !doublePages),
            manualSplit = splitPages && !automaticDouble,
        )
    }
}
