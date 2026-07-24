package koharia.epub

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubImageInteractionTest {

    private val script = buildEpubImageInteractionInstallScript(
        longPressTimeoutMs = 500,
        touchSlopCssPx = 8f,
    )

    @Test
    fun `script recognizes html and svg image elements`() {
        assertTrue(script.contains("tagName === 'img'"))
        assertTrue(script.contains("tagName === 'image'"))
        assertTrue(script.contains("element.namespaceURI === 'http://www.w3.org/2000/svg'"))
    }

    @Test
    fun `script reads svg2 and legacy xlink image sources`() {
        assertTrue(script.contains("image.getAttribute('href')"))
        assertTrue(script.contains("image.getAttributeNS('http://www.w3.org/1999/xlink', 'href')"))
        assertTrue(script.contains("image.getAttribute('xlink:href')"))
        assertTrue(script.contains("href.baseVal || href.animVal"))
        assertTrue(script.contains("new URL(source, document.baseURI).href"))
    }

    @Test
    fun `linked images retain their navigation behavior`() {
        assertTrue(script.contains("image.closest('a[href]')"))
    }
}
