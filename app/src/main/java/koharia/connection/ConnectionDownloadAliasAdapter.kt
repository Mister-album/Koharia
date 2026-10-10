package koharia.connection

data class ConnectionDownloadAlias(
    val mangaTitle: String,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
)

/**
 * Locally indexed legacy locations for the same reading unit; implementations never perform network
 * I/O.
 */
interface ConnectionDownloadAliasAdapter {
    fun downloadAliases(chapterUrl: String): List<ConnectionDownloadAlias>
}
