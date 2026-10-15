package eu.kanade.tachiyomi.data.download

/** Null scope means a full refresh. Directory identity also updates aliases sharing downloaded files. */
data class DownloadCacheChange(val sourceId: Long? = null, val mangaDirectories: Set<String>? = null) {
    fun affects(source: Long, directory: String): Boolean =
        (sourceId == null || sourceId == source) && (mangaDirectories == null || directory in mangaDirectories)
}
