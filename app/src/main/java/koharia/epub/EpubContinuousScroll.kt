package koharia.epub

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    imageInteractionScript: String,
    contentPreparationScript: String,
): String {
    val resourcesJson = buildJsonArray {
        resources.forEach { resource ->
            add(
                buildJsonObject {
                    put("index", resource.index)
                    put("href", resource.href)
                    put("url", resource.url)
                },
            )
        }
    }
    val contentPreparationScriptJson = JsonPrimitive(contentPreparationScript)
    val imageInteractionScriptJson = JsonPrimitive(imageInteractionScript)
    val clampedProgression = initialProgression.coerceIn(0.0, 1.0)

    return """
        (function() {
            const resources = $resourcesJson;
            const currentIndex = $currentIndex;
            const initialProgression = $clampedProgression;
            const requestedContentPreparationScript = $contentPreparationScriptJson;
            const requestedImageInteractionScript = $imageInteractionScriptJson;
            if (document.readyState === 'loading' || !document.body) return 'pending';
            if (!resources[currentIndex]) return 'unavailable';
            const expectedUrl = new URL(resources[currentIndex].url, document.baseURI);
            if (expectedUrl.origin !== location.origin || expectedUrl.pathname !== location.pathname) {
                return 'different-resource';
            }
            const existing = window.__kohariaContinuousScroll;
            if (existing && existing.currentIndex === currentIndex) {
                existing.refresh(requestedContentPreparationScript, requestedImageInteractionScript);
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
            const failedResources = new Set();
            let activeIndex = currentIndex;
            let locationFrame = 0;
            let locationTimer = 0;
            let restored = false;
            let lastLocationSentAt = 0;
            let contentPreparationScript = requestedContentPreparationScript;
            let imageInteractionScript = requestedImageInteractionScript;
            function readingStyles() {
                const computed = getComputedStyle(document.documentElement);
                return Array.from(computed)
                    .filter(function(name) { return name.startsWith('--USER__') || name.startsWith('--RS__'); })
                    .sort()
                    .map(function(name) { return [name, computed.getPropertyValue(name)]; });
            }
            let readingStyleSignature = JSON.stringify(readingStyles());

            function installImageInteractions(targetWindow, resourceIndex) {
                try {
                    targetWindow.__kohariaImageResourceIndex = resourceIndex;
                    targetWindow.eval(imageInteractionScript);
                } catch (_) {}
            }
            installImageInteractions(window, currentIndex);

            const style = document.createElement('style');
            style.id = 'koharia-continuous-scroll-style';
            style.textContent = `
                html { height: auto !important; min-height: 100% !important; overflow-y: auto !important; }
                body { height: auto !important; min-height: 100% !important; overflow: visible !important; }
                /* Resource height compensation is owned by updateSectionHeight, not Chromium. */
                html, body { overflow-anchor: none !important; }
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
                scheduleLocation();
            }

            function measureFrame(index, iframe) {
                try {
                    const doc = iframe.contentDocument;
                    if (!doc || !doc.documentElement) return;
                    for (const entry of readingStyles()) {
                        doc.documentElement.style.setProperty(entry[0], entry[1]);
                    }
                    doc.documentElement.style.setProperty('height', 'auto', 'important');
                    doc.documentElement.style.setProperty('overflow', 'hidden', 'important');
                    if (doc.body) {
                        doc.body.style.setProperty('height', 'auto', 'important');
                        doc.body.style.setProperty('overflow', 'hidden', 'important');
                    }
                    try { iframe.contentWindow.eval(contentPreparationScript); } catch (_) {}
                    const fontState = iframe.contentWindow.__kohariaFontState;
                    if (fontState && fontState.status === 'loading') {
                        iframe.style.visibility = 'hidden';
                        clearTimeout(iframe.__kohariaFontMeasureTimer);
                        iframe.__kohariaFontMeasureTimer = setTimeout(function() {
                            measureFrame(index, iframe);
                        }, 40);
                        return;
                    }
                    installImageInteractions(iframe.contentWindow, index);
                    iframe.style.visibility = 'visible';
                    const height = Math.max(
                        doc.documentElement.scrollHeight || 0,
                        doc.body ? doc.body.scrollHeight || 0 : 0,
                        viewportHeight,
                    );
                    updateSectionHeight(index, height);
                    scheduleLocation();
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
                iframe.style.visibility = 'hidden';
                iframe.addEventListener('load', function() {
                    if (liveFrames.get(index) !== iframe || !iframe.hasAttribute('srcdoc')) return;
                    failedResources.delete(index);
                    measureFrame(index, iframe);
                    try {
                        const doc = iframe.contentDocument;
                        // Only the owning document may talk to Readium's native lifecycle bridge.
                        // Forward taps using the parent's coordinates, preserving image interception.
                        doc.addEventListener('click', function(event) {
                            if (event.defaultPrevented || !iframe.contentWindow.getSelection().isCollapsed) return;
                            const link = event.target.closest('a[href]');
                            if (!link && event.target.closest('button,input,textarea,select,[contenteditable]')) return;
                            const rect = iframe.getBoundingClientRect();
                            let target = document.body;
                            if (link) {
                                target = document.createElement('a');
                                for (const attribute of link.attributes) target.setAttribute(attribute.name, attribute.value);
                                target.href = new URL(link.getAttribute('href'), doc.baseURI).href;
                                target.style.display = 'none';
                                document.body.appendChild(target);
                            }
                            event.preventDefault();
                            target.dispatchEvent(new MouseEvent('click', {
                                bubbles: true, cancelable: true, view: window,
                                clientX: rect.left + event.clientX, clientY: rect.top + event.clientY,
                            }));
                            if (link) target.remove();
                        });
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
                // navigation. Keep the injected CSS, but never bootstrap another native navigator
                // inside this WebView: Android's JavaScript interfaces are shared by all frames.
                fetch(resources[index].url)
                    .then(function(response) {
                        if (!response.ok) throw new Error('HTTP ' + response.status);
                        return response.text();
                    })
                    .then(function(html) {
                        if (liveFrames.get(index) !== iframe) return;
                        const doc = new DOMParser().parseFromString(html, 'text/html');
                        const base = doc.createElement('base');
                        base.href = resources[index].url;
                        doc.head.prepend(base);
                        const navigatorScripts = new Set(Array.from(document.querySelectorAll('script[src]'))
                            .map(function(script) { return new URL(script.src, document.baseURI); })
                            .filter(function(url) { return url.pathname.startsWith('/readium/scripts/'); })
                            .map(function(url) { return url.href; }));
                        for (const script of doc.querySelectorAll('script[src]')) {
                            const url = new URL(script.getAttribute('src'), base.href);
                            if (navigatorScripts.has(url.href)) {
                                script.remove();
                            }
                        }
                        iframe.srcdoc = '<!DOCTYPE html>' + doc.documentElement.outerHTML;
                    })
                    .catch(function() {
                        if (liveFrames.get(index) === iframe) section.replaceChildren();
                        liveFrames.delete(index);
                        failedResources.add(index);
                        scheduleLocation();
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
                const viewportTop = scrolling.scrollTop + 1;
                let bestIndex = activeIndex;
                let bestDistance = Number.POSITIVE_INFINITY;
                for (let index = 0; index < resources.length; index += 1) {
                    const bounds = sectionBounds(index);
                    if (!bounds) continue;
                    if (viewportTop >= bounds.top && viewportTop < bounds.bottom) return index;
                    const distance = viewportTop < bounds.top
                        ? bounds.top - viewportTop
                        : viewportTop - bounds.bottom;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestIndex = index;
                    }
                }
                return bestIndex;
            }

            function notifyLocation(force) {
                locationFrame = 0;
                if (!restored) return;
                clearTimeout(locationTimer);
                locationTimer = 0;
                const now = performance.now();
                const remaining = 90 - (now - lastLocationSentAt);
                if (!force && remaining > 0) {
                    locationTimer = setTimeout(function() { notifyLocation(true); }, remaining);
                    return;
                }
                lastLocationSentAt = now;
                const nextIndex = visibleResource();
                if (nextIndex !== activeIndex) {
                    activeIndex = nextIndex;
                    updateWindow(activeIndex);
                }
                if (failedResources.delete(activeIndex)) {
                    if (window.KohariaContinuousScroll && window.KohariaContinuousScroll.onResourceLoadFailed) {
                        window.KohariaContinuousScroll.onResourceLoadFailed(activeIndex);
                    }
                    return;
                }
                const location = currentLocation();
                if (!location) return;
                if (window.KohariaContinuousScroll && window.KohariaContinuousScroll.onLocationChanged) {
                    window.KohariaContinuousScroll.onLocationChanged(location.resourceIndex, location.progression);
                }
            }

            function currentLocation() {
                if (!restored) return null;
                const index = visibleResource();
                if (index !== currentIndex) {
                    const frame = liveFrames.get(index);
                    if (!frame || frame.style.visibility === 'hidden') return null;
                }
                const bounds = sectionBounds(index);
                if (!bounds) return null;
                // Readium measures resource progression against its full height, including the viewport.
                return {
                    resourceIndex: index,
                    progression: Math.max(0, Math.min(1, (scrolling.scrollTop - bounds.top) / bounds.height)),
                };
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
                currentLocation: currentLocation,
                refresh: function(nextContentPreparationScript, nextImageInteractionScript) {
                    const nextReadingStyleSignature = JSON.stringify(readingStyles());
                    if (contentPreparationScript === nextContentPreparationScript &&
                        imageInteractionScript === nextImageInteractionScript &&
                        readingStyleSignature === nextReadingStyleSignature) return;
                    readingStyleSignature = nextReadingStyleSignature;
                    contentPreparationScript = nextContentPreparationScript;
                    imageInteractionScript = nextImageInteractionScript;
                    try { window.eval(contentPreparationScript); } catch (_) {}
                    installImageInteractions(window, currentIndex);
                    liveFrames.forEach(function(iframe, index) { measureFrame(index, iframe); });
                    scheduleLocation();
                },
            };

            // Preserve Readium's restored progression even when the resource had not finished its
            // final layout at the moment the window was inserted.
            requestAnimationFrame(function() {
                const currentBounds = sectionBounds(currentIndex);
                if (currentBounds) {
                    const offset = initialProgression === 1
                        ? Math.max(0, currentBounds.height - viewportHeight)
                        : initialProgression * currentBounds.height;
                    scrolling.scrollTop = currentBounds.top + offset;
                }
                restored = true;
                notifyLocation(true);
            });
            return 'installed';
        })()
    """.trimIndent()
}
