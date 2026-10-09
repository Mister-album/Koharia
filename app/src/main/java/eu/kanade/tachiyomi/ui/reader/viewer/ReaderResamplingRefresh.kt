package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.View
import android.view.ViewGroup
import androidx.core.view.children

/** Includes the individual physical images of a tiled double-page spread. */
internal fun View.refreshReaderResampling(): Boolean {
    var handled = (this as? ReaderPageImageView)?.refreshResampling() ?: true
    if (this is ViewGroup) {
        children.toList().forEach { handled = it.refreshReaderResampling() && handled }
    }
    return handled
}
