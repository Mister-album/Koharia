package koharia.document

import android.text.Html
import android.text.Spanned
import android.text.style.TypefaceSpan
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.UUID

/** Keeps structural positions and preformatted text through Android's HTML conversion. */
internal object MarkdownDocumentRenderer {
    data class Rendered(val text: Spanned, val headings: List<RawDocumentHeading>)

    fun render(html: String): Rendered {
        val document = Jsoup.parseBodyFragment(html.replace("\r\n", "\n").replace('\r', '\n'))
        document.outputSettings().prettyPrint(false)
        // Generated tag names cannot collide with raw HTML supplied by the document.
        val prefix = "koharia" + UUID.randomUUID().toString().replace("-", "")
        val code = mutableMapOf<String, Pair<String, Boolean>>()
        document.select("pre, code").toList().forEach { element ->
            if (element.parents().any { it.normalName() == "pre" }) return@forEach
            val tag = "${prefix}code${code.size}"
            code[tag] = element.wholeText() to (element.normalName() == "pre")
            element.replaceWith(Element(tag))
        }
        val levels = mutableMapOf<String, Int>()
        document.select("h1, h2, h3, h4, h5, h6").forEach { element ->
            val tag = "${prefix}heading${levels.size}"
            levels[tag] = element.normalName().last().digitToInt()
            val marker = Element(tag)
            element.childNodes().toList().forEach { marker.appendChild(it) }
            element.appendChild(marker)
        }
        val starts = mutableMapOf<String, Int>()
        val headings = mutableListOf<RawDocumentHeading>()
        val handler = Html.TagHandler { opening, tag, output, _ ->
            code[tag]?.let { (literal, block) ->
                if (opening) {
                    if (block && output.isNotEmpty() && output.last() != '\n') output.append('\n')
                    val start = output.length
                    output.append(literal)
                    output.setSpan(TypefaceSpan("monospace"), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (block && (output.isEmpty() || output.last() != '\n')) output.append('\n')
                }
            }
            levels[tag]?.let { level ->
                if (opening) {
                    starts[tag] = output.length
                } else {
                    val start = starts.remove(tag) ?: return@let
                    val raw = output.subSequence(start, output.length).toString()
                    val leading = raw.indexOfFirst { it != ' ' && it != '\n' && it != '\t' }
                    if (leading >= 0) {
                        val title = raw.substring(leading).trimEnd(' ', '\n', '\t')
                        headings += RawDocumentHeading(level, title, start + leading)
                    }
                }
            }
        }
        val text = Html.fromHtml(document.body().html(), Html.FROM_HTML_MODE_LEGACY, null, handler)
        return Rendered(text, headings.sortedBy { it.offset })
    }
}
