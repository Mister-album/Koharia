package koharia.epub

import koharia.epub.font.EpubFontId
import koharia.epub.font.EpubFontManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun buildEpubFontPreparationScript(
    fontManager: EpubFontManager,
    selectedFontId: String,
    publisherStyles: Boolean,
    prioritizeVisibleContent: Boolean = true,
    capturedRequirementsJson: String? = null,
): EpubFontPreparation {
    val requestedId = EpubFontId.fromPreference(selectedFontId)
    val effectiveId = if (publisherStyles) EpubFontId.ORIGINAL else requestedId
    val family = fontManager.resolve(effectiveId)
    val payload = fontManager.webPayload(effectiveId)
    val key = payload?.key ?: family.id.value
    if (payload == null) {
        val retainedLocalFaceKeys = fontManager.localFaceKeys().sorted()
        val fontCatalogValue = fontManager.localCatalogFingerprint()
        return EpubFontPreparation(
            key = key,
            requiresAsyncLoad = false,
            faceKeys = emptySet(),
            script = clearEpubFontScript(key, retainedLocalFaceKeys, fontCatalogValue),
        )
    }
    val facesJson = JsonArray(
        payload.faces.map { face ->
            buildJsonObject {
                put("key", face.key)
                put("postScriptName", face.postScriptName?.let(::JsonPrimitive) ?: JsonNull)
                put("local", face.file != null)
                put("weight", face.weight)
                put("minWeight", face.minWeight)
                put("maxWeight", face.maxWeight)
                put("italic", face.italic)
                put("mimeType", face.mimeType)
            }
        },
    )
    val keyJson = JsonPrimitive(key)
    val familyJson = JsonPrimitive(payload.cssFamilyName)
    val capturedRequirements = capturedRequirementsJson
        ?.let { value -> runCatching { Json.parseToJsonElement(value) as? JsonArray }.getOrNull() }
        ?: JsonArray(emptyList())
    val maxLocalFontBytes = EpubFontManager.MAX_WEB_FONT_FAMILY_BYTES
    return EpubFontPreparation(
        key = key,
        requiresAsyncLoad = true,
        faceKeys = payload.faces.mapTo(mutableSetOf()) { it.key },
        script = """
            (function() {
                const key = $keyJson;
                const family = $familyJson;
                const faces = $facesJson;
                const prioritizeVisibleContent = $prioritizeVisibleContent;
                const nativeCapturedRequirements = $capturedRequirements;
                const maxLocalFontBytes = $maxLocalFontBytes;
                const current = window.__kohariaFontState;
                if (current && current.key === key &&
                    (current.status === 'loading' || current.status === 'ready' || current.status === 'failed')) {
                    return current.status;
                }
                const topWindow = window.top || window;
                if (current && Array.isArray(current.faces)) {
                    current.faces.forEach(function(face) {
                        try { document.fonts.delete(face); } catch (_) {}
                    });
                }
                window.__kohariaFontState = {
                    key: key,
                    status: 'loading',
                    backgroundStatus: 'pending',
                    faces: []
                };

                function bridge() {
                    return window.KohariaEpubFont || (window.parent && window.parent.KohariaEpubFont) ||
                        (topWindow && topWindow.KohariaEpubFont);
                }
                function decodeBase64(value) {
                    const binary = atob(value);
                    const bytes = new Uint8Array(binary.length);
                    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
                    return bytes;
                }
                const fontCacheName = 'koharia-epub-fonts-v2';
                const fontCachePrefix = 'koharia-epub-fonts-';
                const activeFontStorageKey = '__kohariaActiveEpubFont';
                function cacheUrl(face) {
                    return 'https://koharia-font-cache.invalid/v2/' + encodeURIComponent(face.key);
                }
                function setActiveFont() {
                    try { localStorage.setItem(activeFontStorageKey, key); } catch (_) {}
                }
                function nativeBlob(face) {
                    const nativeBridge = bridge();
                    if (!nativeBridge) throw new Error('EPUB font bridge unavailable');
                    const expectedLength = Number(nativeBridge.getLength(face.key));
                    if (!Number.isFinite(expectedLength) || expectedLength <= 0) return null;
                    if (loadedLocalBytes + expectedLength > maxLocalFontBytes) return null;
                    const chunks = [];
                    let total = 0;
                    for (let index = 0; index < 4096; index++) {
                        const encoded = nativeBridge.getChunk(face.key, index);
                        if (encoded === null || typeof encoded === 'undefined') {
                            throw new Error('EPUB font chunk unavailable');
                        }
                        if (encoded.length === 0) break;
                        const chunk = decodeBase64(encoded);
                        chunks.push(chunk);
                        total += chunk.length;
                    }
                    if (total !== expectedLength) throw new Error('EPUB font length changed');
                    return new Blob(chunks, { type: face.mimeType });
                }
                function loadCachedBlob(face) {
                    if (!window.caches) return Promise.resolve(nativeBlob(face));
                    const request = new Request(cacheUrl(face));
                    let nativeAttempted = false;
                    const loadAndCache = function() {
                        return caches.open(fontCacheName).then(function(cache) {
                            return cache.match(request).then(function(response) {
                                if (response) return response.blob();
                                nativeAttempted = true;
                                const blob = nativeBlob(face);
                                if (!blob) return null;
                                return cache.put(
                                    request,
                                    new Response(blob, { headers: { 'Content-Type': face.mimeType } })
                                ).catch(function() {}).then(function() { return blob; });
                            });
                        });
                    };
                    const cached = navigator.locks && typeof navigator.locks.request === 'function'
                        ? navigator.locks.request('koharia-epub-font-' + face.key, loadAndCache)
                        : loadAndCache();
                    return cached.catch(function(error) {
                        if (nativeAttempted) throw error;
                        return nativeBlob(face);
                    });
                }
                function pruneFontCache() {
                    if (!window.caches) return;
                    let activeKey = null;
                    try { activeKey = localStorage.getItem(activeFontStorageKey); } catch (_) {}
                    if (activeKey !== key) return;
                    const activeUrls = new Set(
                        faces.filter(function(face) { return face.local; }).map(cacheUrl)
                    );
                    caches.keys().then(function(names) {
                        names.filter(function(name) {
                            return name.indexOf(fontCachePrefix) === 0 && name !== fontCacheName;
                        }).forEach(function(name) { caches.delete(name); });
                    });
                    caches.open(fontCacheName).then(function(cache) {
                        return cache.keys().then(function(requests) {
                            let latestActiveKey = null;
                            try { latestActiveKey = localStorage.getItem(activeFontStorageKey); } catch (_) {}
                            if (latestActiveKey !== key) return;
                            requests.forEach(function(request) {
                                if (!activeUrls.has(request.url)) cache.delete(request);
                            });
                        });
                    });
                }

                const detectVisibleRequirements = $EPUB_VISIBLE_FONT_REQUIREMENTS_FUNCTION;
                function currentRequirements() {
                    const detected = detectVisibleRequirements();
                    let captured = null;
                    try { captured = topWindow.__kohariaVisibleFontRequirements; } catch (_) {}
                    let currentDocumentUrl = '';
                    try { currentDocumentUrl = String(topWindow.location.href || ''); } catch (_) {}
                    const isFresh = captured && Array.isArray(captured.requirements) &&
                        String(captured.documentUrl || '') === currentDocumentUrl &&
                        Date.now() - Number(captured.capturedAt || 0) < 30000;
                    const merged = new Map();
                    function add(requirement) {
                        if (!requirement) return;
                        const weight = Math.max(1, Math.min(1000, Number(requirement.weight) || 400));
                        const italic = !!requirement.italic;
                        const mapKey = (italic ? 'i:' : 'n:') + String(weight);
                        const score = Math.max(1, Number(requirement.score) || 1);
                        const previous = merged.get(mapKey);
                        if (previous) previous.score += score;
                        else merged.set(mapKey, { weight: weight, italic: italic, score: score });
                    }
                    detected.forEach(add);
                    nativeCapturedRequirements.forEach(add);
                    if (isFresh) captured.requirements.forEach(add);
                    return Array.from(merged.values()).sort(function(first, second) {
                        return second.score - first.score;
                    });
                }
                function faceDistance(face, requirement) {
                    const stylePenalty = face.italic === requirement.italic ? 0 : 2000;
                    const minWeight = Number(face.minWeight || face.weight || 400);
                    const maxWeight = Number(face.maxWeight || face.weight || 400);
                    const weightDistance = requirement.weight < minWeight
                        ? minWeight - requirement.weight
                        : requirement.weight > maxWeight ? requirement.weight - maxWeight : 0;
                    return stylePenalty + weightDistance;
                }
                function priorityFaces() {
                    if (!prioritizeVisibleContent) return faces.slice();
                    const selected = [];
                    currentRequirements().forEach(function(requirement) {
                        let best = null;
                        faces.forEach(function(face) {
                            const distance = faceDistance(face, requirement);
                            if (!best || distance < best.distance) best = { face: face, distance: distance };
                        });
                        if (best && selected.indexOf(best.face) < 0) selected.push(best.face);
                    });
                    if (selected.length === 0 && faces.length > 0) selected.push(faces[0]);
                    return selected;
                }

                let loadedLocalBytes = 0;
                function localSource(face) {
                    return loadCachedBlob(face).then(function(blob) {
                        if (!blob || blob.size <= 0) return null;
                        if (loadedLocalBytes + blob.size > maxLocalFontBytes) return null;
                        loadedLocalBytes += blob.size;
                        return { url: URL.createObjectURL(blob), length: blob.size };
                    });
                }
                function quoteCss(value) {
                    return String(value || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"');
                }
                function loadFace(face) {
                    const localPromise = face.local ? localSource(face) : Promise.resolve(null);
                    return localPromise.then(function(local) {
                        if (face.local && !local) return null;
                        const source = local
                            ? 'url("' + local.url + '")'
                            : 'local("' + quoteCss(face.postScriptName || family) + '")';
                        const descriptors = {
                            style: face.italic ? 'italic' : 'normal',
                            weight: face.minWeight !== face.maxWeight
                                ? String(face.minWeight) + ' ' + String(face.maxWeight)
                                : String(face.weight),
                            display: 'block'
                        };
                        const fontFace = new FontFace(family, source, descriptors);
                        return fontFace.load().then(function(loaded) {
                            const active = window.__kohariaFontState;
                            if (!active || active.key !== key) return null;
                            document.fonts.add(loaded);
                            return loaded;
                        }).catch(function() {
                            if (local) loadedLocalBytes -= local.length;
                            return null;
                        }).then(function(result) {
                            if (local) {
                                try { URL.revokeObjectURL(local.url); } catch (_) {}
                            }
                            return result;
                        });
                    });
                }
                function loadSequentially(faceList, loadedFaces) {
                    let loading = Promise.resolve(loadedFaces);
                    faceList.forEach(function(face) {
                        loading = loading.then(function(result) {
                            return loadFace(face).then(function(loaded) {
                                if (loaded) result.push(loaded);
                                return result;
                            });
                        });
                    });
                    return loading;
                }
                function scheduleDeferred(faceList, loadedFaces) {
                    const run = function() {
                        loadSequentially(faceList, loadedFaces).then(function(result) {
                            const active = window.__kohariaFontState;
                            if (!active || active.key !== key) return;
                            active.faces = result;
                            active.backgroundStatus = 'complete';
                            document.documentElement.setAttribute('data-koharia-font-complete', key);
                            pruneFontCache();
                        }).catch(function() {
                            const active = window.__kohariaFontState;
                            if (!active || active.key !== key) return;
                            active.backgroundStatus = 'complete';
                            document.documentElement.setAttribute('data-koharia-font-complete', key);
                            pruneFontCache();
                        });
                    };
                    if (faceList.length === 0) {
                        run();
                    } else {
                        setTimeout(function() {
                            if (typeof requestIdleCallback === 'function') {
                                requestIdleCallback(run, { timeout: 1200 });
                            } else {
                                run();
                            }
                        }, 350);
                    }
                }

                setActiveFont();
                const blockingFaces = priorityFaces();
                const deferredFaces = prioritizeVisibleContent
                    ? faces.filter(function(face) { return blockingFaces.indexOf(face) < 0; })
                    : [];
                loadSequentially(blockingFaces, []).then(function(loadedFaces) {
                    if (loadedFaces.length === 0) throw new Error('No EPUB font face loaded');
                    if (!window.__kohariaFontState || window.__kohariaFontState.key !== key) return;
                    window.__kohariaFontState = {
                        key: key,
                        status: 'ready',
                        backgroundStatus: deferredFaces.length === 0 ? 'complete' : 'loading',
                        faces: loadedFaces
                    };
                    document.documentElement.setAttribute('data-koharia-font-ready', key);
                    scheduleDeferred(deferredFaces, loadedFaces);
                }).catch(function() {
                    if (!window.__kohariaFontState || window.__kohariaFontState.key !== key) return;
                    window.__kohariaFontState = {
                        key: key,
                        status: 'failed',
                        backgroundStatus: 'complete',
                        faces: []
                    };
                    document.documentElement.setAttribute('data-koharia-font-failed', key);
                });
                return 'loading';
            })()
        """.trimIndent(),
    )
}

private fun clearEpubFontScript(
    key: String,
    retainedLocalFaceKeys: List<String>,
    fontCatalogValue: String,
): String {
    val keyJson = JsonPrimitive(key)
    val retainedLocalFaceKeysJson = JsonArray(retainedLocalFaceKeys.map(::JsonPrimitive))
    val fontCatalogValueJson = JsonPrimitive(fontCatalogValue)
    return """
        (function() {
            const fontCacheName = 'koharia-epub-fonts-v2';
            const fontCachePrefix = 'koharia-epub-fonts-';
            const activeFontStorageKey = '__kohariaActiveEpubFont';
            const fontCatalogStorageKey = '__kohariaEpubFontCatalogV2';
            const retainedLocalFaceKeys = $retainedLocalFaceKeysJson;
            const fontCatalogValue = $fontCatalogValueJson;
            const current = window.__kohariaFontState;
            if (current && Array.isArray(current.faces)) {
                current.faces.forEach(function(face) {
                    try { document.fonts.delete(face); } catch (_) {}
                });
            }
            window.__kohariaFontState = {
                key: $keyJson,
                status: 'ready',
                backgroundStatus: 'complete',
                faces: []
            };
            document.documentElement.setAttribute('data-koharia-font-ready', $keyJson);
            try { localStorage.removeItem(activeFontStorageKey); } catch (_) {}
            if (window.caches) {
                let cachedCatalogValue = null;
                try { cachedCatalogValue = localStorage.getItem(fontCatalogStorageKey); } catch (_) {}
                if (cachedCatalogValue !== fontCatalogValue) {
                    const retainedUrls = new Set(retainedLocalFaceKeys.map(function(faceKey) {
                        return 'https://koharia-font-cache.invalid/v2/' + encodeURIComponent(faceKey);
                    }));
                    caches.keys().then(function(names) {
                        const work = names.filter(function(name) {
                            return name.indexOf(fontCachePrefix) === 0 && name !== fontCacheName;
                        }).map(function(name) { return caches.delete(name); });
                        if (names.indexOf(fontCacheName) >= 0) {
                            work.push(caches.open(fontCacheName).then(function(cache) {
                                return cache.keys().then(function(requests) {
                                    return Promise.all(requests.filter(function(request) {
                                        return !retainedUrls.has(request.url);
                                    }).map(function(request) { return cache.delete(request); }));
                                });
                            }));
                        }
                        return Promise.all(work);
                    }).then(function() {
                        try { localStorage.setItem(fontCatalogStorageKey, fontCatalogValue); } catch (_) {}
                    }).catch(function() {});
                }
            }
            return 'ready';
        })()
    """.trimIndent()
}

internal data class EpubFontPreparation(
    val key: String,
    val requiresAsyncLoad: Boolean,
    val faceKeys: Set<String>,
    val script: String,
)

internal val EPUB_CAPTURE_VISIBLE_FONT_REQUIREMENTS_SCRIPT: String
    get() =
        """
        (function() {
            const detect = $EPUB_VISIBLE_FONT_REQUIREMENTS_FUNCTION;
            const requirements = detect();
            const topWindow = window.top || window;
            try {
            topWindow.__kohariaVisibleFontRequirements = {
                capturedAt: Date.now(),
                documentUrl: String(topWindow.location.href || ''),
                requirements: requirements
            };
            } catch (_) {}
            return requirements;
        })()
        """.trimIndent()

private val EPUB_VISIBLE_FONT_REQUIREMENTS_FUNCTION =
    """
    function() {
        const requirements = new Map();
        const visitedDocuments = new Set();
        let scannedTextNodes = 0;
        function intersects(rect, width, height) {
            return rect && rect.width > 0 && rect.height > 0 &&
                rect.right > 0 && rect.bottom > 0 && rect.left < width && rect.top < height;
        }
        function numericWeight(value) {
            const normalized = String(value || '').toLowerCase();
            if (normalized === 'bold' || normalized === 'bolder') return 700;
            if (normalized === 'lighter') return 300;
            const parsed = Number.parseInt(normalized, 10);
            return Number.isFinite(parsed) ? Math.max(1, Math.min(1000, parsed)) : 400;
        }
        function record(style, score) {
            const weight = numericWeight(style.fontWeight);
            const fontStyle = String(style.fontStyle || '').toLowerCase();
            const italic = fontStyle === 'italic' || fontStyle.indexOf('oblique') === 0;
            const key = (italic ? 'i:' : 'n:') + String(weight);
            const previous = requirements.get(key);
            if (previous) previous.score += score;
            else requirements.set(key, { weight: weight, italic: italic, score: score });
        }
        function scanDocument(doc) {
            if (!doc || visitedDocuments.has(doc) || scannedTextNodes >= 2048) return;
            visitedDocuments.add(doc);
            const view = doc.defaultView;
            if (!view) return;
            const width = Math.max(1, view.innerWidth || doc.documentElement.clientWidth || 1);
            const height = Math.max(1, view.innerHeight || doc.documentElement.clientHeight || 1);
            const sampledElements = new Set();
            const stepX = Math.max(32, Math.floor(width / 8));
            const stepY = Math.max(32, Math.floor(height / 16));
            for (let y = Math.min(16, height - 1); y < height; y += stepY) {
                for (let x = Math.min(16, width - 1); x < width; x += stepX) {
                    Array.from(doc.elementsFromPoint(x, y)).slice(0, 4).forEach(function(element) {
                        if (sampledElements.has(element)) return;
                        sampledElements.add(element);
                        const text = String(element.textContent || '').trim();
                        if (!text || /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)${'$'}/.test(element.tagName)) return;
                        const style = view.getComputedStyle(element);
                        if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) {
                            return;
                        }
                        record(style, Math.min(128, text.length));
                    });
                }
            }
            const walker = doc.createTreeWalker(doc.body || doc.documentElement, 4);
            let node;
            while ((node = walker.nextNode()) && scannedTextNodes < 2048) {
                scannedTextNodes += 1;
                const text = String(node.nodeValue || '').trim();
                if (!text) continue;
                const parent = node.parentElement;
                if (!parent || /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)${'$'}/.test(parent.tagName)) continue;
                const style = view.getComputedStyle(parent);
                if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) continue;
                const range = doc.createRange();
                range.selectNodeContents(node);
                const rects = Array.from(range.getClientRects());
                range.detach();
                if (!rects.some(function(rect) { return intersects(rect, width, height); })) continue;
                record(style, Math.min(512, text.length));
            }
            Array.from(doc.querySelectorAll('iframe')).forEach(function(frame) {
                if (!intersects(frame.getBoundingClientRect(), width, height)) return;
                try { scanDocument(frame.contentDocument); } catch (_) {}
            });
        }
        scanDocument(document);
        return Array.from(requirements.values()).sort(function(first, second) {
            return second.score - first.score;
        }).slice(0, 8);
    }
    """.trimIndent()
