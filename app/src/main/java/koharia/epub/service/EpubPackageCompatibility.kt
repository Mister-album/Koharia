package koharia.epub.service

import koharia.core.archive.BoundedXml
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.ContainerAsset
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.map

/** Normalize malformed package metadata before Readium resolves the spine, without rewriting the ZIP. */
internal fun Asset.withEpubPackageCompatibility(): Asset {
    if (this !is ContainerAsset || container is EpubPackageCompatibilityContainer) return this
    return ContainerAsset(format, EpubPackageCompatibilityContainer(container))
}

private class EpubPackageCompatibilityContainer(
    private val delegate: Container<Resource>,
) : Container<Resource> by delegate {
    override fun get(url: Url): Resource? {
        val resource = delegate[url] ?: return null
        if (!url.toString().substringBefore('?').substringBefore('#').endsWith(".opf", ignoreCase = true)) {
            return resource
        }
        return resource.map { Try.success(it.normalizeEpubPackageNamespaces()) }
    }
}

internal fun ByteArray.normalizeEpubPackageNamespaces(): ByteArray {
    // Keep the normal path byte-for-byte unchanged. BoundedXml does not resolve external entities.
    val document = runCatching { BoundedXml.parse(inputStream()) }.getOrNull() ?: return this
    val root = document.children().singleOrNull() ?: return this
    if (root.tagName() != "package" || root.attr("xmlns") != "http://www.idpf.org/2007/opf") return this
    val manifest = root.children().singleOrNull { it.tagName() == "manifest" } ?: return this
    if (manifest.hasAttr("xmlns") && manifest.attr("xmlns") != root.attr("xmlns")) return this
    val malformedItems = manifest.children().filter {
        it.tagName() == "item" && it.attr("xmlns") == "http://www.w3.org/1999/xhtml"
    }
    if (malformedItems.isEmpty()) return this
    malformedItems.forEach { it.removeAttr("xmlns") }
    document.charset(Charsets.UTF_8)
    document.outputSettings().prettyPrint(false)
    return document.outerHtml().toByteArray(Charsets.UTF_8)
}
