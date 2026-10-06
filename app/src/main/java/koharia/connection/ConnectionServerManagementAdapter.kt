package koharia.connection

import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.flow.Flow

/** Remote category membership is many-to-many and independent of local library categories. */
interface ConnectionRemoteCategoriesAdapter {
    fun remoteCategoriesScreen(mangaUrl: String? = null): Screen
}

/** A server's download queue never participates in the device DownloadManager. */
interface ConnectionServerDownloadsAdapter {
    fun serverDownloadsScreen(): Screen
    fun serverChapterDownloads(mangaUrl: String): Flow<Map<String, ConnectionServerDownloadState>>
    suspend fun downloadChaptersOnServer(chapterUrls: List<String>)
}

enum class ConnectionServerDownloadState { AVAILABLE, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }
