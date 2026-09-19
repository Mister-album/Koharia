package koharia.document

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/** Converts Markdown (GFM) to HTML. Pure JVM — no Android dependencies, so it is unit-testable. */
internal object MarkdownHtmlRenderer {
    private val flavour = GFMFlavourDescriptor()

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        return runCatching {
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        }.getOrDefault(markdown)
    }
}
