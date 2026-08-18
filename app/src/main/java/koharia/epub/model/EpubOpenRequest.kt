package koharia.epub.model

data class RemotePublicationRef(
    val providerId: String,
    val resourceId: String,
)

data class EpubOpenRequest(
    val mangaId: Long,
    val chapterId: Long,
    val sourceId: Long,
    val title: String,
    val remotePublication: RemotePublicationRef?,
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
