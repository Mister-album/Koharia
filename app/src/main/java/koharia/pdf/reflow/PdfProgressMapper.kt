package koharia.pdf.reflow

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

internal object PdfProgressMapper {
    fun preferredAnchor(manifest: PdfReflowManifest, locator: Locator?, savedJson: String?): String? {
        if (locator == null) return null
        locator.locations.fragments.firstOrNull { id -> manifest.blocks.any { it.id == id } }?.let { return it }
        val source = savedJson?.let { runCatching { JSONObject(it).optJSONObject("kohariaPdfSource") }.getOrNull() }
        if (source?.optString("sourceHash") != manifest.sourceHash ||
            source.optString("revision") != manifest.revision
        ) {
            return null
        }
        return source.optString("blockId").takeIf { id ->
            manifest.blocks.any { it.id == id && it.href == locator.href.toString() }
        }
    }

    fun initial(manifest: PdfReflowManifest, savedJson: String?, originalPage: Int): Locator? {
        val saved = savedJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val source = saved?.optJSONObject("kohariaPdfSource")
        if (source?.optString("revision") == manifest.revision &&
            source.optString("sourceHash") == manifest.sourceHash && source.optInt("page", -1) == originalPage
        ) {
            return runCatching { Locator.fromJSON(checkNotNull(saved)) }.getOrNull()
        }
        val sameSource = source?.optString("sourceHash") == manifest.sourceHash &&
            source.optInt("page", -1) == originalPage
        val candidates = manifest.blocks.filter { it.source.page == originalPage }
        val context = source?.optString("context").orEmpty()
        val restored = if (sameSource) {
            candidates.firstOrNull { context.isNotBlank() && it.context == context }
                ?: candidates.minByOrNull {
                    kotlin.math.abs(it.source.firstCharacter - checkNotNull(source).optInt("firstCharacter", 0))
                }
        } else {
            null
        }
        val target = restored
            ?: manifest.blocks.firstOrNull { it.source.page >= originalPage.coerceIn(0, manifest.pageCount - 1) }
            ?: manifest.blocks.lastOrNull() ?: return null
        return Locator(
            href = checkNotNull(Url(target.href)),
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(fragments = listOf(target.id)),
        )
    }

    fun block(manifest: PdfReflowManifest, locator: Locator, visibleId: String?): PdfMappedBlock? {
        val href = locator.href.toString().substringBefore('#').trimStart('/')
        val candidates = manifest.blocks.filter { it.href == href || href.endsWith("/${it.href}") }
        candidates.firstOrNull { it.id == visibleId }?.let { return it }
        locator.locations.fragments.firstNotNullOfOrNull { id ->
            candidates.firstOrNull { it.id == id }
        }?.let { return it }
        val progression = locator.locations.progression ?: 0.0
        return candidates.getOrNull(((candidates.size - 1) * progression).toInt().coerceAtLeast(0))
    }

    fun serialize(
        manifest: PdfReflowManifest,
        locator: Locator,
        block: PdfMappedBlock,
    ): String = locator.toJSON().apply {
        put(
            "kohariaPdfSource",
            JSONObject().put("sourceHash", manifest.sourceHash).put("revision", manifest.revision)
                .put("page", block.source.page).put("blockId", block.id)
                .put("firstCharacter", block.source.firstCharacter).put("context", block.context)
                .put(
                    "box",
                    JSONObject().put("left", block.source.box.left).put("top", block.source.box.top)
                        .put("right", block.source.box.right).put("bottom", block.source.box.bottom),
                ),
        )
    }.toString()
}
