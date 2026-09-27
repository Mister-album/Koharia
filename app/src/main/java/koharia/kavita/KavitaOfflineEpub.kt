package koharia.kavita

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.ByteString.Companion.encodeUtf8
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports the same server page order and DOM anchors used by the online publication. */
class KavitaOfflineEpub(private val api: KavitaApiClient, private val catalog: KavitaCatalog) {
    suspend fun write(chapterId: Long, target: File, checkActive: () -> Unit) {
        if (!api.capabilities(api.getAccount(refresh = true)).downloads) {
            throw KavitaException(KavitaException.Reason.PERMISSION)
        }
        val info = catalog.book(chapterId)
        require(info.pages in 1..20_000)
        val assets = linkedMapOf<String, Pair<String, String>>()
        var totalBytes = 0L
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun write(name: String, bytes: ByteArray, stored: Boolean = false) {
                checkActive()
                totalBytes += bytes.size
                if (totalBytes > MAX_BYTES ||
                    target.parentFile!!.usableSpace < bytes.size
                ) {
                    throw IOException("Insufficient EPUB cache space")
                }
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            write("mimetype", "application/epub+zip".toByteArray(), true)
            val container = """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent()
            write("META-INF/container.xml", container.toByteArray())
            suspend fun asset(url: String): String {
                assets[url]?.let { return it.first }
                val expected = api.url("Book/$chapterId/book-resources")
                val resolved = expected.resolve(url) ?: throw IOException("Invalid EPUB resource")
                require(resolved.host == expected.host && isKavitaEpubResourceUrl(api, chapterId, resolved))
                val resource = fetchKavitaEpubResource(api, chapterId, resolved)
                val mediaType = resource.mediaType
                val suffix = resource.url.queryParameter("file")?.substringAfterLast('.', "")?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }.orEmpty()
                val name =
                    "assets/" + url.encodeUtf8().sha256().hex() +
                        suffix.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
                // Reserve before traversing imports so cyclic stylesheets cannot recurse forever.
                assets[url] = name to mediaType
                val bytes = if (mediaType == "text/css") {
                    val css = rewriteKavitaCss(resource.bytes.toString(Charsets.UTF_8), api, chapterId, resource.url)
                    val replacements = linkedMapOf<String, String>()
                    for (match in kavitaCssUrls.findAll(css)) {
                        val dependency = match.groupValues[2]
                        if (dependency.startsWith(api.virtualBase.toString())) {
                            replacements[dependency] =
                                try {
                                    asset(dependency).removePrefix("assets/")
                                } catch (error: KavitaException) {
                                    val font =
                                        dependency.substringAfterLast('.').lowercase() in
                                            setOf("ttf", "otf", "woff", "woff2")
                                    if (!font || error.status !in listOf(400, 404)) throw error
                                    ""
                                }
                        }
                    }
                    kavitaCssUrls.replace(css) {
                        "url(\"${replacements[it.groupValues[2]] ?: it.groupValues[2]}\")"
                    }.toByteArray()
                } else {
                    resource.bytes
                }
                currentCoroutineContext().ensureActive()
                write("OEBPS/koharia-epub/$name", bytes)
                return name
            }
            for (page in 0 until info.pages) {
                checkActive()
                currentCoroutineContext().ensureActive()
                val original = api.client.newCall(
                    api.request("Book/$chapterId/book-page", "page" to page),
                ).await().use {
                    KavitaApiClient.checkResponse(it)
                    boundedRead(it.body.byteStream(), 32 * 1024 * 1024, checkActive).toString(Charsets.UTF_8)
                }
                val html = if (original.trimStart().startsWith(
                        '"',
                    )
                ) {
                    api.json.decodeFromString<String>(original)
                } else {
                    original
                }
                val document = Jsoup.parse(rewriteKavitaHtml(html, api, chapterId))
                document.select("[src], [xlink:href], [poster], link[href], image[href]").forEach { element ->
                    for (attribute in listOf("src", "xlink:href", "href", "poster")) {
                        val value = element.attr(attribute)
                        if (value.startsWith(api.virtualBase.toString())) element.attr(attribute, asset(value))
                    }
                }
                for (element in document.select("[srcset]")) {
                    val mapped = mutableListOf<String>()
                    for (candidate in element.attr("srcset").split(',')) {
                        val parts = candidate.trim().split(Regex("\\s+"), limit = 2)
                        val url = parts.first()
                        val path = if (url.startsWith(api.virtualBase.toString())) asset(url) else url
                        mapped += path + parts.getOrNull(1)?.let { " $it" }.orEmpty()
                    }
                    element.attr("srcset", mapped.joinToString(", "))
                }
                val urls = Regex("""url\(\s*(['"]?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)
                for (element in document.select("style, [style]")) {
                    val style = if (element.tagName() == "style") element.data() else element.attr("style")
                    val replacements = linkedMapOf<String, String>()
                    for (match in urls.findAll(style)) {
                        val url = match.groupValues[2]
                        if (url.startsWith(api.virtualBase.toString())) replacements[url] = asset(url)
                    }
                    val changed = urls.replace(style) { match ->
                        "url(\"${replacements[match.groupValues[2]] ?: match.groupValues[2]}\")"
                    }
                    if (element.tagName() == "style") element.html(changed) else element.attr("style", changed)
                }
                document.select("a[href]").forEach { link ->
                    val href = link.attr("href")
                    KavitaEpubPublicationService.pageIndex(href)?.let {
                        link.attr(
                            "href",
                            "page-$it.html" +
                                href.substringAfter('#', "").takeIf(String::isNotBlank)?.let { fragment ->
                                    "#$fragment"
                                }.orEmpty(),
                        )
                    }
                }
                document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false)
                document.selectFirst("html")?.attr("xmlns", "http://www.w3.org/1999/xhtml")
                write("OEBPS/koharia-epub/page-$page.html", document.outerHtml().toByteArray())
            }
            val toc = catalog.toc(chapterId)
            val nav = buildString {
                append("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""")
                append("""<head><title>Contents</title></head><body><nav epub:type="toc"><ol>""")
                fun parts(items: List<KavitaBookPart>) {
                    for (item in items.filter { it.page in 0 until info.pages }) {
                        val href = "koharia-epub/page-${item.page}.html" +
                            item.part.takeIf(String::isNotBlank)?.let { "#$it" }.orEmpty()
                        append("""<li><a href="${xml(href)}">${xml(item.title)}</a>""")
                        if (item.children.isNotEmpty()) {
                            append("<ol>")
                            parts(item.children)
                            append("</ol>")
                        }
                        append("</li>")
                    }
                }
                if (toc.isEmpty()) {
                    parts((0 until info.pages).map { KavitaBookPart(title = "${it + 1}", page = it) })
                } else {
                    parts(toc)
                }
                append("</ol></nav></body></html>")
            }
            write("OEBPS/nav.xhtml", nav.toByteArray())
            val manifest = buildString {
                append("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">""")
                append("""<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                append("""<dc:identifier id="id">kavita-$chapterId</dc:identifier>""")
                append("""<dc:title>${xml(info.bookTitle)}</dc:title><dc:language>und</dc:language>""")
                append("""<meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata><manifest>""")
                append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
                for (page in 0 until info.pages) {
                    append("""<item id="p$page" href="koharia-epub/page-$page.html" """)
                    append("""media-type="application/xhtml+xml"/>""")
                }
                assets.values.forEachIndexed { index, (name, type) ->
                    append("""<item id="a$index" href="koharia-epub/${xml(name)}" media-type="${xml(type)}"/>""")
                }
                append("</manifest><spine>")
                for (page in 0 until info.pages) append("""<itemref idref="p$page"/>""")
                append("</spine></package>")
            }
            write("OEBPS/content.opf", manifest.toByteArray())
        }
    }
    private companion object {
        const val MAX_BYTES = 1024L * 1024 * 1024
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }
}

internal fun boundedRead(input: java.io.InputStream, limit: Int, checkActive: () -> Unit): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > limit) throw IOException("Kavita response exceeds resource limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
