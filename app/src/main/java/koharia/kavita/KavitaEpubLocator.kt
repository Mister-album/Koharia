package koharia.kavita

import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

internal fun kavitaLocator(api: KavitaApiClient, state: KavitaReadingState): Locator {
    val page = state.progress.pageNum.coerceIn(0, (state.totalPages - 1).coerceAtLeast(0))
    val anchor = state.progress.bookScrollId.orEmpty()
    val locations = JSONObject().put(
        "totalProgression",
        state.progress.pageNum.toDouble() / state.totalPages.coerceAtLeast(1),
    )
    if (anchor.isNotBlank()) {
        locations.put("kavitaXPath", anchor)
        locations.put("kavitaRestore", anchor)
        if (!anchor.startsWith('/') && !anchor.startsWith("id(")) locations.put("fragments", JSONArray(listOf(anchor)))
    } else {
        locations.put("progression", 0.0)
    }
    return requireNotNull(
        Locator.fromJSON(
            JSONObject()
                .put("href", KavitaEpubPublicationService.pageUrl(api, state.ref.chapterId, page).toString())
                .put("type", "text/html").put("locations", locations),
        ),
    )
}

/** Capture a real visible DOM element, rather than deriving a fictitious anchor from layout page counts. */
internal suspend fun captureKavitaLocator(navigator: EpubNavigatorFragment, locator: Locator): Locator {
    if (!locator.href.toString().contains("koharia-epub/")) return locator
    val raw = navigator.evaluateJavascript(
        """
        (function() {
            var nodes = document.querySelectorAll('[data-koharia-kavita-path]');
            for (var i = 0; i < nodes.length; i++) {
                var n = nodes[i], r = n.getBoundingClientRect();
                if (n.children.length === 0 && r.width > 0 && r.height > 0 &&
                    r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth) {
                    return n.getAttribute('data-koharia-kavita-path');
                }
            }
            return null;
        })()
        """.trimIndent(),
    ) ?: return locator
    val anchor =
        runCatching { JSONArray("[$raw]").optString(0) }.getOrNull()?.takeIf { it.startsWith("//body") }
            ?: return locator
    val json = locator.toJSON()
    val locations = json.optJSONObject("locations") ?: JSONObject().also { json.put("locations", it) }
    locations.put("kavitaXPath", anchor)
    return Locator.fromJSON(json) ?: locator
}

internal suspend fun restoreKavitaLocator(navigator: EpubNavigatorFragment, locator: Locator): Boolean? {
    if (!locator.href.toString().contains("koharia-epub/")) return null
    val locations = locator.toJSON().optJSONObject("locations") ?: return null
    val anchor = locations.optString("kavitaRestore").ifBlank { locations.optString("kavitaXPath") }
        .takeIf(String::isNotBlank) ?: return null
    val path = JSONObject.quote(anchor)
    return navigator.evaluateJavascript(
        """
        (function() {
            var path = $path, target = null;
            if (path.indexOf('//body') === 0 || path.indexOf('/body') === 0) {
                var nodes = document.querySelectorAll('[data-koharia-kavita-path]');
                for (var i = 0; i < nodes.length; i++) {
                    if (nodes[i].getAttribute('data-koharia-kavita-path').toLowerCase() === path.toLowerCase()) {
                        target = nodes[i]; break;
                    }
                }
            } else if (path.indexOf('id(') === 0) {
                try { target = document.evaluate(path, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; } catch(e) {}
            } else { target = document.getElementById(path); }
            if (target) { target.scrollIntoView(); return true; }
            return false;
        })()
        """.trimIndent(),
    )?.toBooleanStrictOrNull()
}
