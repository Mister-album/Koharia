package eu.kanade.tachiyomi.ui.download

import android.text.format.Formatter
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadItemBinding

/**
 * Class used to hold the data of a download.
 * All the elements from the layout file "download_item" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @constructor creates a new download holder.
 */
class DownloadHolder(private val view: View, val adapter: DownloadAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = DownloadItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.container.setOnClickListener {
            adapter.downloadItemListener.onItemClick(bindingAdapterPosition)
        }
        binding.btnPauseResume.setOnClickListener {
            val status = download.status
            if (shouldShowPauseAction(status)) {
                adapter.downloadItemListener.onPauseClick(bindingAdapterPosition)
            } else {
                adapter.downloadItemListener.onResumeClick(bindingAdapterPosition)
            }
        }
        binding.btnCancel.setOnClickListener {
            adapter.downloadItemListener.onCancelClick(bindingAdapterPosition)
        }
    }

    private lateinit var download: Download

    /**
     * Binds this holder with the given category.
     *
     * @param category The category to bind.
     */
    fun bind(download: Download) {
        this.download = download
        // Update the chapter name.
        binding.chapterTitle.text = download.chapter.name

        // Update the manga title
        binding.mangaFullTitle.text = download.manga.title

        // Update the progress bar and the number of downloaded pages
        when (download.mode) {
            Download.Mode.PAGE_CACHE -> {
                val pages = download.pages
                if (pages == null) {
                    binding.downloadProgress.progress = 0
                    binding.downloadProgress.max = 1
                    binding.downloadProgressText.text = ""
                } else {
                    binding.downloadProgress.max = pages.size * 100
                    notifyProgress()
                    notifyDownloadedPages()
                }
            }
            Download.Mode.RAW_FILE -> {
                binding.downloadProgress.max = 100
                notifyProgress()
                notifyDownloadedPages()
            }
        }

        notifyStatus()
    }

    /**
     * Updates the status of the pause/resume button.
     */
    fun notifyStatus() {
        val status = download.status
        if (shouldShowPauseAction(status)) {
            binding.btnPauseResume.setImageResource(R.drawable.ic_pause_24dp)
        } else {
            binding.btnPauseResume.setImageResource(R.drawable.ic_play_arrow_24dp)
        }
    }

    private fun shouldShowPauseAction(status: Download.State): Boolean {
        return status == Download.State.DOWNLOADING ||
            (status == Download.State.QUEUE && adapter.hasRunningDownloads())
    }

    /**
     * Updates the progress bar of the download.
     */
    fun notifyProgress() {
        when (download.mode) {
            Download.Mode.PAGE_CACHE -> {
                val pages = download.pages ?: return
                if (binding.downloadProgress.max == 1) {
                    binding.downloadProgress.max = pages.size * 100
                }
            }
            Download.Mode.RAW_FILE -> {
                val totalBytes = download.rawTotalBytes
                if (totalBytes > 0L && totalBytes <= Int.MAX_VALUE.toLong()) {
                    if (binding.downloadProgress.max != totalBytes.toInt()) {
                        binding.downloadProgress.max = totalBytes.toInt()
                    }
                } else if (binding.downloadProgress.max != 100) {
                    binding.downloadProgress.max = 100
                }
            }
        }
        val progress = when (download.mode) {
            Download.Mode.PAGE_CACHE -> download.totalProgress
            Download.Mode.RAW_FILE -> {
                val totalBytes = download.rawTotalBytes
                if (totalBytes > 0L && totalBytes <= Int.MAX_VALUE.toLong()) {
                    download.rawDownloadedBytes.coerceAtMost(totalBytes).toInt()
                } else {
                    download.totalProgress
                }
            }
        }
        binding.downloadProgress.setProgressCompat(progress, true)
    }

    /**
     * Updates the text field of the number of downloaded pages.
     */
    fun notifyDownloadedPages() {
        binding.downloadProgressText.text = when (download.mode) {
            Download.Mode.PAGE_CACHE -> {
                val pages = download.pages ?: return
                "${download.downloadedImages}/${pages.size}"
            }
            Download.Mode.RAW_FILE -> {
                val downloaded = Formatter.formatFileSize(view.context, download.rawDownloadedBytes)
                val totalBytes = download.rawTotalBytes
                if (totalBytes > 0L) {
                    val total = Formatter.formatFileSize(view.context, totalBytes)
                    "$downloaded/$total"
                } else {
                    downloaded
                }
            }
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.downloadItemListener.onItemReleased(position)
        binding.container.isDragged = false
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
        }
    }
}
