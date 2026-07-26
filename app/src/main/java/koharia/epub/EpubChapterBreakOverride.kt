package koharia.epub

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.readium.r2.shared.publication.Link

internal const val EPUB_CHAPTER_BREAK_STYLE_ID = "koharia-chapter-break"
internal const val EPUB_CHAPTER_BREAK_TARGET_ATTRIBUTE = "data-koharia-chapter-break-target"

internal fun List<Link>.flattenEpubTocHrefs(): List<String> =
    flatMap { link ->
        buildList {
            add(link.href.toString())
            addAll(link.children.flattenEpubTocHrefs())
        }
    }.distinct()

internal fun buildEpubChapterBreakOverrideScript(
    tocHrefs: List<String>,
    enabled: Boolean,
): String {
    val resetScript = """
        var style = document.getElementById('$EPUB_CHAPTER_BREAK_STYLE_ID');
        if (style) style.remove();
        Array.from(document.querySelectorAll('[$EPUB_CHAPTER_BREAK_TARGET_ATTRIBUTE]')).forEach(function(element) {
            element.removeAttribute('$EPUB_CHAPTER_BREAK_TARGET_ATTRIBUTE');
        });
    """.trimIndent()
    if (!enabled || tocHrefs.isEmpty()) {
        return """
            (function() {
                if (!document.documentElement || document.documentElement.localName.toLowerCase() !== 'html') return false;
                $resetScript
                return true;
            })()
        """.trimIndent()
    }

    val tocHrefsJson = buildJsonArray {
        tocHrefs.forEach { add(JsonPrimitive(it)) }
    }
    return """
        (function() {
            if (!document.documentElement || document.documentElement.localName.toLowerCase() !== 'html') return false;
            $resetScript
            style = document.createElementNS('http://www.w3.org/1999/xhtml', 'style');
            style.id = '$EPUB_CHAPTER_BREAK_STYLE_ID';
            style.setAttribute('type', 'text/css');
            style.textContent =
                'html:root:root:root [$EPUB_CHAPTER_BREAK_TARGET_ATTRIBUTE] { ' +
                '-webkit-column-break-before: always !important; break-before: column !important; }';
            (document.head || document.documentElement).appendChild(style);

            var tocHrefs = $tocHrefsJson;
            function decodeComponent(value) {
                try { return decodeURIComponent(value); } catch (_) { return value; }
            }
            function normalizeResource(value) {
                return decodeComponent(String(value || '').split('#')[0].split('?')[0])
                    .replace(/\\/g, '/')
                    .replace(/^\/+/, '');
            }
            function isSameResource(first, second) {
                if (!first || !second) return false;
                return first === second || first.endsWith('/' + second) || second.endsWith('/' + first);
            }
            var currentResource = normalizeResource(location.pathname);
            var headingSelector = 'h1, h2, h3, h4, h5, h6, [role="heading"]';
            var blockSelector = headingSelector + ', section, article, header, div, p, li, dt, dd';
            tocHrefs.forEach(function(href) {
                var hashIndex = href.indexOf('#');
                if (hashIndex < 0 || !isSameResource(currentResource, normalizeResource(href))) return;
                var fragment = decodeComponent(href.slice(hashIndex + 1));
                if (!fragment) return;
                var target = document.getElementById(fragment) || document.getElementsByName(fragment)[0];
                if (!target || target === document.body || target === document.documentElement) return;
                var nextHeading = target.nextElementSibling && target.nextElementSibling.matches(headingSelector)
                    ? target.nextElementSibling
                    : null;
                var marker = nextHeading || target.closest(blockSelector) || target;
                if (marker === document.body || marker === document.documentElement) return;
                marker.setAttribute('$EPUB_CHAPTER_BREAK_TARGET_ATTRIBUTE', '');
            });
            return true;
        })()
    """.trimIndent()
}
