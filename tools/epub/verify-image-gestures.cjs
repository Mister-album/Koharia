// Run from the repository root: node tools/epub/verify-image-gestures.cjs
// Executes the production image event handlers with a minimal DOM/bridge fixture.
// This checks handler behavior, not Android/WebView's physical event generation.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/java/koharia/epub/EpubImageInteraction.kt'), 'utf8');
const start = source.indexOf('        const existing = window.__kohariaImageInteractions;');
const end = source.indexOf("        return 'installed';", start);
assert.ok(start >= 0 && end > start, 'Production handler boundaries must exist');
const script = source.slice(start, end)
    .replaceAll('$SVG_NAMESPACE', 'http://www.w3.org/2000/svg')
    .replaceAll('$XLINK_NAMESPACE', 'http://www.w3.org/1999/xlink')
    .replaceAll('$EPUB_IMAGE_BRIDGE_NAME', 'KohariaEpubImage')
    .replaceAll('$longPressTimeoutMs', '500')
    .replaceAll('$touchSlopCssPx', '8');
let passed = 0;
for (const svg of [false, true]) {
    function fixture(linked = false, rect = {left: -200, top: -300, right: 200, bottom: 300, width: 400, height: 600}) {
        const handlers = {}, calls = [], timers = new Map();
        let timerId = 0;
        const image = {
            tagName: svg ? 'image' : 'IMG', localName: svg ? 'image' : 'img',
            namespaceURI: svg ? 'http://www.w3.org/2000/svg' : 'http://www.w3.org/1999/xhtml',
            src: 'https://fixture.invalid/image.png', href: { baseVal: 'image.png' },
            getAttribute: key => ['src', 'href'].includes(key) ? 'image.png' : null,
            closest: () => linked ? {} : null,
            getBoundingClientRect: () => rect,
        };
        const window = {
            setTimeout: fn => { timers.set(++timerId, fn); return timerId; },
            KohariaEpubImage: { onImageInteraction: action => calls.push(action) },
        };
        const document = {baseURI: 'https://fixture.invalid/', addEventListener: (name, fn) => {handlers[name] = fn;}};
        vm.runInNewContext('(function(){const resourceIndex=0;' + script + '})()', {
            window, document, clearTimeout: id => timers.delete(id), URL,
        });
        return {
            calls,
            send(type, overrides = {}) {
                const event = {
                    target: image, pointerId: 1, isPrimary: true, button: 0,
                    clientX: 0, clientY: 0, detail: 1, pointerType: 'touch', prevented: false,
                    preventDefault() { this.prevented = true; }, stopImmediatePropagation() {}, ...overrides,
                };
                handlers[type](event);
                return event;
            },
            longPress() { for (const fn of [...timers.values()]) fn(); timers.clear(); },
        };
    }
    function check(name, test) { test(); passed++; }
    for (const point of [{clientX: -180}, {clientX: 180}, {clientY: -280}, {clientY: 280}, {clientX: 168}]) {
        check('edge tap falls through to reading navigation', () => {
            const f = fixture(); f.send('pointerdown', point); f.send('pointerup', point);
            assert.equal(f.send('click', point).prevented, false);
            assert.deepEqual(f.calls, []);
        });
    }
    check('just inside safe area previews', () => {
        const f = fixture(), point = {clientX: 167};
        f.send('pointerdown', point); f.send('pointerup', point); f.send('click', point);
        assert.deepEqual(f.calls, ['preview']);
    });
    check('jitter from ring into interior does not preview', () => {
        const f = fixture(); f.send('pointerdown', {clientX: 170}); f.send('pointerup', {clientX: 166});
        assert.equal(f.send('click', {clientX: 166}).prevented, false); assert.deepEqual(f.calls, []);
    });
    check('small image keeps a usable center', () => {
        const f = fixture(false, {left: -10, right: 10, top: -10, bottom: 10, width: 20, height: 20});
        f.send('pointerdown'); f.send('pointerup'); f.send('click'); assert.deepEqual(f.calls, ['preview']);
    });
    check('zero-sized image does not preview', () => {
        const f = fixture(false, {left: 0, right: 0, top: 0, bottom: 0, width: 0, height: 0});
        f.send('pointerdown'); f.send('pointerup'); f.send('click'); assert.deepEqual(f.calls, []);
    });
    check('edge long press still opens image actions', () => {
        const f = fixture(), point = {clientX: 180};
        f.send('pointerdown', point); f.longPress(); f.send('pointerup', point); f.send('click', point);
        assert.deepEqual(f.calls, ['actions']);
    });
    check('ordinary tap', () => {
        const f = fixture(); f.send('pointerdown'); f.send('pointerup'); f.send('click');
        assert.deepEqual(f.calls, ['preview']);
    });
    check('small jitter still taps', () => {
        const f = fixture(); f.send('pointerdown'); f.send('pointermove', {clientX: 4}); f.send('pointerup', {clientX: 4}); f.send('click');
        assert.deepEqual(f.calls, ['preview']);
    });
    for (const cancelled of ['move', 'return', 'upOnly', 'scroll', 'cancel', 'multiTouch', 'diagonal']) {
        check(cancelled, () => {
            const f = fixture(); f.send('pointerdown');
            if (cancelled === 'move' || cancelled === 'return') f.send('pointermove', {clientX: 12});
            if (cancelled === 'return') f.send('pointermove');
            if (cancelled === 'diagonal') f.send('pointermove', {clientX: 6, clientY: 6});
            if (cancelled === 'scroll') f.send('scroll');
            if (cancelled === 'cancel') f.send('pointercancel');
            if (cancelled === 'multiTouch') f.send('pointerdown', {pointerId: 2, isPrimary: false});
            f.send('pointerup', {clientX: cancelled === 'upOnly' ? 12 : 0});
            assert.equal(f.send('click').prevented, true);
            assert.deepEqual(f.calls, []);
            // Cancellation cannot poison the next intentional tap.
            f.send('pointerdown'); f.send('pointerup'); f.send('click');
            assert.deepEqual(f.calls, ['preview']);
        });
    }
    check('long press does not also preview', () => {
        const f = fixture(); f.send('pointerdown'); f.longPress(); f.send('contextmenu'); f.send('pointerup'); f.send('click');
        assert.deepEqual(f.calls, ['actions']);
    });
    check('movement cancels long press and context menu', () => {
        const f = fixture(); f.send('pointerdown'); f.send('pointermove', {clientY: 15}); f.longPress(); f.send('contextmenu');
        assert.deepEqual(f.calls, []);
    });
    check('linked image keeps navigation', () => {
        const f = fixture(true); f.send('pointerdown'); f.send('pointerup');
        assert.equal(f.send('click').prevented, false); assert.deepEqual(f.calls, []);
    });
    check('dragged linked image is not activated', () => {
        const f = fixture(true); f.send('pointerdown'); f.send('pointermove', {clientX: 15}); f.send('pointerup');
        assert.equal(f.send('click').prevented, true); assert.deepEqual(f.calls, []);
    });
    check('accessibility activation after cancelled touch', () => {
        const f = fixture(); f.send('pointerdown'); f.send('pointercancel');
        f.send('click', {detail: 0, pointerType: ''}); assert.deepEqual(f.calls, ['preview']);
    });
    check('secondary button context menu', () => {
        const f = fixture(); f.send('pointerdown'); f.send('pointercancel');
        f.send('pointerdown', {button: 2}); f.send('contextmenu'); assert.deepEqual(f.calls, ['actions']);
    });
    check('down outside image cannot become image tap', () => {
        const f = fixture(); f.send('pointerdown', {target: {tagName: 'P'}}); f.send('pointerup'); f.send('click');
        assert.deepEqual(f.calls, []);
    });
}
console.log(`${passed} image gesture cases passed (HTML and SVG).`);
