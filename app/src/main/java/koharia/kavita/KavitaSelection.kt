package koharia.kavita

import koharia.connection.ConnectionTextSelection
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

// Remember a selection briefly while Android moves focus from the WebView to the reader toolbar.
internal val KAVITA_SELECTION_SCRIPT = """
    (function() {
        if (window.__kohariaSelectionInstalled) return;
        window.__kohariaSelectionInstalled = true;
        function element(node) {
            var el = node && (node.nodeType === 1 ? node : node.parentElement);
            while (el && (!el.hasAttribute('data-koharia-kavita-path') ||
                el.tagName.toLowerCase() === 'app-epub-highlight')) el = el.parentElement;
            return el;
        }
        document.addEventListener('selectionchange', function() {
            var selected = window.getSelection();
            if (!selected || !selected.rangeCount || selected.isCollapsed) return;
            var range = selected.getRangeAt(0), start = element(range.startContainer), end = element(range.endContainer);
            var text = selected.toString();
            if (!start || !end || !text.trim() || text.length > 50000) return;
            window.__kohariaAnnotationSelection = {
                start: start.getAttribute('data-koharia-kavita-path'),
                end: end.getAttribute('data-koharia-kavita-path'), text: text, time: Date.now()
            };
        });
    })()
""".trimIndent()

internal suspend fun captureKavitaSelection(navigator: EpubNavigatorFragment): ConnectionTextSelection? {
    val locator = navigator.currentLocator.value
    if (!locator.href.toString().contains("koharia-epub/")) return null
    val raw = navigator.evaluateJavascript(
        """
        (function() {
            var selection = window.__kohariaAnnotationSelection;
            return selection && Date.now() - selection.time < 30000 ? selection : null;
        })()
        """.trimIndent(),
    ) ?: return null
    if (navigator.currentLocator.value.href != locator.href) return null
    val value = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val start = value.optString("start")
    val end = value.optString("end")
    val text = value.optString("text")
    if (!start.startsWith("//body") || !end.startsWith("//body") || text.isBlank()) return null
    return ConnectionTextSelection(locator, start, end, text)
}

internal suspend fun renderKavitaHighlights(
    navigator: EpubNavigatorFragment,
    values: List<koharia.connection.ConnectionTextHighlight>,
) {
    val href = navigator.currentLocator.value.href.toString().substringBefore('#')
    if (!href.contains("koharia-epub/")) return
    val highlights = org.json.JSONArray(
        values.filter {
            it.selection.locator.href.toString().substringBefore('#') == href
        }.map {
            JSONObject().put("start", it.selection.startAnchor).put("end", it.selection.endAnchor)
                .put("text", it.selection.text).put("slot", it.slot.coerceIn(0, 3))
                .put("color", it.color)
        },
    )
    navigator.evaluateJavascript(
        """
        (function(values) {
            if (!window.CSS || !CSS.highlights || !window.Highlight) return false;
            function find(path) {
                for (const el of document.querySelectorAll('[data-koharia-kavita-path]')) {
                    if (el.getAttribute('data-koharia-kavita-path').toLowerCase() === path.toLowerCase()) return el;
                }
                if (path.indexOf('id(') === 0) {
                    try { return document.evaluate(path, document, null, 9, null).singleNodeValue; } catch(e) {}
                }
                return document.getElementById(path);
            }
            const groups = [[], [], [], []], colors = ['#ffe082', '#a5d6a7', '#90caf9', '#f48fb1'];
            for (const value of values) {
                if (typeof value.color === 'string' &&
                    /^rgba\(\d+,\d+,\d+,[0-9.]+\)$/.test(value.color)) colors[value.slot] = value.color;
                const start = find(value.start), end = find(value.end);
                if (!start || !end) continue;
                try {
                    const area = document.createRange();
                    area.setStart(start, 0); area.setEnd(end, end.childNodes.length);
                    const walker = document.createTreeWalker(area.commonAncestorContainer, NodeFilter.SHOW_TEXT);
                    let node, text = '', positions = [];
                    while ((node = walker.nextNode())) {
                        if (!area.intersectsNode(node)) continue;
                        if (node.parentElement.closest('style,script')) continue;
                        for (let index = 0; index < node.length; index++) {
                            let char = node.data[index];
                            if (/\s/.test(char)) char = ' ';
                            if (char === ' ' && text.endsWith(' ')) continue;
                            text += char; positions.push([node, index]);
                        }
                    }
                    const selected = value.text.replace(/\s+/g, ' ').trim(), index = text.indexOf(selected);
                    if (!selected || index < 0) continue;
                    const first = positions[index], last = positions[index + selected.length - 1];
                    const range = document.createRange();
                    range.setStart(first[0], first[1]); range.setEnd(last[0], last[1] + 1);
                    groups[value.slot].push(range);
                } catch(e) {}
            }
            let style = document.getElementById('koharia-annotation-colors');
            if (!style) {
                style = document.createElement('style'); style.id = 'koharia-annotation-colors';
                document.head.appendChild(style);
            }
            style.textContent = colors.map((c, i) =>
                '::highlight(koharia-annotation-' + i + '){background-color:' + c + ';color:#111}').join('\n');
            groups.forEach((ranges, i) => CSS.highlights.set('koharia-annotation-' + i, new Highlight(...ranges)));
            return true;
        })($highlights)
        """.trimIndent(),
    )
}
