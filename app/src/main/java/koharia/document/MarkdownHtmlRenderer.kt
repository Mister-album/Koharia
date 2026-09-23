package koharia.document

import logcat.LogPriority
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import tachiyomi.core.common.util.system.logcat

/** Converts Markdown (GFM) to HTML. Pure JVM apart from the [logcat] fallback diagnostic. */
internal object MarkdownHtmlRenderer {
    private val flavour = GFMFlavourDescriptor()

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        return try {
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        } catch (error: Exception) {
            // Catch Exception (not Throwable): a parser failure degrades to unrendered Markdown,
            // but Errors such as OutOfMemoryError on a near-limit file must propagate.
            logcat(LogPriority.WARN, error) { "[MarkdownHtmlRenderer] markdown render failed, using raw text" }
            markdown
        }
    }
}
