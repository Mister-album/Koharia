@file:Suppress("ktlint:standard:max-line-length")

package koharia.suwayomi

import koharia.connection.ConnectionServerDownloadState

internal fun serverDownloadState(chapter: SuwayomiChapter, queued: SuwayomiDownloadItem?): ConnectionServerDownloadState = when {
    chapter.isDownloaded || queued?.state == "FINISHED" -> ConnectionServerDownloadState.DOWNLOADED
    queued?.state == "ERROR" -> ConnectionServerDownloadState.ERROR
    queued?.state == "DOWNLOADING" -> ConnectionServerDownloadState.DOWNLOADING
    queued != null -> ConnectionServerDownloadState.QUEUED
    else -> ConnectionServerDownloadState.AVAILABLE
}
