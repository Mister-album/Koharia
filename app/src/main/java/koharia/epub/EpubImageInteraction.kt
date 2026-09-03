package koharia.epub

internal fun buildEpubImageColorPolicyScript(
    preserveImageColors: Boolean,
    parentColorsInverted: Boolean,
): String =
    """
    (function() {
        const preserveImageColors = $preserveImageColors;
        const parentColorsInverted = $parentColorsInverted;
        const styleId = '$IMAGE_COLOR_POLICY_STYLE_ID';
        const root = document.documentElement;
        const styleNamespace = root && root.namespaceURI === '$SVG_NAMESPACE'
            ? '$SVG_NAMESPACE'
            : '$XHTML_NAMESPACE';
        let style = document.getElementById(styleId);
        if (!preserveImageColors) {
            if (style) style.remove();
            return 'removed';
        }
        if (style && style.namespaceURI !== styleNamespace) {
            style.remove();
            style = null;
        }
        if (!style) {
            style = document.createElementNS(styleNamespace, 'style');
            style.id = styleId;
            style.setAttribute('type', 'text/css');
            (document.head || document.documentElement).appendChild(style);
        }
        const rootIsSvg = document.documentElement &&
            document.documentElement.namespaceURI === '$SVG_NAMESPACE' &&
            document.documentElement.localName.toLowerCase() === 'svg';
        style.textContent = parentColorsInverted
            ? rootIsSvg
                ? `
                :root {
                    -webkit-filter: invert(100%) !important;
                    filter: invert(100%) !important;
                }
                `
                : `
                :root img,
                :root svg {
                    -webkit-filter: invert(100%) !important;
                    filter: invert(100%) !important;
                }
                :root svg svg {
                    -webkit-filter: none !important;
                    filter: none !important;
                }
                `
            : `
                :root[style*="readium-night-on"] [epub\\:type~="titlepage"] img:only-child,
                :root[style*="readium-night-on"] [type~="titlepage"] img:only-child,
                :root[style*="readium-night-on"] img[class*="gaiji"],
                :root[style*="readium-night-on"][style*="readium-darken-on"] img,
                :root[style*="readium-night-on"][style*="readium-invert-on"] img {
                    -webkit-filter: none !important;
                    filter: none !important;
                }
            `;
        return 'applied';
    })()
    """.trimIndent()

internal fun buildEpubStandaloneImageLayoutScript(paginated: Boolean): String =
    """
    (function() {
        const stateKey = '__kohariaStandaloneImageLayout';

        function restoreProperty(element, name, value, priority) {
            if (!element) return;
            if (value) {
                element.style.setProperty(name, value, priority || '');
            } else {
                element.style.removeProperty(name);
            }
        }

        function restoreState(state) {
            if (!state) return;
            if (state.loadListener && state.image) {
                state.image.removeEventListener('load', state.loadListener);
            }
            restoreProperty(state.image, 'max-width', state.maxWidth, state.maxWidthPriority);
            restoreProperty(state.image, 'max-height', state.maxHeight, state.maxHeightPriority);
            restoreProperty(state.container, 'overflow-x', state.overflowX, state.overflowXPriority);
            restoreProperty(state.container, 'visibility', state.visibility, state.visibilityPriority);
        }

        const previousState = window[stateKey];
        restoreState(previousState);
        delete window[stateKey];
        if (!$paginated) return previousState ? 'removed' : 'ignored';

        function standaloneImageContainer(image) {
            const body = document.body;
            if (!body || !image || body.textContent.trim() !== '') return null;
            const images = body.querySelectorAll('img, svg image');
            if (images.length !== 1 || images[0] !== image) return null;
            let container = image;
            while (container.parentElement && container.parentElement !== body) {
                container = container.parentElement;
            }
            if (container.parentElement !== body) return null;
            const contentChildren = Array.from(body.children).filter(function(child) {
                const name = child.localName && child.localName.toLowerCase();
                return name !== 'script' && name !== 'style';
            });
            return contentChildren.length === 1 && contentChildren[0] === container ? container : null;
        }

        function fitStandaloneImage(state) {
            const container = state.container;
            const image = state.image;
            const imageRect = image.getBoundingClientRect();
            const containerRect = container.getBoundingClientRect();
            if (imageRect.width <= 0 || imageRect.height <= 0 ||
                containerRect.width <= 0 || containerRect.height <= 0) return false;
            const scale = Math.min(
                containerRect.width / imageRect.width,
                containerRect.height / imageRect.height,
                1,
            );
            if (scale < 0.999) {
                const computed = window.getComputedStyle(image);
                const renderedWidth = Number.parseFloat(computed.width);
                const renderedHeight = Number.parseFloat(computed.height);
                if (!Number.isFinite(renderedWidth) || !Number.isFinite(renderedHeight)) return false;
                image.style.setProperty('max-width', `${'$'}{renderedWidth * scale}px`, 'important');
                image.style.setProperty('max-height', `${'$'}{renderedHeight * scale}px`, 'important');
            }
            container.style.setProperty('overflow-x', 'hidden', 'important');
            restoreProperty(container, 'visibility', state.visibility, state.visibilityPriority);
            state.loadListener = null;
            return true;
        }

        const standaloneImage = document.querySelector('img, svg image');
        const standaloneContainer = standaloneImageContainer(standaloneImage);
        if (!standaloneContainer) return 'ignored';
        const state = {
            image: standaloneImage,
            container: standaloneContainer,
            maxWidth: standaloneImage.style.getPropertyValue('max-width'),
            maxWidthPriority: standaloneImage.style.getPropertyPriority('max-width'),
            maxHeight: standaloneImage.style.getPropertyValue('max-height'),
            maxHeightPriority: standaloneImage.style.getPropertyPriority('max-height'),
            overflowX: standaloneContainer.style.getPropertyValue('overflow-x'),
            overflowXPriority: standaloneContainer.style.getPropertyPriority('overflow-x'),
            visibility: standaloneContainer.style.getPropertyValue('visibility'),
            visibilityPriority: standaloneContainer.style.getPropertyPriority('visibility'),
            loadListener: null,
        };
        window[stateKey] = state;
        // A transformed full-page image can create a small horizontal overflow. Readium
        // mistakes that overflow for another column and never advances to the next resource.
        if (fitStandaloneImage(state)) return 'applied';
        standaloneContainer.style.setProperty('visibility', 'hidden', 'important');
        state.loadListener = function() {
            if (window[stateKey] !== state) return;
            fitStandaloneImage(state);
        };
        standaloneImage.addEventListener('load', state.loadListener, { once: true });
        return 'pending';
    })()
    """.trimIndent()

internal fun buildEpubImageInteractionInstallScript(
    longPressTimeoutMs: Int,
    touchSlopCssPx: Float,
    preserveImageColors: Boolean,
    parentColorsInverted: Boolean,
    paginated: Boolean,
): String =
    """
    (function() {
        const resourceIndex = Number.isInteger(window.__kohariaImageResourceIndex)
            ? window.__kohariaImageResourceIndex
            : -1;
        ${buildEpubImageColorPolicyScript(preserveImageColors, parentColorsInverted)};
        ${buildEpubStandaloneImageLayoutScript(paginated)};

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
private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"
private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
private const val IMAGE_COLOR_POLICY_STYLE_ID = "koharia-epub-image-color-policy"
