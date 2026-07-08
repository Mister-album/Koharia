package koharia.epub.model

data class EpubOpenRequest(
    val mangaId: Long,
    val chapterId: Long,
    val sourceId: Long,
    val title: String,
    val bookUrl: String?,
    val localUri: String?,
    val openSource: OpenSource,
) {
    enum class OpenSource {
        LOCAL,
        REMOTE,
    }
}
