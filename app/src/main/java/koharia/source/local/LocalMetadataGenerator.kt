package koharia.source.local

import eu.kanade.tachiyomi.source.model.SManga
import koharia.connection.LibraryMetadata
import koharia.connection.LibraryMetadataField
import koharia.connection.LibraryMetadataSuggestion
import koharia.connection.MetadataFilenameTemplate
import koharia.connection.MetadataSuggestionSource
import org.jsoup.nodes.Document

internal data class LocalEmbeddedMetadata(
    val title: String? = null,
    val series: String? = null,
    val authors: List<String> = emptyList(),
    val contributors: List<String> = emptyList(),
    val description: String? = null,
    val subjects: List<String> = emptyList(),
)

internal fun LocalEmbeddedMetadata.forSeriesDisplay(isDirectoryMetadata: Boolean): LocalEmbeddedMetadata {
    return copy(title = series ?: title.takeIf { isDirectoryMetadata })
}

private data class LocalFilenameMetadata(
    val titleCandidates: List<String>,
    val authors: List<String>,
    val status: Int?,
)

private data class ClassifiedFilenameSegments(
    val titleCandidates: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val status: Int? = null,
    val hasVolumeMarker: Boolean = false,
    val hasStructuralMarker: Boolean = false,
)

internal fun parseLocalOpfMetadata(document: Document): LocalEmbeddedMetadata {
    fun firstText(name: String): String? = document.selectFirst("*|$name")
        ?.text()
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun allText(name: String): List<String> = document.select("*|$name")
        .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
        .distinct()

    fun metaContent(name: String): String? = document.selectFirst("""meta[name="$name"]""")
        ?.attr("content")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    val collection = document.selectFirst("meta[property=belongs-to-collection]")
        ?.let { element -> element.attr("content").ifBlank { element.text() } }
        ?.trim()
        ?.takeIf(String::isNotBlank)

    return LocalEmbeddedMetadata(
        title = firstText("title"),
        series = metaContent("calibre:series") ?: collection,
        authors = allText("creator"),
        contributors = allText("contributor"),
        description = firstText("description"),
        subjects = allText("subject"),
    )
}

internal fun generateLocalMetadataSuggestion(
    folderName: String,
    itemNames: List<String>,
    embeddedMetadata: List<LocalEmbeddedMetadata>,
    filenameTemplate: MetadataFilenameTemplate,
): LibraryMetadataSuggestion {
    val folderMetadata = parseLocalFilenameMetadata(
        filename = folderName,
        template = MetadataFilenameTemplate.AUTO,
        allowPlainTitle = true,
    )
    val itemMetadata = itemNames.mapNotNull { name ->
        parseLocalFilenameMetadata(name, filenameTemplate, allowPlainTitle = false)
    }
    val filenameCandidates = itemMetadata.mapNotNull { metadata -> metadata.titleCandidates.firstOrNull() }
    val filenameSeries = filenameCandidates.mostCommonValue()
    val embeddedSeries = embeddedMetadata.mapNotNull(LocalEmbeddedMetadata::series).mostCommonValue()
    val embeddedTitles = embeddedMetadata.mapNotNull(LocalEmbeddedMetadata::title).distinctNormalized()
    val embeddedTitle = embeddedTitles.singleOrNull()
    val embeddedSeriesOrTitle = embeddedSeries ?: embeddedTitle
    val normalizedFolderName = folderMetadata?.titleCandidates?.firstOrNull()
        ?: folderName.trim().takeIf(String::isNotBlank)

    val titleWithSource = when (filenameTemplate) {
        MetadataFilenameTemplate.AUTO -> {
            embeddedSeriesOrTitle?.let { it to MetadataSuggestionSource.EPUB_EMBEDDED }
                ?: filenameSeries?.let { it to MetadataSuggestionSource.ITEM_FILENAME }
                ?: normalizedFolderName?.let { it to MetadataSuggestionSource.FOLDER }
        }
        MetadataFilenameTemplate.FOLDER_ITEM_TITLE -> {
            normalizedFolderName?.let { it to MetadataSuggestionSource.FOLDER }
                ?: embeddedSeriesOrTitle?.let { it to MetadataSuggestionSource.EPUB_EMBEDDED }
        }
        else -> {
            filenameSeries?.let { it to MetadataSuggestionSource.ITEM_FILENAME }
                ?: embeddedSeriesOrTitle?.let { it to MetadataSuggestionSource.EPUB_EMBEDDED }
                ?: normalizedFolderName?.let { it to MetadataSuggestionSource.FOLDER }
        }
    }

    val embeddedAuthors = embeddedMetadata.flatMap(LocalEmbeddedMetadata::authors).distinctNormalized()
    val filenameAuthors = itemMetadata.map(LocalFilenameMetadata::authors).mostCommonList()
    val authorWithSource = embeddedAuthors.takeIf(List<String>::isNotEmpty)
        ?.joinToString(", ")
        ?.let { it to MetadataSuggestionSource.EPUB_EMBEDDED }
        ?: filenameAuthors.takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?.let { it to MetadataSuggestionSource.ITEM_FILENAME }
        ?: folderMetadata?.authors
            ?.takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?.let { it to MetadataSuggestionSource.FOLDER }
    val statusWithSource = itemMetadata.mapNotNull(LocalFilenameMetadata::status)
        .mostCommonInt()
        ?.let { it to MetadataSuggestionSource.ITEM_FILENAME }
        ?: folderMetadata?.status?.let { it to MetadataSuggestionSource.FOLDER }
    val contributors = embeddedMetadata.flatMap(LocalEmbeddedMetadata::contributors).distinctNormalized()
    val description = embeddedMetadata.mapNotNull(LocalEmbeddedMetadata::description)
        .maxByOrNull(String::length)
    val subjects = embeddedMetadata.flatMap(LocalEmbeddedMetadata::subjects).distinctNormalized()

    val fieldSources = buildMap {
        titleWithSource?.let { put(LibraryMetadataField.TITLE, it.second) }
        authorWithSource?.let { put(LibraryMetadataField.AUTHOR, it.second) }
        if (contributors.isNotEmpty()) put(LibraryMetadataField.ARTIST, MetadataSuggestionSource.EPUB_EMBEDDED)
        if (description != null) put(LibraryMetadataField.DESCRIPTION, MetadataSuggestionSource.EPUB_EMBEDDED)
        if (subjects.isNotEmpty()) put(LibraryMetadataField.GENRES, MetadataSuggestionSource.EPUB_EMBEDDED)
        statusWithSource?.let { put(LibraryMetadataField.STATUS, it.second) }
    }

    return LibraryMetadataSuggestion(
        metadata = LibraryMetadata(
            title = titleWithSource?.first,
            author = authorWithSource?.first,
            artist = contributors.takeIf(List<String>::isNotEmpty)?.joinToString(", "),
            description = description,
            genres = subjects,
            status = statusWithSource?.first,
            source = "generated",
        ),
        fieldSources = fieldSources,
        matchedFilenameCount = when (filenameTemplate) {
            MetadataFilenameTemplate.FOLDER_ITEM_TITLE -> itemNames.count(String::isNotBlank)
            else -> itemMetadata.count { it.titleCandidates.isNotEmpty() }
        },
        totalItemCount = itemNames.size,
    )
}

private fun parseLocalFilenameMetadata(
    filename: String,
    template: MetadataFilenameTemplate,
    allowPlainTitle: Boolean,
): LocalFilenameMetadata? {
    val name = filename.withoutSupportedExtension().trim()
    if (name.isBlank()) return null

    // Stage 1: tokenize bracketed parts without assuming fixed positions.
    val segments = BRACKET_SEGMENT_PATTERN.findAll(name)
        .map { match ->
            (match.groups[1]?.value ?: match.groups[2]?.value)
                .orEmpty()
                .trim()
        }
        .filter(String::isNotBlank)
        .toList()

    // Stage 2: classify title, creator, volume, status, edition, and source segments.
    val outsideBracketText = BRACKET_SEGMENT_PATTERN.replace(name, "").trim()
    val classified = classifyFilenameSegments(
        segments = segments,
        hasOutsideTitle = outsideBracketText.isNotBlank(),
    )

    // Stage 3: remove classified bracket tags and infer a title from the remaining filename.
    val inlineStatus = parseInlineStatus(name)
    val sanitizedName = name
        .replace(IGNORED_INLINE_TAG_PATTERN, " ")
        .replace(INLINE_STATUS_PATTERN, " ")
    val sequenceAwareName = preserveBracketedSequenceMarkers(sanitizedName)
        .replace(PARENTHESIS_PATTERN, " ")
        .normalizeWhitespace()
    val unwrappedName = BRACKET_SEGMENT_PATTERN.replace(sanitizedName, " ")
        .replace(PARENTHESIS_PATTERN, " ")
        .normalizeWhitespace()
    val inferredTitle = when (template) {
        MetadataFilenameTemplate.AUTO -> {
            extractSeries(sequenceAwareName, VOLUME_PATTERN)
                ?: extractSeries(sequenceAwareName, CHAPTER_PATTERN)
                ?: extractSeriesTitle(unwrappedName)
                ?: unwrappedName.takeIf {
                    allowPlainTitle ||
                        ((classified.hasStructuralMarker || sanitizedName != name) && it.isNotBlank())
                }?.let(::cleanSeriesCandidate)
        }
        MetadataFilenameTemplate.SERIES_VOLUME_TITLE -> {
            extractSeries(sequenceAwareName, VOLUME_PATTERN)
                ?: extractSeries(unwrappedName, TRAILING_VOLUME_NUMBER_PATTERN)
                ?: unwrappedName.takeIf {
                    classified.hasVolumeMarker && it.isNotBlank()
                }?.let(::cleanSeriesCandidate)
        }
        MetadataFilenameTemplate.SERIES_CHAPTER_TITLE -> {
            extractSeries(sequenceAwareName, CHAPTER_PATTERN)
        }
        MetadataFilenameTemplate.SERIES_TITLE -> extractSeriesTitle(unwrappedName)
        MetadataFilenameTemplate.FOLDER_ITEM_TITLE -> null
    }

    // Stage 4: normalize aliases and choose deterministic candidates for aggregation.
    val titleCandidates = (classified.titleCandidates + listOfNotNull(inferredTitle))
        .flatMap(::splitTitleCandidates)
        .distinctNormalized()

    return LocalFilenameMetadata(
        titleCandidates = titleCandidates,
        authors = classified.authors.distinctNormalized(),
        status = classified.status ?: inlineStatus,
    )
}

private fun preserveBracketedSequenceMarkers(value: String): String {
    return BRACKET_SEGMENT_PATTERN.replace(value) { match ->
        val segment = (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim()
        when {
            VOLUME_SEGMENT_PATTERN.matches(segment) -> {
                val normalized = if (segment.all(Char::isDigit)) "Vol $segment" else segment
                " $normalized "
            }
            CHAPTER_SEGMENT_PATTERN.matches(segment) -> " $segment "
            else -> " "
        }
    }
}

private fun classifyFilenameSegments(
    segments: List<String>,
    hasOutsideTitle: Boolean,
): ClassifiedFilenameSegments {
    if (segments.isEmpty()) return ClassifiedFilenameSegments()

    val titles = mutableListOf<String>()
    val authors = mutableListOf<String>()
    var status: Int? = null
    var hasVolumeMarker = false
    var hasStructuralMarker = false
    var structuralSegmentSeen = false

    segments.forEachIndexed { index, rawSegment ->
        val segment = rawSegment.normalizeWhitespace()
        val parsedStatus = parseBracketStatus(segment)
        val prefixedAuthors = extractPrefixedAuthors(segment)
        when {
            parsedStatus != null -> {
                status = status ?: parsedStatus
                structuralSegmentSeen = true
                hasStructuralMarker = true
            }
            VOLUME_SEGMENT_PATTERN.matches(segment) -> {
                hasVolumeMarker = true
                structuralSegmentSeen = true
                hasStructuralMarker = true
            }
            CHAPTER_SEGMENT_PATTERN.matches(segment) -> {
                structuralSegmentSeen = true
                hasStructuralMarker = true
            }
            isIgnoredFilenameTag(segment) -> {
                structuralSegmentSeen = true
                hasStructuralMarker = true
            }
            prefixedAuthors.isNotEmpty() -> {
                authors += prefixedAuthors
                hasStructuralMarker = true
            }
            (index > 0 || hasOutsideTitle) &&
                AUTHOR_SEPARATOR_PATTERN.containsMatchIn(segment) &&
                !structuralSegmentSeen -> {
                authors += splitAuthors(segment)
                hasStructuralMarker = true
            }
            titles.isEmpty() && !structuralSegmentSeen -> titles += segment
            authors.isEmpty() && !structuralSegmentSeen -> {
                authors += splitAuthors(segment)
                hasStructuralMarker = true
            }
            else -> {
                structuralSegmentSeen = true
                hasStructuralMarker = true
            }
        }
    }

    return ClassifiedFilenameSegments(
        titleCandidates = titles.flatMap(::splitTitleCandidates),
        authors = authors,
        status = status,
        hasVolumeMarker = hasVolumeMarker,
        hasStructuralMarker = hasStructuralMarker,
    )
}

private fun extractPrefixedAuthors(value: String): List<String> {
    val match = AUTHOR_PREFIX_PATTERN.find(value) ?: return emptyList()
    return splitAuthors(value.substring(match.range.last + 1))
}

private fun splitAuthors(value: String): List<String> = value
    .split(AUTHOR_SEPARATOR_PATTERN)
    .map(String::normalizeWhitespace)
    .filter(String::isNotBlank)

private fun splitTitleCandidates(value: String): List<String> = value
    .split('_')
    .map { candidate ->
        candidate
            .normalizeWhitespace()
            .trim('-', '–', '—', '_', '.', ' ')
    }
    .filter(String::isNotBlank)

private fun isIgnoredFilenameTag(value: String): Boolean {
    return value.lowercase().replace(" ", "") in IGNORED_FILENAME_TAGS
}

private fun parseBracketStatus(value: String): Int? = when (value.trim().lowercase()) {
    "完结", "完結", "已完结", "已完結", "全", "completed", "complete", "finished", "ended" -> SManga.COMPLETED
    "连载", "連載", "连载中", "連載中", "ongoing", "publishing" -> SManga.ONGOING
    "休刊", "休载", "休載", "hiatus", "on hiatus" -> SManga.ON_HIATUS
    "中止", "腰斩", "腰斬", "cancelled", "canceled" -> SManga.CANCELLED
    else -> null
}

private fun parseInlineStatus(value: String): Int? {
    return INLINE_STATUS_PATTERN.find(value)?.groups?.get(1)?.value?.let(::parseBracketStatus)
}

private fun String.withoutSupportedExtension(): String {
    val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (extension in MEDIA_EXTENSIONS) substringBeforeLast('.') else this
}

private fun String.normalizeWhitespace(): String = trim().replace(WHITESPACE_PATTERN, " ")

private fun extractSeries(name: String, marker: Regex): String? {
    val match = marker.find(name) ?: return null
    return cleanSeriesCandidate(name.substring(0, match.range.first))
}

private fun extractSeriesTitle(name: String): String? {
    val separator = SERIES_TITLE_SEPARATOR.find(name) ?: return null
    return cleanSeriesCandidate(name.substring(0, separator.range.first))
}

private fun cleanSeriesCandidate(value: String): String? {
    return value.trim().trim('-', '–', '—', '_', '.', ' ', '[', ']', '(', ')', '【', '】')
        .takeIf(String::isNotBlank)
}

private fun List<String>.mostCommonValue(): String? {
    return filter(String::isNotBlank)
        .groupBy { it.trim().lowercase() }
        .maxByOrNull { it.value.size }
        ?.value
        ?.first()
        ?.trim()
}

private fun List<String>.distinctNormalized(): List<String> {
    return map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
}

private fun List<List<String>>.mostCommonList(): List<String> {
    return filter(List<String>::isNotEmpty)
        .groupBy { values -> values.joinToString("\u0000") { it.trim().lowercase() } }
        .maxByOrNull { it.value.size }
        ?.value
        ?.first()
        .orEmpty()
}

private fun List<Int>.mostCommonInt(): Int? {
    return groupBy { it }
        .maxByOrNull { it.value.size }
        ?.key
}

private val BRACKET_SEGMENT_PATTERN = Regex("""\[([^]]+)]|【([^】]+)】""")

private val PARENTHESIS_PATTERN = Regex("""[()（）]""")

private val WHITESPACE_PATTERN = Regex("""\s+""")

private val AUTHOR_PREFIX_PATTERN = Regex(
    pattern = """^(?:作者|作画|作畫|原作|脚本|腳本|漫画|漫畫|author|writer|artist)\s*[:：]\s*""",
    option = RegexOption.IGNORE_CASE,
)

private val AUTHOR_SEPARATOR_PATTERN = Regex("""\s*(?:×|、|/|／|&|，|,)\s*""")

private val VOLUME_SEGMENT_PATTERN = Regex(
    pattern = """(?:v(?:ol(?:ume)?)?\.?\s*\d+(?:\.\d+)?|book\s*\d+(?:\.\d+)?|""" +
        """第?\s*\d+(?:\.\d+)?\s*(?:巻|卷|册|冊|集)|\d{1,4})""",
    option = RegexOption.IGNORE_CASE,
)

private val CHAPTER_SEGMENT_PATTERN = Regex(
    pattern = """(?:ch(?:apter)?\.?\s*\d+(?:\.\d+)?|c\s*\d+(?:\.\d+)?|""" +
        """ep(?:isode)?\.?\s*\d+(?:\.\d+)?|#\s*\d+(?:\.\d+)?|""" +
        """第\s*\d+(?:\.\d+)?\s*[话話章回])""",
    option = RegexOption.IGNORE_CASE,
)

private val IGNORED_FILENAME_TAGS = setOf(
    "bili",
    "bilibili",
    "境外版",
    "海外版",
    "单行本",
    "單行本",
    "数字版",
    "數字版",
    "电子版",
    "電子版",
    "digital",
    "digitaledition",
    "kindle",
    "web版",
    "扫图",
    "掃圖",
    "修正版",
)

private val IGNORED_INLINE_TAG_PATTERN = Regex(
    pattern = """[\s._\-–—]*(?:[\[【(（]\s*)?(?:境外版|海外版|单行本|單行本|""" +
        """数字版|數字版|电子版|電子版|digital(?:\s*edition)?|kindle|web版|""" +
        """扫图|掃圖|修正版|bili(?:bili)?)(?:\s*[\]】)）])?[\s._\-–—]*""",
    option = RegexOption.IGNORE_CASE,
)

private val INLINE_STATUS_PATTERN = Regex(
    pattern = """(?:^|[\s._\-–—\[【(（])(完结|完結|已完结|已完結|全|completed|complete|""" +
        """finished|ended|连载|連載|连载中|連載中|ongoing|publishing|休刊|休载|休載|""" +
        """hiatus|on\s+hiatus|中止|腰斩|腰斬|cancelled|canceled)""" +
        """(?=$|[\s._\-–—\]】)）])""",
    option = RegexOption.IGNORE_CASE,
)

private val VOLUME_PATTERN = Regex(
    pattern = """(?:^|[\s._\-–—\[【(])""" +
        """(?:v(?:ol(?:ume)?)?\.?\s*\d+(?:\.\d+)?|book\s*\d+(?:\.\d+)?|""" +
        """第?\s*\d+(?:\.\d+)?\s*(?:巻|卷|册|冊|集)|\[\s*\d+(?:\.\d+)?\s*])""" +
        """(?=$|[\s._\-–—\]】)])""",
    option = RegexOption.IGNORE_CASE,
)

private val CHAPTER_PATTERN = Regex(
    pattern = """(?:^|[\s._\-–—\[【(])""" +
        """(?:ch(?:apter)?\.?\s*\d+(?:\.\d+)?|c\s*\d+(?:\.\d+)?|""" +
        """ep(?:isode)?\.?\s*\d+(?:\.\d+)?|#\s*\d+(?:\.\d+)?|""" +
        """第\s*\d+(?:\.\d+)?\s*[话話章回])""" +
        """(?=$|[\s._\-–—\]】)])""",
    option = RegexOption.IGNORE_CASE,
)

private val TRAILING_VOLUME_NUMBER_PATTERN = Regex("""(?:^|[\s._\-–—(])\d{1,4}(?:\.\d+)?\)?$""")

private val SERIES_TITLE_SEPARATOR = Regex("""\s+(?:-|－|–|—|\||·)\s+""")

private val MEDIA_EXTENSIONS = setOf("cbz", "zip", "cbr", "rar", "7z", "tar", "epub", "pdf")
