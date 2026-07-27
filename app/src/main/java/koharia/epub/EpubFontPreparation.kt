package koharia.epub

import koharia.epub.font.EpubFontId
import koharia.epub.font.EpubFontManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun buildEpubFontPreparationScript(
    fontManager: EpubFontManager,
    selectedFontId: String,
    publisherStyles: Boolean,
): EpubFontPreparation {
    val requestedId = EpubFontId.fromPreference(selectedFontId)
    val effectiveId = if (publisherStyles) EpubFontId.ORIGINAL else requestedId
    val family = fontManager.resolve(effectiveId)
    val payload = fontManager.webPayload(effectiveId)
    val key = payload?.key ?: family.id.value
    if (payload == null) {
        return EpubFontPreparation(
            key = key,
            requiresAsyncLoad = false,
            faceKeys = emptySet(),
            script = clearEpubFontScript(key),
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
                window.__kohariaFontState = { key: key, status: 'loading', faces: [] };

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
                let loadedLocalBytes = 0;
                function localSource(face) {
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
                    const url = URL.createObjectURL(new Blob(chunks, { type: face.mimeType }));
                    loadedLocalBytes += total;
                    return { url: url, length: total };
                }
                function quoteCss(value) {
                    return String(value || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"');
                }
                function loadFace(face, loadedFaces) {
                    const local = face.local ? localSource(face) : null;
                    if (face.local && !local) return Promise.resolve(loadedFaces);
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
                        document.fonts.add(loaded);
                        loadedFaces.push(loaded);
                        return loadedFaces;
                    }).catch(function() {
                        if (local) loadedLocalBytes -= local.length;
                        return loadedFaces;
                    }).then(function(result) {
                        if (local) {
                            try { URL.revokeObjectURL(local.url); } catch (_) {}
                        }
                        return result;
                    });
                }
                let loading = Promise.resolve([]);
                faces.forEach(function(face) {
                    loading = loading.then(function(loadedFaces) {
                        return loadFace(face, loadedFaces);
                    });
                });
                loading.then(function(loadedFaces) {
                    if (loadedFaces.length === 0) throw new Error('No EPUB font face loaded');
                    if (!window.__kohariaFontState || window.__kohariaFontState.key !== key) return;
                    window.__kohariaFontState = { key: key, status: 'ready', faces: loadedFaces };
                    document.documentElement.setAttribute('data-koharia-font-ready', key);
                }).catch(function() {
                    if (!window.__kohariaFontState || window.__kohariaFontState.key !== key) return;
                    window.__kohariaFontState = { key: key, status: 'failed', faces: [] };
                    document.documentElement.setAttribute('data-koharia-font-failed', key);
                });
                return 'loading';
            })()
        """.trimIndent(),
    )
}

private fun clearEpubFontScript(key: String): String {
    val keyJson = JsonPrimitive(key)
    return """
        (function() {
            const current = window.__kohariaFontState;
            if (current && Array.isArray(current.faces)) {
                current.faces.forEach(function(face) {
                    try { document.fonts.delete(face); } catch (_) {}
                });
            }
            window.__kohariaFontState = { key: $keyJson, status: 'ready', faces: [] };
            document.documentElement.setAttribute('data-koharia-font-ready', $keyJson);
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
