package koharia.epub

import org.json.JSONArray
import org.json.JSONObject

internal data class EpubContinuousScrollResource(
    val index: Int,
    val href: String,
    val url: String,
)

/**
 * Builds a virtualized vertical reading order inside Readium's current resource WebView.
 *
 * Readium's scroll mode only scrolls one XHTML resource at a time and keeps resource changes in a
 * horizontal ViewPager. The script keeps the current XHTML in place, inserts same-origin iframes
 * for adjacent resources and retains measured placeholders for the rest of the reading order. Only
 * the resources around the visible section stay live (in addition to Readium's original document),
 * so long books do not keep every XHTML document in memory. The native bridge receives the visible
 * resource and its local progression, allowing the existing Locator/progress pipeline to remain
 * authoritative.
 */
internal fun buildEpubContinuousScrollInstallScript(
    resources: List<EpubContinuousScrollResource>,
    currentIndex: Int,
    initialProgression: Double,
    paragraphIndentScript: String,
): String {
    val resourcesJson = JSONArray().apply {
        resources.forEach { resource ->
            put(
                JSONObject().apply {
                    put("index", resource.index)
                    put("href", resource.href)
                    put("url", resource.url)
                },
            )
        }
    }
    val indentScriptJson = JSONObject.quote(paragraphIndentScript)
    val clampedProgression = initialProgression.coerceIn(0.0, 1.0)

    return """
        (function() {
            const resources = $resourcesJson;
            const currentIndex = $currentIndex;
            const initialProgression = $clampedProgression;
            const requestedParagraphIndentScript = $indentScriptJson;
            const existing = window.__kohariaContinuousScroll;
            if (existing && existing.currentIndex === currentIndex) {
                existing.refresh(requestedParagraphIndentScript);
                return 'ready';
            }
            if (!document.body || !resources.length || currentIndex < 0 || currentIndex >= resources.length) {
                return 'unavailable';
            }

            const scrolling = document.scrollingElement || document.documentElement;
            const originalScrollTop = scrolling.scrollTop;
            const viewportHeight = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1);
            const measuredHeights = new Map();
            const liveFrames = new Map();
            let activeIndex = currentIndex;
            let locationFrame = 0;
            let lastLocationSentAt = 0;
            let paragraphIndentScript = requestedParagraphIndentScript;

            const style = document.createElement('style');
            style.id = 'koharia-continuous-scroll-style';
            style.textContent = `
                html { height: auto !important; min-height: 100% !important; overflow-y: auto !important; }
                body { height: auto !important; min-height: 100% !important; overflow: visible !important; }
                #koharia-continuous-before, #koharia-continuous-after {
                    display: block !important;
                    width: 100% !important;
                    margin: 0 !important;
                    padding: 0 !important;
                }
                .koharia-continuous-resource {
                    display: block !important;
                    position: relative !important;
                    width: 100% !important;
                    min-width: 0 !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    border: 0 !important;
                    overflow: hidden !important;
                    background: transparent !important;
                }
                .koharia-continuous-resource > iframe {
                    display: block !important;
                    width: 100% !important;
                    min-width: 0 !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    border: 0 !important;
                    overflow: hidden !important;
                    background: transparent !important;
                }
                .koharia-continuous-marker {
                    display: block !important;
                    width: 100% !important;
                    height: 0 !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    border: 0 !important;
                }
            `;
            (document.head || document.documentElement).appendChild(style);

            const before = document.createElement('div');
            before.id = 'koharia-continuous-before';
            const currentStart = document.createElement('div');
            currentStart.id = 'koharia-continuous-current-start';
            currentStart.className = 'koharia-continuous-marker';
            const currentEnd = document.createElement('div');
            currentEnd.id = 'koharia-continuous-current-end';
            currentEnd.className = 'koharia-continuous-marker';
            const after = document.createElement('div');
            after.id = 'koharia-continuous-after';

            document.body.insertBefore(before, document.body.firstChild);
            document.body.insertBefore(currentStart, before.nextSibling);
            document.body.appendChild(currentEnd);
            document.body.appendChild(after);

            function createPlaceholder(resource) {
                const section = document.createElement('section');
                section.className = 'koharia-continuous-resource';
                section.dataset.resourceIndex = String(resource.index);
                section.style.height = viewportHeight + 'px';
                measuredHeights.set(resource.index, viewportHeight);
                return section;
            }

            const sections = new Map();
            for (let index = 0; index < resources.length; index += 1) {
                if (index === currentIndex) continue;
                const section = createPlaceholder(resources[index]);
                sections.set(index, section);
                if (index < currentIndex) before.appendChild(section);
                else after.appendChild(section);
            }

            // Prepending earlier resources must not move the initially restored reading position.
            scrolling.scrollTop = originalScrollTop + before.getBoundingClientRect().height;

            function sectionBounds(index) {
                if (index === currentIndex) {
                    const top = currentStart.getBoundingClientRect().top + scrolling.scrollTop;
                    const bottom = currentEnd.getBoundingClientRect().top + scrolling.scrollTop;
                    return { top: top, bottom: Math.max(top + 1, bottom), height: Math.max(1, bottom - top) };
                }
                const section = sections.get(index);
                if (!section) return null;
                const rect = section.getBoundingClientRect();
                const top = rect.top + scrolling.scrollTop;
                return { top: top, bottom: top + rect.height, height: Math.max(1, rect.height) };
            }

            function updateSectionHeight(index, nextHeight) {
                const section = sections.get(index);
                if (!section) return;
                const safeHeight = Math.max(viewportHeight, Math.ceil(nextHeight || viewportHeight));
                const oldHeight = measuredHeights.get(index) || viewportHeight;
                if (Math.abs(safeHeight - oldHeight) < 1) return;
                const wasAboveViewport = section.getBoundingClientRect().bottom <= 1;
                measuredHeights.set(index, safeHeight);
                section.style.height = safeHeight + 'px';
                const iframe = liveFrames.get(index);
                if (iframe) iframe.style.height = safeHeight + 'px';
                if (wasAboveViewport) scrolling.scrollTop += safeHeight - oldHeight;
            }

            function measureFrame(index, iframe) {
                try {
                    const doc = iframe.contentDocument;
                    if (!doc || !doc.documentElement) return;
                    doc.documentElement.style.setProperty('height', 'auto', 'important');
                    doc.documentElement.style.setProperty('overflow', 'hidden', 'important');
                    if (doc.body) {
                        doc.body.style.setProperty('height', 'auto', 'important');
                        doc.body.style.setProperty('overflow', 'hidden', 'important');
                    }
                    try { iframe.contentWindow.eval(paragraphIndentScript); } catch (_) {}
                    const height = Math.max(
                        doc.documentElement.scrollHeight || 0,
                        doc.body ? doc.body.scrollHeight || 0 : 0,
                        viewportHeight,
                    );
                    updateSectionHeight(index, height);
                } catch (_) {
                    // A publication with mixed origins cannot be stitched safely. Its placeholder
                    // remains available and native navigation continues to work as a fallback.
                }
            }

            function loadSection(index) {
                if (index === currentIndex || index < 0 || index >= resources.length || liveFrames.has(index)) return;
                const section = sections.get(index);
                if (!section) return;
                const iframe = document.createElement('iframe');
                iframe.setAttribute('scrolling', 'no');
                iframe.setAttribute('frameborder', '0');
                iframe.setAttribute('title', resources[index].href || '');
                iframe.style.height = (measuredHeights.get(index) || viewportHeight) + 'px';
                iframe.addEventListener('load', function() {
                    measureFrame(index, iframe);
                    try {
                        const doc = iframe.contentDocument;
                        const observer = new ResizeObserver(function() { measureFrame(index, iframe); });
                        observer.observe(doc.documentElement);
                        if (doc.body) observer.observe(doc.body);
                        iframe.__kohariaResizeObserver = observer;
                        Array.from(doc.images || []).forEach(function(image) {
                            if (!image.complete) image.addEventListener('load', function() { measureFrame(index, iframe); }, { once: true });
                        });
                    } catch (_) {}
                });
                liveFrames.set(index, iframe);
                section.replaceChildren(iframe);
                // A direct iframe URL is treated by Readium's WebViewClient as an internal link
                // navigation. Fetching through the same resource server keeps Readium's injected
                // CSS/scripts while srcdoc prevents the horizontal resource pager from taking over.
                fetch(resources[index].url)
                    .then(function(response) {
                        if (!response.ok) throw new Error('HTTP ' + response.status);
                        return response.text();
                    })
                    .then(function(html) {
                        if (liveFrames.get(index) !== iframe) return;
                        const escapedUrl = resources[index].url
                            .replace(/&/g, '&amp;')
                            .replace(/"/g, '&quot;')
                            .replace(/</g, '&lt;');
                        const base = '<base href="' + escapedUrl + '">';
                        if (/<head[\s>]/i.test(html)) {
                            html = html.replace(/<head([^>]*)>/i, '<head${'$'}1>' + base);
                        } else {
                            html = base + html;
                        }
                        iframe.srcdoc = html;
                    })
                    .catch(function() {
                        if (liveFrames.get(index) === iframe) section.replaceChildren();
                        liveFrames.delete(index);
                    });
            }

            function unloadSection(index) {
                if (index === currentIndex) return;
                const iframe = liveFrames.get(index);
                const section = sections.get(index);
                if (!iframe || !section) return;
                try {
                    if (iframe.__kohariaResizeObserver) iframe.__kohariaResizeObserver.disconnect();
                    iframe.src = 'about:blank';
                } catch (_) {}
                section.replaceChildren();
                liveFrames.delete(index);
                section.style.height = (measuredHeights.get(index) || viewportHeight) + 'px';
            }

            function updateWindow(index) {
                for (let candidate = 0; candidate < resources.length; candidate += 1) {
                    if (candidate === currentIndex) continue;
                    if (Math.abs(candidate - index) <= 1) loadSection(candidate);
                    else unloadSection(candidate);
                }
            }

            function visibleResource() {
                const viewportCenter = scrolling.scrollTop + viewportHeight * 0.5;
                let bestIndex = activeIndex;
                let bestDistance = Number.POSITIVE_INFINITY;
                for (let index = 0; index < resources.length; index += 1) {
                    const bounds = sectionBounds(index);
                    if (!bounds) continue;
                    if (viewportCenter >= bounds.top && viewportCenter < bounds.bottom) return index;
                    const distance = viewportCenter < bounds.top
                        ? bounds.top - viewportCenter
                        : viewportCenter - bounds.bottom;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestIndex = index;
                    }
                }
                return bestIndex;
            }

            function notifyLocation(force) {
                locationFrame = 0;
                const now = performance.now();
                if (!force && now - lastLocationSentAt < 90) return;
                lastLocationSentAt = now;
                const nextIndex = visibleResource();
                if (nextIndex !== activeIndex) {
                    activeIndex = nextIndex;
                    updateWindow(activeIndex);
                }
                const bounds = sectionBounds(activeIndex);
                if (!bounds) return;
                const localOffset = scrolling.scrollTop - bounds.top;
                const scrollableHeight = Math.max(1, bounds.height - viewportHeight);
                const progression = Math.max(0, Math.min(1, localOffset / scrollableHeight));
                if (window.KohariaContinuousScroll && window.KohariaContinuousScroll.onLocationChanged) {
                    window.KohariaContinuousScroll.onLocationChanged(activeIndex, progression);
                }
            }

            function scheduleLocation() {
                if (!locationFrame) locationFrame = requestAnimationFrame(function() { notifyLocation(false); });
            }

            document.addEventListener('scroll', scheduleLocation, { passive: true, capture: true });
            window.addEventListener('resize', scheduleLocation, { passive: true });
            updateWindow(currentIndex);
            window.__kohariaContinuousScroll = {
                currentIndex: currentIndex,
                resources: resources,
                notifyLocation: notifyLocation,
                refresh: function(nextParagraphIndentScript) {
                    paragraphIndentScript = nextParagraphIndentScript;
                    try { window.eval(paragraphIndentScript); } catch (_) {}
                    const loadedIndexes = Array.from(liveFrames.keys());
                    loadedIndexes.forEach(unloadSection);
                    updateWindow(activeIndex);
                    scheduleLocation();
                },
            };

            // Preserve Readium's restored progression even when the resource had not finished its
            // final layout at the moment the window was inserted.
            requestAnimationFrame(function() {
                const currentBounds = sectionBounds(currentIndex);
                if (currentBounds) {
                    scrolling.scrollTop = currentBounds.top +
                        initialProgression * Math.max(0, currentBounds.height - viewportHeight);
                }
                notifyLocation(true);
            });
            return 'installed';
        })()
    """.trimIndent()
}
