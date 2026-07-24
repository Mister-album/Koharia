package koharia.epub

internal fun buildEpubImageInteractionInstallScript(
    longPressTimeoutMs: Int,
    touchSlopCssPx: Float,
): String =
    """
    (function() {
        const resourceIndex = Number.isInteger(window.__kohariaImageResourceIndex)
            ? window.__kohariaImageResourceIndex
            : -1;
        const existing = window.__kohariaImageInteractions;
        if (existing) {
            existing.resourceIndex = resourceIndex;
            return 'ready';
        }

        const state = {
            resourceIndex: resourceIndex,
            active: null,
            suppressClickImage: null,
        };
        window.__kohariaImageInteractions = state;

        function imageElementType(element) {
            if (!element || !element.tagName) return null;
            const tagName = (element.localName || element.tagName).toLowerCase();
            if (tagName === 'img') return 'html';
            if (tagName === 'image' && element.namespaceURI === '$SVG_NAMESPACE') return 'svg';
            return null;
        }

        function eventImage(event) {
            const target = event && event.target;
            return imageElementType(target) ? target : null;
        }

        function rawImageSource(image, type) {
            if (type === 'html') return image.getAttribute('src') || '';
            return image.getAttribute('href') ||
                image.getAttributeNS('$XLINK_NAMESPACE', 'href') ||
                image.getAttribute('xlink:href') ||
                '';
        }

        function resolvedImageSource(image, type, rawSource) {
            if (type === 'html') return image.currentSrc || image.src || rawSource;
            const href = image.href;
            const hrefValue = typeof href === 'string'
                ? href
                : href && (href.baseVal || href.animVal);
            const source = hrefValue || rawSource;
            if (!source) return '';
            try {
                return new URL(source, document.baseURI).href;
            } catch (_) {
                return source;
            }
        }

        function cancelLongPress() {
            if (!state.active) return;
            if (state.active.timer) clearTimeout(state.active.timer);
            state.active = null;
        }

        function notify(action, image) {
            const bridge = window.$EPUB_IMAGE_BRIDGE_NAME;
            if (!bridge || !bridge.onImageInteraction) return false;
            const type = imageElementType(image);
            if (!type) return false;
            const rawSource = rawImageSource(image, type);
            const currentSource = resolvedImageSource(image, type, rawSource);
            if (!currentSource && !rawSource) return false;
            bridge.onImageInteraction(
                action,
                state.resourceIndex,
                currentSource,
                rawSource,
                image.getAttribute('alt') || '',
                image.getAttribute('title') || '',
            );
            return true;
        }

        document.addEventListener('pointerdown', function(event) {
            const image = eventImage(event);
            if (!image || event.isPrimary === false || (event.button !== undefined && event.button !== 0)) return;
            cancelLongPress();
            const active = {
                image: image,
                pointerId: event.pointerId,
                startX: event.clientX,
                startY: event.clientY,
                timer: 0,
            };
            active.timer = window.setTimeout(function() {
                if (state.active !== active) return;
                state.active = null;
                if (notify('actions', image)) state.suppressClickImage = image;
            }, $longPressTimeoutMs);
            state.active = active;
        }, true);

        document.addEventListener('pointermove', function(event) {
            const active = state.active;
            if (!active || active.pointerId !== event.pointerId) return;
            const deltaX = event.clientX - active.startX;
            const deltaY = event.clientY - active.startY;
            if (Math.hypot(deltaX, deltaY) > $touchSlopCssPx) cancelLongPress();
        }, { passive: true, capture: true });

        document.addEventListener('pointerup', cancelLongPress, true);
        document.addEventListener('pointercancel', cancelLongPress, true);
        document.addEventListener('scroll', cancelLongPress, { passive: true, capture: true });

        document.addEventListener('contextmenu', function(event) {
            const image = eventImage(event);
            if (!image) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            cancelLongPress();
            if (state.suppressClickImage !== image && notify('actions', image)) {
                state.suppressClickImage = image;
            }
        }, true);

        document.addEventListener('click', function(event) {
            const image = eventImage(event);
            if (!image) return;
            if (state.suppressClickImage === image) {
                state.suppressClickImage = null;
                event.preventDefault();
                event.stopImmediatePropagation();
                return;
            }
            if (image.closest && image.closest('a[href]')) return;
            if (notify('preview', image)) {
                event.preventDefault();
                event.stopImmediatePropagation();
            }
        }, true);

        return 'installed';
    })()
    """.trimIndent()

internal const val EPUB_IMAGE_BRIDGE_NAME = "KohariaEpubImage"
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
