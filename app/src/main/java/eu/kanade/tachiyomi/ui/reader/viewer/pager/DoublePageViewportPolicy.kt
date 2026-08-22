package eu.kanade.tachiyomi.ui.reader.viewer.pager

internal object DoublePageViewportPolicy {

    fun allowsAutomaticDoublePages(width: Int, height: Int): Boolean {
        return width > 0 && height > 0 && width > height
    }
}
