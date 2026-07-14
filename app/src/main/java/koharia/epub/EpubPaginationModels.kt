package koharia.epub

import koharia.epub.settings.EpubLayoutPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

enum class EpubPaginationPhase {
    CACHED,
    CALCULATING,
    READY,
    UNAVAILABLE,
}

internal data class EpubPaginationLayoutSnapshot(
    val readingMode: String,
    val pageDirection: String,
    val fontSize: Float,
    val lineHeight: Float,
    val paragraphSpacing: Float,
    val paragraphIndent: Float,
    val pageMargins: Float,
    val verticalMargins: Float,
    val fontFamily: String,
    val publisherStyles: Boolean,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val densityDpi: Int,
    val fontScale: Float,
    val webViewVersion: String,
) {
    val json: String
        get() = buildJsonObject {
            put("algorithmVersion", PAGINATION_ALGORITHM_VERSION)
            put("readiumVersion", READIUM_VERSION)
            put("readingMode", readingMode)
            put("pageDirection", pageDirection)
            put("fontSize", fontSize.toDouble())
            put("lineHeight", lineHeight.toDouble())
            put("paragraphSpacing", paragraphSpacing.toDouble())
            put("paragraphIndent", paragraphIndent.toDouble())
            put("pageMargins", pageMargins.toDouble())
            put("verticalMargins", verticalMargins.toDouble())
            put("fontFamily", fontFamily)
            put("publisherStyles", publisherStyles)
            put("viewportWidthPx", viewportWidthPx)
            put("viewportHeightPx", viewportHeightPx)
            put("densityDpi", densityDpi)
            put("fontScale", fontScale.toDouble())
            put("webViewVersion", webViewVersion)
        }.toString()

    val key: String
        get() = json.sha256()

    companion object {
        fun from(
            preferences: EpubLayoutPreferences,
            viewport: EpubPaginationViewport,
        ): EpubPaginationLayoutSnapshot {
            return EpubPaginationLayoutSnapshot(
                readingMode = preferences.readingMode.get().name,
                pageDirection = preferences.pageDirection.get().name,
                fontSize = preferences.fontSize.get(),
                lineHeight = preferences.lineHeight.get(),
                paragraphSpacing = preferences.paragraphSpacing.get(),
                paragraphIndent = preferences.paragraphIndent.get(),
                pageMargins = preferences.pageMargins.get(),
                verticalMargins = preferences.verticalMargins.get(),
                fontFamily = preferences.fontFamily.get().name,
                publisherStyles = preferences.publisherStyles.get(),
                viewportWidthPx = viewport.widthPx,
                viewportHeightPx = viewport.heightPx,
                densityDpi = viewport.densityDpi,
                fontScale = viewport.fontScale,
                webViewVersion = viewport.webViewVersion,
            )
        }
    }
}

data class EpubPaginationViewport(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val fontScale: Float,
    val webViewVersion: String,
)

internal data class EpubPaginationRequest(
    val generation: Long,
    val publicationKey: String,
    val layoutKey: String,
    val layoutSnapshotJson: String,
    val initialPageCounts: Map<String, Int>,
    val shouldScan: Boolean,
)

internal fun Map<String, Int>.toPageCountsJson(): String {
    return buildJsonObject {
        toSortedMap().forEach { (href, count) -> put(href, count) }
    }.toString()
}

internal fun String.toPageCounts(): Map<String, Int> {
    return runCatching {
        Json.parseToJsonElement(this).jsonObject.mapNotNull { (href, element) ->
            element.jsonPrimitive.intOrNull
                ?.takeIf { it > 0 }
                ?.let { href to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}

private fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

private const val PAGINATION_ALGORITHM_VERSION = 3
private const val READIUM_VERSION = "3.3.0"
