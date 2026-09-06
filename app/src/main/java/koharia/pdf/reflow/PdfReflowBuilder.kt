@file:Suppress("ktlint:standard:max-line-length")

package koharia.pdf.reflow

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

internal object PdfReflowBuilder {
    private data class Line(val glyphs: List<PdfGlyph>, val box: PdfBox, val size: Float) {
        val text get() = glyphs.joinToString("") { it.text }
    }

    fun needsOriginalPage(page: PdfPageFacts): Boolean {
        val text = page.glyphs.filter { it.text.isNotBlank() }
        if (page.hasUnmappedText || page.rotation != 0 || page.width > page.height || text.size < 20) return true
        if (text.any { it.text == "\uFFFD" || (it.angle >= 0 && abs(it.angle) > 0.1f) }) return true
        val images = page.graphics.filter {
            it.kind in listOf(3, 4, 5) &&
                it.box.width * it.box.height > page.width * page.height * .005f
        }
        if (images.any { image -> text.count { image.box.intersects(it.box) } > 5 }) return true
        if (page.graphics.count {
                it.kind == 2 && (it.box.width > page.width * .3f || it.box.height > page.height * .3f)
            } >
            8
        ) {
            return true
        }
        // A large interior gap on several lines is a conservative signal for columns/tables.
        return lines(page.copy(glyphs = PdfInlineLayout.analyze(page).glyphs)).count { line ->
            line.glyphs.zipWithNext().any { (a, b) -> b.box.left - a.box.right > page.width * .16f }
        } > 3
    }

    private fun lines(page: PdfPageFacts): List<Line> {
        val groups = mutableListOf<MutableList<PdfGlyph>>()
        page.glyphs.filter { it.text !in listOf("\r", "\n") && it.box.height > 0 }.sortedBy {
            (
                it.box.top +
                    it.box.bottom
                ) /
                2
        }.forEach { glyph ->
            val center = (glyph.box.top + glyph.box.bottom) / 2
            val line = groups.lastOrNull()?.takeIf { existing ->
                val first = existing.first()
                // Punctuation occupies only a small part of the em square; use font size to keep it on its line.
                val tolerance = maxOf(page.styles[first.style].size, page.styles[glyph.style].size) * .6f
                abs(center - (first.box.top + first.box.bottom) / 2) < tolerance
            } ?: mutableListOf<PdfGlyph>().also(groups::add)
            line += glyph
        }
        return groups.map { group ->
            val glyphs = group.sortedBy { it.box.left }
            Line(
                glyphs,
                PdfBox(
                    glyphs.minOf {
                        it.box.left
                    },
                    glyphs.minOf { it.box.top },
                    glyphs.maxOf { it.box.right },
                    glyphs.maxOf { it.box.bottom },
                ),
                glyphs.map { page.styles[it.style].size }.sorted().let { it[it.size / 2] },
            )
        }.sortedBy { it.box.top }
    }

    fun build(
        directory: File,
        title: String,
        sourceHash: String,
        revision: String,
        checkCancellation: () -> Unit = {
        },
    ): PdfReflowManifest {
        val index = Json.decodeFromString<PdfExtractionIndex>(File(directory, "index.json").readText())
        val selectedPages = index.availablePages ?: (0 until index.pageCount).toList()
        val pageCache = object : LinkedHashMap<Int, PdfPageFacts>(8, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageFacts>?) = size > 8
        }
        val inlineCache = mutableMapOf<Int, PdfInlineLayout>()
        fun page(i: Int) = pageCache.getOrPut(i) {
            Json.decodeFromString<PdfPageFacts>(File(directory, "page-$i.json").readText())
        }
        fun inline(facts: PdfPageFacts): PdfInlineLayout {
            if (inlineCache.size >= 8 && facts.index !in inlineCache) inlineCache.clear()
            return inlineCache.getOrPut(facts.index) { PdfInlineLayout.analyze(facts) }
        }
        fun bodyPage(facts: PdfPageFacts, layout: PdfInlineLayout) = facts.copy(
            glyphs = layout.glyphs.filterNot { glyph ->
                facts.graphics.any { it.kind == -1 && it.asset != null && it.box.intersects(glyph.box) }
            },
        )
        val notes = mutableListOf<PdfFootnote>()
        val repeated = mutableMapOf<String, MutableSet<Int>>()
        fun signature(line: Line) = line.text.replace(Regex("[\\d０-９\\s]+"), "").take(160)
        selectedPages.forEach { i ->
            checkCancellation()
            val facts = page(i)
            if (facts.fallbackAsset ==
                null
            ) {
                lines(bodyPage(facts, inline(facts))).filter {
                    it.box.top < facts.height * .09f || it.box.bottom > facts.height * .92f
                }.forEach { line ->
                    repeated.getOrPut(signature(line)) { mutableSetOf() }.add(i)
                }
            }
        }
        val bodySizes = mutableListOf<Float>()
        val blocks = mutableListOf<PdfBlock>()
        selectedPages.forEach { i ->
            checkCancellation()
            val facts = page(i)
            val whole =
                PdfSourceSpan(
                    i,
                    0,
                    facts.glyphs.lastOrNull()?.index ?: 0,
                    PdfBox(0f, 0f, facts.width.toFloat(), facts.height.toFloat()),
                )
            if (facts.fallbackAsset != null) {
                blocks +=
                    PdfBlock("pdf-$i-image", "image", asset = facts.fallbackAsset, source = whole, confidence = 0f)
                return@forEach
            }
            val inline = inline(facts)
            notes += inline.notes
            val visible = lines(bodyPage(facts, inline)).filterNot { line ->
                val margin = line.box.top < facts.height * .09f || line.box.bottom > facts.height * .92f
                margin && (repeated[signature(line)]?.size ?: 0) >= 3
            }
            val bodySize =
                visible.filter { it.text.length >= 12 }.map { it.size }.sorted().let { it.getOrNull(it.size / 2) }
                    ?: 12f
            bodySizes += bodySize
            val left = visible.minOfOrNull { it.box.left } ?: 0f
            val paragraph = mutableListOf<PdfRun>()
            var paragraphSource: PdfSourceSpan? = null
            fun flush() {
                if (paragraph.isEmpty()) return
                val source = checkNotNull(paragraphSource)
                blocks +=
                    PdfBlock(
                        "pdf-${source.page}-c${source.firstCharacter}",
                        "paragraph",
                        paragraph.toList(),
                        source = source,
                        confidence = .8f,
                    )
                paragraph.clear()
                paragraphSource = null
            }
            var previous: Line? = null
            val images = facts.graphics.filter { it.asset != null }.sortedBy { it.box.top }.toMutableList()
            visible.forEach { line ->
                while (images.firstOrNull()?.let { it.box.top <= line.box.top } == true) {
                    flush()
                    val graphic = images.removeAt(0)
                    blocks +=
                        PdfBlock(
                            "pdf-$i-${graphic.asset}",
                            "image",
                            asset = graphic.asset,
                            source = whole.copy(box = graphic.box),
                        )
                }
                val heading = line.size > bodySize * 1.22f && line.text.trim().length in 2..100
                val indented = line.box.left - left > bodySize * .7f
                val separated = previous?.let { line.box.top - it.box.bottom > bodySize * .9f } ?: false
                if (heading || indented || separated) flush()
                val runs = mutableListOf<PdfRun>()
                var previousGlyph: PdfGlyph? = null
                line.glyphs.forEach { glyph ->
                    val style = facts.styles[glyph.style]
                    val previous = previousGlyph
                    val separatedWords = previous != null &&
                        inline.scripts[glyph.index] == null && inline.scripts[previous.index] == null &&
                        previous.text.last().let { it.code < 128 && it.isLetterOrDigit() } &&
                        glyph.text.first().let { it.code < 128 && it.isLetterOrDigit() } &&
                        glyph.box.left - previous.box.right > style.size * .18f
                    val value = (if (separatedWords) " " else "") + glyph.text
                    val old = runs.lastOrNull()
                    val ruby = inline.ruby[glyph.index]
                    val script = inline.scripts[glyph.index]
                    val noteId = inline.noteReferences[glyph.index]
                    if (old != null && old.style == style && old.ruby == null && ruby == null &&
                        old.script == script && old.noteId == noteId
                    ) {
                        runs[runs.lastIndex] = old.copy(
                            text = old.text + value,
                            source = old.source.copy(
                                lastCharacter = glyph.index,
                                box = old.source.box.copy(
                                    right = glyph.box.right,
                                    bottom = maxOf(old.source.box.bottom, glyph.box.bottom),
                                ),
                            ),
                        )
                    } else {
                        runs += PdfRun(
                            value,
                            style,
                            inline.sources[glyph.index] ?: PdfSourceSpan(i, glyph.index, glyph.index, glyph.box),
                            ruby,
                            script,
                            noteId,
                        )
                    }
                    previousGlyph = glyph
                }
                if (runs.isNotEmpty()) {
                    if (heading) {
                        blocks +=
                            PdfBlock(
                                "pdf-$i-c${runs.first().source.firstCharacter}",
                                "heading",
                                runs,
                                source = runs.first().source,
                                confidence = .75f,
                            )
                    } else {
                        val tail = paragraph.lastOrNull()?.text?.lastOrNull()
                        val head = runs.first().text.firstOrNull()
                        if (tail != null && head != null && tail.isLetterOrDigit() && head.isLetterOrDigit() &&
                            tail.code < 128 &&
                            head.code < 128
                        ) {
                            paragraph[paragraph.lastIndex] = paragraph.last().let { it.copy(text = it.text + " ") }
                        }
                        if (paragraphSource == null) paragraphSource = runs.first().source
                        paragraph += runs
                    }
                }
                previous = line
            }
            flush()
            images.forEach { graphic ->
                blocks +=
                    PdfBlock(
                        "pdf-$i-${graphic.asset}",
                        "image",
                        asset = graphic.asset,
                        source = whole.copy(box = graphic.box),
                    )
            }
        }
        require(
            index.availablePages != null || blocks.filter {
                it.kind != "image"
            }.sumOf { it.runs.sumOf { run -> run.text.length } } >= 80,
        ) { "This PDF cannot be reliably reflowed" }
        val medianSize = index.bodyFontSize ?: bodySizes.sorted().let { it.getOrNull(it.size / 2) } ?: 12f
        val mapped = mutableListOf<PdfMappedBlock>()
        val resources = mutableListOf<Pair<String, String>>()
        var part = mutableListOf<PdfBlock>()
        var length = 0
        val anchoredPages = mutableSetOf<Int>()
        fun writePart() {
            if (part.isEmpty()) return
            val href = "text/part-${resources.size}.xhtml"
            val body = buildString {
                part.forEach { block ->
                    if (anchoredPages.add(
                            block.source.page,
                        )
                    ) {
                        append("<span id=\"pdf-page-${block.source.page}\"></span>")
                    }
                    mapped +=
                        PdfMappedBlock(
                            block.id,
                            "EPUB/$href",
                            block.source,
                            block.runs.joinToString("") {
                                it.text
                            }.take(120),
                        )
                    if (block.kind == "image") {
                        val width = block.source.box.width.toInt().coerceAtLeast(1)
                        val height = block.source.box.height.toInt().coerceAtLeast(1)
                        append(
                            "<figure class=\"pdf-image\" id=\"${xml(
                                block.id,
                            )}\" data-pdf-anchor=\"${xml(block.id)}\">" +
                                "<img src=\"../images/${xml(
                                    checkNotNull(block.asset),
                                )}\" width=\"$width\" height=\"$height\" " +
                                "decoding=\"async\" alt=\"${xml("PDF ${block.source.page + 1}")}\"/></figure>",
                        )
                    } else {
                        val tag = if (block.kind == "heading") "h2" else "p"
                        append("<$tag id=\"${xml(block.id)}\">")
                        block.runs.forEachIndexed { n, run ->
                            val id = "${block.id}-r$n"
                            mapped += PdfMappedBlock(id, "EPUB/$href", run.source, run.text.take(120))
                            val ratio = if (run.script !=
                                null
                            ) {
                                1f
                            } else {
                                (run.style.size / medianSize).coerceIn(.65f, 2.5f)
                            }
                            val color = run.style.color?.takeIf { it and 0xffffff != 0 && it and 0xffffff != 0xffffff }
                            val css = buildString {
                                append("font-size:${java.lang.String.format(java.util.Locale.ROOT,"%.3f",ratio)}em;")
                                if ((run.style.weight ?: 400) >= 600) append("font-weight:bold;")
                                if (run.style.italic) append("font-style:italic;")
                                if (color !=
                                    null
                                ) {
                                    append("color:#${(color and 0xffffff).toString(16).padStart(6,'0')};")
                                }
                            }
                            val content = when {
                                run.ruby != null ->
                                    "<ruby>${xml(
                                        run.text,
                                    )}<rt data-pdf-characters=\"${run.ruby.characters.joinToString(
                                        ",",
                                    )}\">${xml(run.ruby.text)}</rt></ruby>"
                                run.script != null -> "<${run.script}>${xml(run.text)}</${run.script}>"
                                else -> xml(run.text)
                            }
                            val linked = run.noteId?.let {
                                "<a epub:type=\"noteref\" role=\"doc-noteref\" href=\"../notes.xhtml#$it\">$content</a>"
                            } ?: content
                            append("<span id=\"$id\" data-pdf-anchor=\"$id\" style=\"$css\">$linked</span>")
                        }
                        append("</$tag>")
                    }
                }
            }
            resources += href to xhtml(title, body, "../style.css")
            part = mutableListOf()
            length = 0
        }
        blocks.forEach { block ->
            if (block.kind == "image") {
                writePart()
                part += block
                writePart()
                return@forEach
            }
            val chapterBoundary =
                part.isNotEmpty() &&
                    index.outlines.any { it.page == block.source.page && part.last().source.page != block.source.page }
            if (length > 24000 || chapterBoundary) writePart()
            part += block
            length += block.runs.sumOf { it.text.length }
        }
        writePart()
        val manifest = PdfReflowManifest(revision, sourceHash, index.pageCount, mapped)
        File(directory, "manifest.json").writeText(Json.encodeToString(manifest))
        val toc = index.outlines.mapNotNull { outline ->
            mapped.firstOrNull { it.source.page >= outline.page }?.let {
                outline.title to
                    "${it.href.removePrefix("EPUB/")}#${it.id}"
            }
        }
            .ifEmpty { listOf(title to resources.first().first) }
        val assets = blocks.mapNotNull { it.asset }.distinct()
        ZipOutputStream(File(directory, "book.epub").outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray, stored: Boolean = false) {
                checkCancellation()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize =
                        entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray(), true)
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            val items =
                resources.mapIndexed { i, r ->
                    "<item id=\"p$i\" href=\"${r.first}\" media-type=\"application/xhtml+xml\"/>"
                }.joinToString("") +
                    assets.mapIndexed { i, a ->
                        "<item id=\"i$i\" href=\"images/${xml(a)}\" media-type=\"image/png\"/>"
                    }.joinToString("")
            val noteItem = if (notes.isNotEmpty()) {
                "<item id=\"notes\" href=\"notes.xhtml\" media-type=\"application/xhtml+xml\"/>"
            } else {
                ""
            }
            val spine = resources.indices.joinToString("") { "<itemref idref=\"p$it\"/>" }
            put(
                "EPUB/package.opf",
                """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:sha256:$revision</dc:identifier><dc:title>${xml(
                    title,
                )}</dc:title><dc:language>und</dc:language><meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="style.css" media-type="text/css"/>$items$noteItem</manifest><spine>$spine</spine></package>""".toByteArray(),
            )
            put(
                "EPUB/nav.xhtml",
                xhtml(
                    title,
                    "<nav epub:type=\"toc\" id=\"toc\"><h1>${xml(title)}</h1><ol>${toc.joinToString("") {
                        "<li><a href=\"${xml(it.second)}\">${xml(it.first)}</a></li>"
                    }}</ol></nav>",
                    "style.css",
                ).toByteArray(),
            )
            put(
                "EPUB/style.css",
                "body{line-height:1.7}p{margin:0 0 .5em;text-indent:2em}h2{font-size:1em;text-align:center;margin:1.2em 0}figure{margin:1em 0;text-align:center}img{max-width:100%;max-height:90vh;object-fit:contain}.pdf-image{margin:0;break-inside:avoid}.pdf-image img{display:block;width:100%;height:auto;max-height:85vh}a{color:inherit}ruby{ruby-position:over}rt{font-size:.5em}sup,sub{font-size:.65em;line-height:0}a[epub\\:type=noteref]{text-decoration:none}".toByteArray(),
            )
            if (notes.isNotEmpty()) {
                val content = notes.joinToString("") { note ->
                    "<aside epub:type=\"footnote\" role=\"doc-footnote\" id=\"${note.id}\"><p>${xml(
                        note.marker,
                    )} ${xml(note.text)}</p></aside>"
                }
                put("EPUB/notes.xhtml", xhtml(title, content, "style.css").toByteArray())
            }
            resources.forEach { put("EPUB/${it.first}", it.second.toByteArray()) }
            assets.forEach { put("EPUB/images/$it", File(directory, it).readBytes(), stored = true) }
        }
        return manifest
    }

    internal fun xhtml(title: String, body: String, css: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${xml(
        title,
    )}</title><link rel="stylesheet" type="text/css" href="$css"/></head><body>$body</body></html>"""
    internal fun xml(
        text: String,
    ) = text.filter {
        it >= ' ' || it in "\n\r\t"
    }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
