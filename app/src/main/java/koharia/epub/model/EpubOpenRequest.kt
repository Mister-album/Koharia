package koharia.epub.model

data class EpubOpenRequest(
    val mangaId: Long,
    val chapterId: Long,
    val sourceId: Long,
    val title: String,
    val bookUrl: String?,
    val localUri: String?,
    val openSource: OpenSource,
    val publisherStylesOverride: Boolean? = null,
    val publicationKey: String = "chapter:$chapterId",
    val persistCache: Boolean = true,
) {
    enum class OpenSource {
        LOCAL,
        REMOTE,
    }
}
