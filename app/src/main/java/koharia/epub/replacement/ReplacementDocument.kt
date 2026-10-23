package koharia.epub.replacement

import koharia.domain.textreplacement.TextReplacementEdit
import koharia.domain.textreplacement.TextReplacementEngine
import koharia.domain.textreplacement.TextReplacementResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** The preview runs the same DOM segmentation as the publication transformer. */
class ReplacementPreviewDocument(val html: String, val xhtml: Boolean) {
    fun preview(rules: koharia.domain.textreplacement.EffectiveRuleSet): TextReplacementResult =
        replaceDocument(html, TextReplacementEngine(rules), xhtml).mapping

    val original: String by lazy { preview(koharia.domain.textreplacement.EffectiveRuleSet.EMPTY).original }
}

internal data class ReplacementNodeMapping(
    val selector: String,
    val originalIndex: Int,
    val displayIndex: Int?,
    val mapping: TextReplacementResult,
)

internal data class ReplacementDocument(
    val html: String,
    val mapping: TextReplacementResult,
    val originalDom: org.jsoup.nodes.Document,
    val displayDom: org.jsoup.nodes.Document,
    val nodes: List<ReplacementNodeMapping>,
)

/** Retains element identity and attributes; never treats replacement text as markup. */
internal fun replaceDocument(html: String, engine: TextReplacementEngine, xhtml: Boolean): ReplacementDocument {
    val document = Jsoup.parse(html, "", if (xhtml) Parser.xmlParser() else Parser.htmlParser())
    document.outputSettings().prettyPrint(false)
    val body = document.selectFirst("body") ?: error("missing_body")
    val originalDom = document.clone()
    val nodeResults = mutableListOf<Triple<TextNode, Int, TextReplacementResult>>()
    val allOriginal = StringBuilder()
    val allDisplay = StringBuilder()
    val allEdits = mutableListOf<TextReplacementEdit>()
    val pending = mutableListOf<TextNode>()
    fun flush() {
        if (pending.isEmpty()) return
        val original = pending.joinToString("") { it.wholeText }
        val result = engine.replace(original)
        val base = allOriginal.length
        allEdits += result.edits.map { it.copy(start = it.start + base, end = it.end + base) }
        allOriginal.append(original)
        allDisplay.append(result.text)
        var offset = 0
        pending.forEach { node ->
            val text = node.wholeText
            val start = offset
            val end = offset + text.length
            val replaced = StringBuilder()
            val localEdits = mutableListOf<TextReplacementEdit>()
            var cursor = start
            result.edits.filter { it.start < end && it.end > start }.forEach { edit ->
                if (edit.start > cursor) replaced.append(original, cursor, edit.start.coerceAtMost(end))
                if (edit.start >= start) replaced.append(edit.text)
                localEdits += edit.copy(
                    start = (edit.start - start).coerceAtLeast(0),
                    end = (edit.end - start).coerceAtMost(text.length),
                    text = if (edit.start >= start) edit.text else "",
                )
                cursor = edit.end.coerceAtMost(end)
            }
            if (cursor < end) replaced.append(original, cursor, end)
            nodeResults += Triple(
                node,
                node.siblingIndex(),
                TextReplacementResult(
                    text,
                    replaced.toString(),
                    localEdits,
                ),
            )
            node.text(replaced.toString())
            offset = end
        }
        pending.clear()
    }
    fun boundary() {
        flush()
        allOriginal.append('\n')
        allDisplay.append('\n')
    }
    fun visit(node: Node) {
        when (node) {
            is TextNode -> pending += node
            is Element -> {
                val tag = node.normalName()
                if (tag in setOf("script", "style", "noscript", "svg", "math")) {
                    boundary()
                    return
                }
                val isolated = tag in setOf("br", "img", "ruby", "rt", "rp", "iframe", "audio", "video") ||
                    node.attr("epub:type").split(' ').contains("noteref") || node.attr("role") == "doc-noteref"
                val block = node.tag().isBlock || isolated
                if (block) boundary()
                if (isolated) {
                    val text = node.wholeText()
                    allOriginal.append(text)
                    allDisplay.append(text)
                } else {
                    node.childNodes().forEach(::visit)
                }
                if (block) boundary()
            }
        }
    }
    visit(body)
    flush()
    val nodes = nodeResults.map { (node, originalIndex, result) ->
        val parent = checkNotNull(node.parent() as? Element)
        val displayIndex = if (node.wholeText.isEmpty()) {
            null
        } else {
            parent.childNodes()
                .take(node.siblingIndex()).count { it !is TextNode || it.wholeText.isNotEmpty() }
        }
        ReplacementNodeMapping(parent.cssSelector(), originalIndex, displayIndex, result)
    }
    return ReplacementDocument(
        if (allEdits.isEmpty()) html else document.outerHtml(),
        TextReplacementResult(allOriginal.toString(), allDisplay.toString(), allEdits),
        originalDom,
        document,
        nodes,
    )
}
