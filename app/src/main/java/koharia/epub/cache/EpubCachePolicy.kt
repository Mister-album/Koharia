package koharia.epub.cache

internal object EpubCachePolicy {

    enum class OpenSource {
        MANUAL_DOWNLOAD,
        COMPLETE_CACHE,
        REMOTE,
    }

    fun selectOpenSource(
        manualDownloadUri: String?,
        completeCacheUri: String?,
        remoteUrl: String?,
    ): OpenSource? = when {
        manualDownloadUri != null -> OpenSource.MANUAL_DOWNLOAD
        completeCacheUri != null -> OpenSource.COMPLETE_CACHE
        remoteUrl != null -> OpenSource.REMOTE
        else -> null
    }

    fun publicationKey(
        fileHash: String?,
        fileLastModified: String?,
        sizeBytes: Long,
        fallback: String,
    ): String = when {
        !fileHash.isNullOrBlank() -> "komga:$fileHash"
        !fileLastModified.isNullOrBlank() && sizeBytes > 0L ->
            "komga:$fileLastModified:$sizeBytes"
        else -> fallback
    }

    fun shouldAppendPartial(
        existingBytes: Long,
        responseCode: Int,
        contentRange: String?,
    ): Boolean {
        if (existingBytes <= 0L || responseCode != 206) return false
        return contentRange
            ?.trim()
            ?.startsWith("bytes $existingBytes-", ignoreCase = true) == true
    }
}
