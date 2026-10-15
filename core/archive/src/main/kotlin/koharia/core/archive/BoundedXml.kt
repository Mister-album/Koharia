package koharia.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader

/** Validate resource budgets before building a metadata DOM; never return a truncated document. */
object BoundedXml {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_DEPTH = 256
    private const val MAX_TOKENS = 100_000
    private const val MAX_ATTRIBUTES = 100_000

    fun parse(content: String): Document {
        if (content.length > MAX_BYTES || content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) exceeded()
        validate { setInput(StringReader(content)) }
        return Jsoup.parse(content, "", Parser.xmlParser())
    }

    fun parse(input: InputStream, charsetName: String? = null): Document {
        val bytes = ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
                if (count == -1) break
                if (output.size() + count > MAX_BYTES) exceeded()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        validate { setInput(ByteArrayInputStream(bytes), charsetName) }
        return Jsoup.parse(ByteArrayInputStream(bytes), charsetName, "", Parser.xmlParser())
    }

    private fun validate(input: XmlPullParser.() -> Unit) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        // Token mode does not expand declared entities or fetch external DTDs.
        check(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) { "DTD processing must remain disabled" }
        parser.input()
        var tokens = 0
        var attributes = 0
        while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
            if (++tokens > MAX_TOKENS || parser.depth > MAX_DEPTH) exceeded()
            if (parser.eventType == XmlPullParser.START_TAG) {
                attributes += parser.attributeCount
                if (attributes > MAX_ATTRIBUTES) exceeded()
            }
        }
    }

    private fun exceeded(): Nothing = throw IOException("XML metadata exceeds the parsing limit")
}
