package koharia.epub.replacement

import koharia.domain.textreplacement.EffectiveRuleSet
import koharia.domain.textreplacement.TextReplacementEngine
import koharia.domain.textreplacement.TextReplacementResult
import koharia.epub.isSameEpubResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.map
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ReplacementPublication(val rules: EffectiveRuleSet) {
    private var positions: () -> List<Locator> = { emptyList() }
    private val engine = TextReplacementEngine(rules)
    private val documents = ReplacementDocumentCache()
    internal var originalResource: ((Url) -> Resource?)? = null
    private val failures = MutableStateFlow<Set<String>>(emptySet())
    val errors = failures.asStateFlow()

    suspend fun ensureMapped(publication: Publication, href: Url, active: Boolean = false) {
        if (rules.rules.isEmpty()) return
        if (active) documents.activate(href.toString())
        if (documents.contains(href.toString())) return
        val original = originalResource
        val resource = if (original != null) original(href) else publication.get(href)
        if (resource == null) return
        try {
            val bytes = resource.read().getOrNull() ?: return
            if (original != null) {
                val link = publication.readingOrder.find { it.href.toString().isSameEpubResource(href.toString()) }
                transform(bytes, href.toString(), isXhtml(href, link?.mediaType?.toString()))
            }
        } finally {
            resource.close()
        }
    }

    fun sample(href: String?): String = href?.let { find(it)?.mapping?.original }?.take(12000).orEmpty()

    suspend fun loadPreview(publication: Publication, href: String?): ReplacementPreviewDocument? {
        val link = publication.readingOrder.find { it.href.toString().isSameEpubResource(href.orEmpty()) }
            ?: return null
        val xhtml = link.mediaType?.toString()?.contains("xhtml") == true
        href?.let(::find)?.let { return ReplacementPreviewDocument(it.originalDom.outerHtml(), xhtml) }
        val resource = originalResource?.invoke(link.url()) ?: publication.get(link) ?: return null
        val bytes = try {
            resource.read().getOrNull()
        } finally {
            resource.close()
        } ?: return null
        href?.let(::find)?.let { return ReplacementPreviewDocument(it.originalDom.outerHtml(), xhtml) }
        val document = org.jsoup.Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, "")
        return ReplacementPreviewDocument(document.outerHtml(), xhtml)
    }

    suspend fun loadSample(publication: Publication, href: String?): String {
        sample(href).takeIf { it.isNotEmpty() }?.let { return it }
        val link = publication.readingOrder.find { candidate ->
            val key = normalize(candidate.href.toString())
            val wanted = normalize(href.orEmpty())
            key.isSameEpubResource(wanted)
        } ?: return ""
        val resource = originalResource?.invoke(link.url()) ?: publication.get(link) ?: return ""
        val bytes = try {
            resource.read().getOrNull()
        } finally {
            resource.close()
        } ?: return ""
        sample(href).takeIf { it.isNotEmpty() }?.let { return it }
        return org.jsoup.Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, "").body().wholeText().take(12000)
    }

    internal fun transform(bytes: ByteArray, href: String, xhtml: Boolean): ByteArray {
        if (rules.rules.isEmpty()) return bytes
        return try {
            val bom = when {
                bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> 2 to Charsets.UTF_16BE
                bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> 2 to Charsets.UTF_16LE
                bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> 3 to Charsets.UTF_8
                else -> 0 to Charsets.UTF_8
            }
            val decoded = bom.second.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, bom.first, bytes.size - bom.first)).toString()
            val result = replaceDocument(decoded, engine, xhtml)
            documents.put(href, result)
            bytes.copyOfRange(0, bom.first) + result.html.toByteArray(bom.second)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val resource = runCatching { java.net.URI(href).path }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: TextReplacementService.identity(href).take(8)
            failures.update { it + resource }
            bytes
        }
    }

    private fun find(href: String): ReplacementDocument? = documents.get(href)

    fun clear() = documents.clear()

    private fun isXhtml(href: Url, mediaType: String?): Boolean = mediaType?.contains("xhtml") == true ||
        href.toString().substringBefore('#').substringBefore('?').substringAfterLast('.') in setOf("xhtml", "xht")

    /** Text quotes remain in original coordinates on disk. Element anchors are never renamed. */
    fun mapLocator(locator: Locator, toOriginal: Boolean): Locator {
        val document = find(locator.href.toString()) ?: return locator
        val mapping = document.mapping
        if (mapping.edits.isEmpty()) return locator
        val coordinates = locator.locations.otherLocations[COORDINATES]
        if (toOriginal && coordinates == "original") return locator
        if (!toOriginal && coordinates == rules.fingerprint) return locator
        val locations = locator.locations.otherLocations.toMutableMap()
        locations[COORDINATES] = if (toOriginal) "original" else rules.fingerprint
        val dom = if (toOriginal) document.displayDom else document.originalDom
        val range = locations["domRange"] as? Map<*, *>
        if (range != null) {
            var valid = true
            val mappedRange = range.mapValues { (_, raw) ->
                val point = raw as? Map<*, *>
                val selector = point?.get("cssSelector") as? String
                val index = (point?.get("textNodeIndex") as? Number)?.toInt()
                val offset = (point?.get("charOffset") as? Number)?.toInt()
                val canonical = selector?.let { runCatching { dom.selectFirst(it)?.cssSelector() }.getOrNull() }
                val node = document.nodes.find {
                    it.selector == canonical && (if (toOriginal) it.displayIndex else it.originalIndex) == index
                }
                if (node != null && !toOriginal && node.displayIndex == null) {
                    val nodeIndex = document.nodes.indexOf(node)
                    val next = document.nodes.drop(nodeIndex + 1).firstOrNull { it.displayIndex != null }
                    val previous = document.nodes.take(nodeIndex).lastOrNull { it.displayIndex != null }
                    val nearest = next ?: previous
                    if (nearest != null) {
                        mapOf(
                            "cssSelector" to nearest.selector,
                            "textNodeIndex" to checkNotNull(nearest.displayIndex),
                            "charOffset" to if (next != null) 0 else nearest.mapping.text.length,
                        )
                    } else {
                        valid = false
                        raw
                    }
                } else if (node == null) {
                    valid = false
                    if (selector != null) locations["cssSelector"] = selector
                    raw
                } else {
                    checkNotNull(point).entries.associate { it.key.toString() to it.value }.toMutableMap().apply {
                        put("textNodeIndex", if (toOriginal) node.originalIndex else node.displayIndex)
                        if (offset != null) {
                            put(
                                "charOffset",
                                if (toOriginal) node.mapping.toOriginal(offset) else node.mapping.toDisplay(offset),
                            )
                        }
                    }
                }
            }
            if (valid) locations["domRange"] = mappedRange else locations.remove("domRange")
        }
        // Character-bearing CFIs cannot be reused after text lengths change; retain element anchors.
        locations.remove("partialCfi")
        val progression = locator.locations.progression?.let { value ->
            val inputLength = if (toOriginal) mapping.text.length else mapping.original.length
            val outputLength = if (toOriginal) mapping.original.length else mapping.text.length
            val offset = (value * inputLength).toInt()
            val mapped = if (toOriginal) mapping.toOriginal(offset) else mapping.toDisplay(offset)
            if (outputLength == 0) 0.0 else (mapped.toDouble() / outputLength).coerceIn(0.0, 1.0)
        }
        val groups = positions().groupBy { normalize(it.href.toString()) }.entries.toList()
        val resourceKey = normalize(locator.href.toString())
        val groupIndex = groups.indexOfFirst {
            it.key.isSameEpubResource(resourceKey)
        }
        val beginProgress = groups.getOrNull(groupIndex)?.value?.firstOrNull()?.locations?.totalProgression
        val endProgress = groups.getOrNull(groupIndex + 1)?.value?.firstOrNull()?.locations?.totalProgression ?: 1.0
        val total = if (groupIndex >= 0 && beginProgress != null &&
            progression != null
        ) {
            beginProgress + progression * (endProgress - beginProgress)
        } else {
            locator.locations.totalProgression
        }
        val base = locator.copy(
            locations = locator.locations.copy(
                fragments = locator.locations.fragments.filterNot { it.startsWith("epubcfi(") },
                otherLocations = locations,
                progression = progression,
                totalProgression = total,
            ),
        )
        val input = if (toOriginal) mapping.text else mapping.original
        val output = if (toOriginal) mapping.original else mapping.text
        val quote = locator.text.highlight
        val start = quote?.takeIf { it.isNotEmpty() }?.let { input.indexOf(it) } ?: -1
        if (start < 0) return base.copy(text = Locator.Text())
        // Ambiguous quotes must use the retained structural anchor/progression instead.
        if (input.indexOf(checkNotNull(quote), start + 1) >= 0) return base.copy(text = Locator.Text())
        val map: (Int) -> Int = if (toOriginal) mapping::toOriginal else mapping::toDisplay
        val begin = map(start)
        val end = map(start + quote.length).coerceAtLeast(begin)
        return base.copy(
            text = Locator.Text(
                before = output.substring((begin - 32).coerceAtLeast(0), begin),
                highlight = output.substring(begin, end).takeIf { it.isNotEmpty() },
                after = output.substring(end, (end + 32).coerceAtMost(output.length)),
            ),
        )
    }

    private fun normalize(href: String) = href.substringBefore('#').substringBefore('?').trimStart('/')

    companion object {
        private const val COORDINATES = "kohariaTextCoordinates"
        private val sessions = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<
                Publication,
                ReplacementPublication,
                >(),
        )
        fun attach(
            publication: Publication,
            replacement: ReplacementPublication,
            positions: () -> List<Locator> = {
                emptyList()
            },
        ) {
            replacement.positions = positions
            sessions[publication] = replacement
        }
        fun of(publication: Publication): ReplacementPublication? = sessions[publication]
    }
}

internal fun Publication.Builder.installTextReplacement(replacement: ReplacementPublication) {
    // The opener can invoke its builder callback more than once. This instance belongs to one
    // session: never wrap its transformed container again or replace its original-resource reader.
    if (replacement.rules.rules.isEmpty() || replacement.originalResource != null) return
    val original = container
    replacement.originalResource = { href ->
        val resourceHref = href.removeFragment()
        original[resourceHref] ?: original[resourceHref.removeQuery()]
    }
    val publicationManifest = manifest
    // Keep authoritative position weights tied to original resources, not replacement byte length.
    servicesBuilder.positionsServiceFactory?.let { factory ->
        servicesBuilder.positionsServiceFactory = { context ->
            factory(Publication.Service.Context(context.manifest, original, context.services))
        }
    }
    container = object : Container<Resource> {
        override val sourceUrl = original.sourceUrl
        override val entries = original.entries
        override fun get(url: Url): Resource? {
            val resource = original[url] ?: return null
            val mediaType = publicationManifest.linkWithHref(url)?.mediaType
            val extension = url.toString().substringBefore('?').substringBefore('#').substringAfterLast('.')
            if (mediaType?.isHtml != true && extension !in setOf("html", "htm", "xhtml", "xht")) return resource
            return resource.map { bytes ->
                Try.success(
                    replacement.transform(
                        bytes,
                        url.toString(),
                        mediaType?.toString()?.contains("xhtml") == true || extension in setOf(
                            "xhtml",
                            "xht",
                        ),
                    ),
                )
            }
        }
        override fun close() = original.close()
    }
}
