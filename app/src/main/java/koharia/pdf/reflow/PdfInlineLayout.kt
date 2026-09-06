package koharia.pdf.reflow

import kotlin.math.abs

internal data class PdfInlineLayout(
    val glyphs: List<PdfGlyph>,
    val ruby: Map<Int, PdfRuby>,
    val scripts: Map<Int, String>,
    val sources: Map<Int, PdfSourceSpan>,
    val noteReferences: Map<Int, String>,
    val notes: List<PdfFootnote>,
    val originalRegions: List<PdfBox>,
) {
    companion object {
        fun analyze(page: PdfPageFacts): PdfInlineLayout {
            val visible = page.glyphs.filter { it.text.isNotBlank() && it.box.height > 0 }
            val bodySize = visible.map { page.styles[it.style].size }.sorted().let { it.getOrNull(it.size / 2) } ?: 12f
            val normal = visible.filter { page.styles[it.style].size >= bodySize * .8f }
            val small = visible.filter { page.styles[it.style].size < bodySize * .72f }
            val removed = mutableSetOf<Int>()
            val replacements = mutableMapOf<Int, PdfGlyph>()
            val rubies = mutableMapOf<Int, PdfRuby>()
            val scripts = mutableMapOf<Int, String>()
            val sources = mutableMapOf<Int, PdfSourceSpan>()
            val references = mutableMapOf<Int, String>()
            val regions = mutableListOf<PdfBox>()
            val notes = mutableListOf<PdfFootnote>()

            fun span(glyphs: List<PdfGlyph>) = PdfSourceSpan(
                page.index,
                glyphs.minOf { it.index },
                glyphs.maxOf { it.index },
                bounds(glyphs),
            )
            fun plainText(glyphs: List<PdfGlyph>) = buildString {
                var previous: PdfGlyph? = null
                glyphs.forEach { glyph ->
                    previous?.let { before ->
                        if (before.text.last().let { it.code < 128 && it.isLetterOrDigit() } &&
                            glyph.text.first().let { it.code < 128 && it.isLetterOrDigit() } &&
                            (
                                glyph.box.left - before.box.right > page.styles[glyph.style].size * .2f ||
                                    glyph.box.top - before.box.bottom > 1f
                                )
                        ) {
                            append(' ')
                        }
                    }
                    append(glyph.text)
                    previous = glyph
                }
            }
            val groups = rows(small).flatMap { line ->
                val result = mutableListOf<MutableList<PdfGlyph>>()
                line.sortedBy { it.box.left }.forEach { glyph ->
                    val previous = result.lastOrNull()
                    if (previous == null || glyph.box.left - previous.last().box.right > bodySize * .8f) {
                        result += mutableListOf(glyph)
                    } else {
                        previous += glyph
                    }
                }
                result
            }
            groups.forEach { annotation ->
                val box = bounds(annotation)
                val value = annotation.joinToString("") { it.text }
                if (box.top < page.height * .09f || box.bottom > page.height * .94f) return@forEach
                val below = normal.filter { base ->
                    base.index !in removed && base.index !in rubies &&
                        box.left < base.box.right && box.right > base.box.left &&
                        box.bottom <= base.box.top + bodySize * .4f && box.bottom >= base.box.top - bodySize * .8f
                }.sortedBy { it.box.left }
                val marker = marker(value)
                if (below.isNotEmpty() && marker == null) {
                    val baseRows = rows(below)
                    if (baseRows.size == 1) {
                        val first = below.first()
                        val baseBox = bounds(below)
                        removed += below.drop(1).map { it.index }
                        removed += annotation.map { it.index }
                        replacements[first.index] = first.copy(text = below.joinToString("") { it.text }, box = baseBox)
                        rubies[first.index] = PdfRuby(value, span(annotation), annotation.map { it.index })
                        sources[first.index] = span(below)
                        return@forEach
                    }
                    regions += bounds(below + annotation)
                    return@forEach
                }
                val adjacent = normal.filter { base ->
                    base.box.right <= box.left + bodySize * .15f && box.left - base.box.right < bodySize * .85f &&
                        abs((base.box.top + base.box.bottom - box.top - box.bottom) / 2) < bodySize
                }.minByOrNull { abs(it.box.right - box.left) }
                if (adjacent != null) {
                    val center = (box.top + box.bottom) / 2
                    val baseCenter = (adjacent.box.top + adjacent.box.bottom) / 2
                    if (abs(center - baseCenter) > bodySize * .15f) {
                        annotation.forEach { glyph ->
                            scripts[glyph.index] = if (center < baseCenter) "sup" else "sub"
                            sources[glyph.index] = span(listOf(glyph))
                            replacements[glyph.index] = glyph.copy(
                                box = glyph.box.copy(top = adjacent.box.top, bottom = adjacent.box.bottom),
                            )
                        }
                    }
                }
            }

            // Only remove a footer note when its marker has a matching raised reference on this page.
            val footer = rows(
                visible.filter {
                    it.box.top > page.height * .7f && page.styles[it.style].size < bodySize * .85f
                },
            )
            val pending = mutableListOf<PdfGlyph>()
            fun finishNote() {
                if (pending.isEmpty()) return
                val value = plainText(pending)
                val match = Regex("^([0-9０-９①-⑳*†‡]+)[.)、：:]?\\s*(.+)$").find(value)
                val number = match?.groupValues?.get(1)?.let(::marker)
                val ref = groups.firstOrNull { group ->
                    marker(group.joinToString("") { it.text }) == number && number != null &&
                        group.all { scripts[it.index] == "sup" } && bounds(group).top < bounds(pending).top
                }
                if (match != null && ref != null) {
                    val id = "pdf-${page.index}-note-${pending.first().index}"
                    notes += PdfFootnote(id, checkNotNull(number), match.groupValues[2], span(pending))
                    ref.forEach { references[it.index] = id }
                    removed += pending.map { it.index }
                }
                pending.clear()
            }
            footer.forEach { row ->
                val text = row.joinToString("") { it.text }
                if (Regex("^[0-9０-９①-⑳*†‡]").containsMatchIn(text)) finishNote()
                pending += row
            }
            finishNote()
            page.annotations.forEach { annotation ->
                val anchor = visible.filter { it.index !in removed && it.index !in references }.minByOrNull {
                    if (it.box.intersects(annotation.box)) {
                        0f
                    } else {
                        abs(it.box.left - annotation.box.left) + abs(it.box.top - annotation.box.top)
                    }
                } ?: return@forEach
                val id = "pdf-${page.index}-annotation-${annotation.index}"
                notes += PdfFootnote(id, "", annotation.text, span(listOf(anchor)))
                references[anchor.index] = id
            }
            return PdfInlineLayout(
                page.glyphs.filter { it.index !in removed }.map { replacements[it.index] ?: it },
                rubies,
                scripts,
                sources,
                references,
                notes,
                regions,
            )
        }

        private fun marker(value: String): String? {
            val normalized = value.trim().map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
            return normalized.takeIf { it.matches(Regex("[0-9①-⑳*†‡]{1,3}")) }
        }

        private fun rows(glyphs: List<PdfGlyph>): List<List<PdfGlyph>> {
            val rows = mutableListOf<MutableList<PdfGlyph>>()
            glyphs.sortedBy { (it.box.top + it.box.bottom) / 2 }.forEach { glyph ->
                val center = (glyph.box.top + glyph.box.bottom) / 2
                val row = rows.lastOrNull()?.takeIf {
                    abs(center - (it.first().box.top + it.first().box.bottom) / 2) <
                        maxOf(it.first().box.height, glyph.box.height, 2f) * .65f
                } ?: mutableListOf<PdfGlyph>().also(rows::add)
                row += glyph
            }
            return rows.map { it.sortedBy { g -> g.box.left } }
        }

        private fun bounds(glyphs: List<PdfGlyph>) = PdfBox(
            glyphs.minOf { it.box.left },
            glyphs.minOf { it.box.top },
            glyphs.maxOf { it.box.right },
            glyphs.maxOf { it.box.bottom },
        )
    }
}
