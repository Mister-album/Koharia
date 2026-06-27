package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem

/**
 * Adapter storing a list of downloads.
 *
 * @param downloadItemListener Listener called when an item of the list is released.
 */
class DownloadAdapter(val downloadItemListener: DownloadItemListener) : FlexibleAdapter<AbstractFlexibleItem<*>>(
    null,
    downloadItemListener,
    true,
) {
    override fun shouldMove(fromPosition: Int, toPosition: Int): Boolean {
        // Don't let sub-items changing group
        return getHeaderOf(getItem(fromPosition)) == getHeaderOf(getItem(toPosition))
    }

    fun hasRunningDownloads(): Boolean {
        return (0 until itemCount)
            .mapNotNull { getItem(it) as? DownloadItem }
            .any { it.download.status == eu.kanade.tachiyomi.data.download.model.Download.State.DOWNLOADING }
    }

    interface DownloadItemListener {
        fun onItemClick(position: Int)
        fun onItemReleased(position: Int)
        fun onPauseClick(position: Int)
        fun onResumeClick(position: Int)
        fun onCancelClick(position: Int)
    }
}
