package koharia.epub.model

import koharia.pdf.reflow.PdfReflowManifest

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
    val pdfReflow: PdfReflowManifest? = null,
) {
    enum class OpenSource {
        LOCAL,
        REMOTE,
    }
}
