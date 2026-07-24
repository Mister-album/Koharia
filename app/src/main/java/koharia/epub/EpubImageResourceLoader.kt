package koharia.epub

import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import tachiyomi.core.common.util.system.ImageUtil
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets

internal suspend fun loadEpubImageContent(
    publication: Publication,
    reference: EpubImageReference,
): EpubImageContent {
    val documentLink = reference.resourceIndex
        .takeIf { it >= 0 }
        ?.let(publication.readingOrder::getOrNull)
        ?: publication.readingOrder.firstOrNull { link ->
            link.href.toString().sameEpubImageResource(reference.documentHref)
        }
        ?: throw IOException("EPUB image document is unavailable")

    var lastError: Throwable? = null
    for (url in epubImageCandidateUrls(documentLink, reference.currentSource, reference.rawSource)) {
        val resource = publication.get(url) ?: continue
        val bytes = try {
            resource.read().getOrElse { error ->
                throw IOException(error.message)
            }
        } catch (error: Throwable) {
            lastError = error
            continue
        } finally {
            resource.close()
        }

        val detectedType = ImageUtil.findImageType(bytes.inputStream())
        val isSvg = detectedType == null && bytes.isSvgImage()
        if (detectedType == null && !isSvg) {
            lastError = IOException("Unsupported EPUB image format")
            continue
        }

        val extension = detectedType?.extension ?: SVG_EXTENSION
        val mimeType = detectedType?.mime ?: SVG_MIME_TYPE
        val imageSource = Buffer().write(bytes)
        val isAnimated = !isSvg && ImageUtil.isAnimatedAndSupported(imageSource)
        imageSource.close()

        val originalFileName = url.filename
            ?.substringBeforeLast('.', missingDelimiterValue = url.filename.orEmpty())
            ?.takeIf(String::isNotBlank)
            ?: "image"

        return EpubImageContent(
            reference = reference,
            bytes = bytes.toByteString(),
            mimeType = mimeType,
            extension = extension,
            originalFileName = originalFileName,
            isAnimated = isAnimated,
            isSvg = isSvg,
        )
    }

    throw IOException(lastError?.message ?: "EPUB image resource is unavailable", lastError)
}

internal fun epubImageCandidateUrls(
    documentLink: Link,
    currentSource: String,
    rawSource: String,
): List<Url> = epubImageCandidateHrefs(
    documentHref = documentLink.href.toString(),
    currentSource = currentSource,
    rawSource = rawSource,
).mapNotNull(Url::invoke)

internal fun epubImageCandidateHrefs(
    documentHref: String,
    currentSource: String,
    rawSource: String,
): List<String> = buildList {
    val documentUri = runCatching { URI(documentHref) }.getOrNull() ?: return@buildList

    fun addCandidate(source: String) {
        if (source.isBlank()) return
        val sourceUri = runCatching { URI(source.trim()) }.getOrNull() ?: return
        val scheme = sourceUri.scheme
        if (scheme != null && !scheme.equals("http", true) && !scheme.equals("https", true)) return

        add(documentUri.resolve(sourceUri).toString().substringBefore('#'))
        if (sourceUri.rawAuthority.equals(READIUM_PACKAGE_AUTHORITY, ignoreCase = true)) {
            sourceUri.rawPath
                ?.trimStart('/')
                ?.takeIf(String::isNotBlank)
                ?.let { add(it) }
        }
    }

    addCandidate(currentSource)
    addCandidate(rawSource)
}.filter(String::isNotBlank).distinct()

private fun ByteArray.isSvgImage(): Boolean {
    val prefix = copyOfRange(0, size.coerceAtMost(SVG_PREFIX_BYTES))
        .toString(StandardCharsets.UTF_8)
        .trimStart('\uFEFF')
    return SVG_ROOT_PATTERN.containsMatchIn(prefix)
}

private fun String.sameEpubImageResource(other: String): Boolean {
    val first = substringBefore('#').substringBefore('?').trimStart('/')
    val second = other.substringBefore('#').substringBefore('?').trimStart('/')
    return first == second || first.endsWith("/$second") || second.endsWith("/$first")
}

internal const val SVG_MIME_TYPE = "image/svg+xml"
internal const val SVG_EXTENSION = "svg"
private const val SVG_PREFIX_BYTES = 16 * 1024
private const val READIUM_PACKAGE_AUTHORITY = "readium_package"
private val SVG_ROOT_PATTERN = Regex(
    pattern = """<svg(?:\s|>)""",
    option = RegexOption.IGNORE_CASE,
)
