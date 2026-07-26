package koharia.epub.service

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import logcat.LogPriority
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.map
import tachiyomi.core.common.util.system.logcat

private data class DecodedXhtml(
    val content: String,
    val charset: Charset,
    val byteOrderMark: ByteArray,
)

internal data class EpubXhtmlCompatibilityResult(
    val content: String,
    val repairedAttributes: Int,
)

private data class EpubXhtmlByteCompatibilityResult(
    val bytes: ByteArray,
    val repairedAttributes: Int,
)

internal fun Publication.Builder.installEpubXhtmlCompatibility() {
    if (container is EpubXhtmlCompatibilityContainer) return
    container = EpubXhtmlCompatibilityContainer(container, manifest)
}

private class EpubXhtmlCompatibilityContainer(
    private val delegate: Container<Resource>,
    private val manifest: Manifest,
) : Container<Resource> {

    override val sourceUrl = delegate.sourceUrl
    override val entries = delegate.entries

    override fun get(url: Url): Resource? {
        val resource = delegate[url] ?: return null
        if (!manifest.isHtmlResource(url)) return resource

        return resource.map { bytes ->
            val normalized = bytes.normalizeEpubXhtmlForCompatibility()
            if (normalized.repairedAttributes > 0) {
                logcat(LogPriority.DEBUG) {
                    "EPUB XHTML compatibility repaired href=${url.safeLogHref()} " +
                        "attributes=${normalized.repairedAttributes}"
                }
            }
            Try.success(normalized.bytes)
        }
    }

    override fun close() {
        delegate.close()
    }
}

private fun Manifest.isHtmlResource(url: Url): Boolean {
    if (linkWithHref(url)?.mediaType?.isHtml == true) return true
    return url.toString()
        .substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase() in HTML_FILE_EXTENSIONS
}

private fun Url.safeLogHref(): String =
    toString()
        .substringBefore('?')
        .substringBefore('#')

private fun ByteArray.normalizeEpubXhtmlForCompatibility(): EpubXhtmlByteCompatibilityResult {
    val decoded = decodeXhtml() ?: return EpubXhtmlByteCompatibilityResult(this, 0)
    val normalized = decoded.content.normalizeEpubXhtmlForCompatibility()
    if (normalized.repairedAttributes == 0) {
        return EpubXhtmlByteCompatibilityResult(this, 0)
    }
    return EpubXhtmlByteCompatibilityResult(
        bytes = decoded.byteOrderMark + normalized.content.toByteArray(decoded.charset),
        repairedAttributes = normalized.repairedAttributes,
    )
}

private fun ByteArray.decodeXhtml(): DecodedXhtml? {
    val (charset, byteOrderMark) = when {
        startsWithBytes(UTF_16_BE_BOM) -> Charsets.UTF_16BE to UTF_16_BE_BOM
        startsWithBytes(UTF_16_LE_BOM) -> Charsets.UTF_16LE to UTF_16_LE_BOM
        startsWithBytes(UTF_8_BOM) -> Charsets.UTF_8 to UTF_8_BOM
        else -> Charsets.UTF_8 to byteArrayOf()
    }
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val contentBytes = ByteBuffer.wrap(this, byteOrderMark.size, size - byteOrderMark.size)
    val content = runCatching { decoder.decode(contentBytes).toString() }.getOrNull() ?: return null
    return DecodedXhtml(content, charset, byteOrderMark)
}

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

internal fun String.normalizeEpubXhtmlForCompatibility(): EpubXhtmlCompatibilityResult {
    if ('<' !in this) return EpubXhtmlCompatibilityResult(this, 0)

    // Work on start-tag tokens only. A whole-document regex could rewrite attribute-shaped text
    // inside quoted values, comments, CDATA or processing instructions.
    val normalized = StringBuilder(length)
    var repairedAttributes = 0
    var cursor = 0
    while (cursor < length) {
        val tagStart = indexOf('<', cursor)
        if (tagStart < 0) {
            normalized.append(substring(cursor))
            break
        }
        normalized.append(substring(cursor, tagStart))

        val tagEnd = findMarkupEnd(tagStart)
        if (tagEnd < 0) {
            normalized.append(substring(tagStart))
            break
        }

        val markup = substring(tagStart, tagEnd + 1)
        if (tagStart + 1 < length && this[tagStart + 1].isXmlNameStart()) {
            val result = markup.normalizeStartTagAttributes()
            normalized.append(result.content)
            repairedAttributes += result.repairedAttributes

            val rawTextElement = markup.startTagName()
                ?.lowercase()
                ?.takeIf(RAW_TEXT_ELEMENT_NAMES::contains)
            val contentStart = tagEnd + 1
            if (rawTextElement != null && !markup.isSelfClosingStartTag()) {
                val rawTextEnd = findRawTextEndTag(rawTextElement, contentStart)
                if (rawTextEnd < 0) {
                    normalized.append(substring(contentStart))
                    cursor = length
                    continue
                }
                normalized.append(substring(contentStart, rawTextEnd))
                cursor = rawTextEnd
                continue
            }
        } else {
            normalized.append(markup)
        }
        cursor = tagEnd + 1
    }
    return EpubXhtmlCompatibilityResult(normalized.toString(), repairedAttributes)
}

private fun String.findRawTextEndTag(elementName: String, start: Int): Int {
    val closingPrefix = "</$elementName"
    var candidate = indexOf(closingPrefix, startIndex = start, ignoreCase = true)
    while (candidate >= 0) {
        val boundary = getOrNull(candidate + closingPrefix.length)
        if (boundary == null || boundary.isWhitespace() || boundary == '>') return candidate
        candidate = indexOf(closingPrefix, startIndex = candidate + closingPrefix.length, ignoreCase = true)
    }
    return -1
}

private fun String.startTagName(): String? {
    if (length < 3 || first() != '<' || !this[1].isXmlNameStart()) return null
    var end = 2
    while (end < lastIndex && this[end].isXmlNamePart()) end++
    return substring(1, end)
}

private fun String.isSelfClosingStartTag(): Boolean =
    dropLast(1).trimEnd().endsWith('/')

private fun String.findMarkupEnd(tagStart: Int): Int {
    return when {
        startsWith("<!--", tagStart) -> indexOf("-->", tagStart + 4)
            .takeIf { it >= 0 }
            ?.plus(2)
            ?: -1
        startsWith("<![CDATA[", tagStart) -> indexOf("]]>", tagStart + 9)
            .takeIf { it >= 0 }
            ?.plus(2)
            ?: -1
        startsWith("<?", tagStart) -> indexOf("?>", tagStart + 2)
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: -1
        else -> findTagEnd(tagStart + 1)
    }
}

private fun String.findTagEnd(start: Int): Int {
    var quote: Char? = null
    for (index in start until length) {
        val character = this[index]
        if (quote != null) {
            if (character == quote) quote = null
        } else {
            when (character) {
                '\'', '"' -> quote = character
                '>' -> return index
            }
        }
    }
    return -1
}

private fun String.normalizeStartTagAttributes(): EpubXhtmlCompatibilityResult {
    if (length < 3 || first() != '<' || last() != '>') {
        return EpubXhtmlCompatibilityResult(this, 0)
    }

    val normalized = StringBuilder(length)
    normalized.append('<')
    var cursor = 1
    while (cursor < lastIndex && this[cursor].isXmlNamePart()) {
        normalized.append(this[cursor])
        cursor++
    }

    var repairedAttributes = 0
    while (cursor < lastIndex) {
        val character = this[cursor]
        if (character.isWhitespace() || character == '/') {
            normalized.append(character)
            cursor++
            continue
        }
        if (!character.isXmlNameStart()) {
            normalized.append(character)
            cursor++
            continue
        }

        val nameStart = cursor
        cursor++
        while (cursor < lastIndex && this[cursor].isXmlNamePart()) cursor++
        normalized.append(substring(nameStart, cursor))

        val separatorStart = cursor
        while (cursor < lastIndex && this[cursor].isWhitespace()) cursor++
        if (cursor >= lastIndex || this[cursor] != '=') {
            normalized.append("=\"\"")
            repairedAttributes++
            cursor = separatorStart
            continue
        }

        normalized.append(substring(separatorStart, cursor + 1))
        cursor++
        val valueSpacingStart = cursor
        while (cursor < lastIndex && this[cursor].isWhitespace()) cursor++
        normalized.append(substring(valueSpacingStart, cursor))
        if (cursor >= lastIndex) continue

        val quote = this[cursor].takeIf { it == '\'' || it == '"' }
        if (quote != null) {
            val valueStart = cursor
            cursor++
            while (cursor < lastIndex && this[cursor] != quote) cursor++
            if (cursor < lastIndex) cursor++
            normalized.append(substring(valueStart, cursor))
        } else {
            val valueStart = cursor
            while (cursor < lastIndex && !this[cursor].isWhitespace()) cursor++
            normalized.append(substring(valueStart, cursor))
        }
    }
    normalized.append('>')
    return EpubXhtmlCompatibilityResult(normalized.toString(), repairedAttributes)
}

private fun Char.isXmlNameStart(): Boolean =
    this == ':' || this == '_' || isLetter()

private fun Char.isXmlNamePart(): Boolean =
    isXmlNameStart() || this == '-' || this == '.' || isDigit()

private val HTML_FILE_EXTENSIONS = setOf("htm", "html", "xht", "xhtml")
private val RAW_TEXT_ELEMENT_NAMES = setOf("script", "style")
private val UTF_8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF_16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
private val UTF_16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
