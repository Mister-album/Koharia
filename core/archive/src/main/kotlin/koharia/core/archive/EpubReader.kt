package koharia.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URLDecoder

/**
 * Wrapper over ArchiveReader to load files in epub format.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /** Returns the single image represented by each spine item of a Divina-compatible EPUB. */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    private fun getPagesFromDocument(document: Document): List<ManifestItem> {
        val manifest = document.select("*|manifest > *|item")
            .associate { item ->
                item.attr("id") to ManifestItem(
                    href = item.attr("href"),
                    mediaType = item.attr("media-type"),
                )
            }

        return document.select("*|spine > *|itemref")
            .mapNotNull { itemRef -> manifest[itemRef.attr("idref")] }
    }

    private fun getImagesFromPages(pages: List<ManifestItem>, packageHref: String): List<String> {
        if (pages.isEmpty()) return emptyList()
        val basePath = getParentDirectory(packageHref)
        val result = ArrayList<String>(pages.size)
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, URLDecoder.decode(page.href, Charsets.UTF_8.name()))
            if (page.mediaType.startsWith("image/", ignoreCase = true)) {
                result += entryPath
                return@forEach
            }

            val document = getInputStream(entryPath)?.use { Jsoup.parse(it, null, "") }
                ?: return emptyList()
            val imageBasePath = getParentDirectory(entryPath)
            val imagePaths = buildList {
                document.select("img[src]")
                    .mapTo(this) { image -> image.attr("src") }
                document.select("svg > image")
                    .mapNotNullTo(this) { image ->
                        image.attr("xlink:href").ifBlank { image.attr("href") }.ifBlank { null }
                    }
            }
                .map { imageHref -> resolveZipPath(imageBasePath, imageHref) }
                .distinct()
            if (imagePaths.size != 1) return emptyList()
            result += imagePaths.single()
        }

        return result.takeIf { it.size == pages.size }.orEmpty()
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String,
    )
}
