package koharia.epub

import org.json.JSONArray

internal fun currentEpubTocIndexScript(anchors: List<String>): String = """
    (function() {
        const anchors = ${JSONArray(anchors)};
        const rtl = getComputedStyle(document.documentElement).direction === 'rtl';
        let current = -1;
        anchors.forEach(function(fragment, index) {
            if (!fragment) { current = index; return; }
            try { fragment = decodeURIComponent(fragment); } catch (_) {}
            const element = document.getElementById(fragment) || document.getElementsByName(fragment)[0];
            if (!element || !element.getClientRects().length) return;
            const rect = element.getBoundingClientRect();
            const beforePageEnd = rtl ? rect.right > 0 : rect.left < document.documentElement.clientWidth;
            if (beforePageEnd && rect.top < document.documentElement.clientHeight) current = index;
        });
        return current;
    })();
""".trimIndent()
