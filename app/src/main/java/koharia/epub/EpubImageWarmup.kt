package koharia.epub

internal val EPUB_WARM_NEARBY_IMAGES_SCRIPT = """
    (function() {
        const state = window.__kohariaImageWarmup || (window.__kohariaImageWarmup = { seen: new WeakSet(), pending: false });
        if (state.pending) return;
        state.pending = true;
        const run = function() {
            state.pending = false;
            const images = Array.from(document.images);
            const width = document.documentElement.clientWidth;
            const height = document.documentElement.clientHeight;
            const candidates = images.filter(function(img, index) {
                if (state.seen.has(img) || typeof img.decode !== 'function') return false;
                if (index === 0 || index === images.length - 1) return true;
                const r = img.getBoundingClientRect();
                return r.right > -width && r.left < width * 2 && r.bottom > -height && r.top < height * 2;
            }).slice(0, 3);
            candidates.forEach(function(img) {
                state.seen.add(img);
                img.decode().catch(function() { state.seen.delete(img); });
            });
        };
        if (window.requestIdleCallback) window.requestIdleCallback(run, { timeout: 300 });
        else window.setTimeout(run, 50);
    })();
""".trimIndent()
