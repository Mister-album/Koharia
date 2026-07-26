package koharia.epub

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.jsoup.Jsoup
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import java.net.URI

internal data class EpubFootnoteImageContent(
    val bytes: ByteString,
    val isSvg: Boolean,
)

internal fun epubFootnoteImageSources(contentHtml: String): List<String> =
    Jsoup.parseBodyFragment(contentHtml)
        .select("img[src]")
        .mapNotNull { element -> element.attr("src").trim().takeIf(String::isNotEmpty) }
        .distinct()
        .take(MAX_EPUB_FOOTNOTE_IMAGES)

internal suspend fun loadEpubFootnoteImages(
    publication: Publication,
    documentHref: String,
    contentHtml: String,
): Map<String, EpubFootnoteImageContent> {
    val images = linkedMapOf<String, EpubFootnoteImageContent>()
    var totalBytes = 0

    for (source in epubFootnoteImageSources(contentHtml)) {
        for (candidate in epubImageCandidateHrefs(documentHref, source, source)) {
            val candidateUri = runCatching { URI(candidate) }.getOrNull() ?: continue
            if (candidateUri.scheme != null || candidateUri.rawAuthority != null) continue
            val resource = Url(candidate)?.let(publication::get) ?: continue
            try {
                val declaredLength = resource.length().getOrNull()
                if (declaredLength != null && declaredLength > MAX_EPUB_FOOTNOTE_IMAGE_BYTES) continue

                val bytes = resource.read(0L..MAX_EPUB_FOOTNOTE_IMAGE_BYTES).getOrNull() ?: continue
                if (bytes.isEmpty() || bytes.size > MAX_EPUB_FOOTNOTE_IMAGE_BYTES) continue
                if (totalBytes + bytes.size > MAX_EPUB_FOOTNOTE_TOTAL_IMAGE_BYTES) return images

                images[source] = EpubFootnoteImageContent(
                    bytes = bytes.toByteString(),
                    isSvg = bytes.isSvgImage(),
                )
                totalBytes += bytes.size
                break
            } finally {
                resource.close()
            }
        }
    }
    return images
}

private const val MAX_EPUB_FOOTNOTE_IMAGES = 8
private const val MAX_EPUB_FOOTNOTE_IMAGE_BYTES = 4L * 1024L * 1024L
private const val MAX_EPUB_FOOTNOTE_TOTAL_IMAGE_BYTES = 12 * 1024 * 1024
