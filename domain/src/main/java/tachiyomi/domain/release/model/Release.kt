package tachiyomi.domain.release.model

/**
 * Contains information about the latest release.
 */
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    val downloadLink: String,
    val fallbackDownloadLinks: List<String> = emptyList(),
    val expectedSize: Long? = null,
    val expectedSha256: String? = null,
) {
    val downloadLinks: List<String>
        get() = (listOf(downloadLink) + fallbackDownloadLinks).distinct()
}
