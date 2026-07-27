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
    return EpubFontPreparation(
        key = key,
        requiresAsyncLoad = true,
        faceKeys = payload.faces.mapTo(mutableSetOf()) { it.key },
        script = """
            (function() {
                const key = $keyJson;
                const family = $familyJson;
                const faces = $facesJson;
                const current = window.__kohariaFontState;
                if (current && current.key === key && (current.status === 'loading' || current.status === 'ready')) {
                    return current.status;
                }
                const topWindow = window.top || window;
                const shared = topWindow.__kohariaSharedFontBlobs ||
                    (topWindow.__kohariaSharedFontBlobs = Object.create(null));
                const activeBlobKeys = new Set(faces.filter(function(face) { return face.local; }).map(function(face) {
                    return face.key;
                }));
                Object.keys(shared).forEach(function(blobKey) {
                    if (activeBlobKeys.has(blobKey)) return;
                    try { URL.revokeObjectURL(shared[blobKey]); } catch (_) {}
                    delete shared[blobKey];
                });
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
                function localUrl(face) {
                    if (shared[face.key]) return shared[face.key];
                    const nativeBridge = bridge();
                    if (!nativeBridge) throw new Error('EPUB font bridge unavailable');
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
                    if (total === 0) throw new Error('EPUB font is empty');
                    const url = URL.createObjectURL(new Blob(chunks, { type: face.mimeType }));
                    shared[face.key] = url;
                    return url;
                }
                function quoteCss(value) {
                    return String(value || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"');
                }
                const loading = faces.map(function(face) {
                    const source = face.local
                        ? 'url("' + localUrl(face) + '")'
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
                        return loaded;
                    });
                });
                Promise.all(loading).then(function(loadedFaces) {
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
            const topWindow = window.top || window;
            const shared = topWindow.__kohariaSharedFontBlobs || {};
            Object.keys(shared).forEach(function(blobKey) {
                try { URL.revokeObjectURL(shared[blobKey]); } catch (_) {}
                delete shared[blobKey];
            });
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
