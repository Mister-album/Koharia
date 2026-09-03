package koharia.epub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubImageInteractionTest {

    private val script = buildEpubImageInteractionInstallScript(
        longPressTimeoutMs = 500,
        touchSlopCssPx = 8f,
        preserveImageColors = true,
        parentColorsInverted = false,
        paginated = true,
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

    @Test
    fun `script keeps images unchanged in Readium night mode`() {
        assertTrue(script.contains("koharia-epub-image-color-policy"))
        assertTrue(script.contains("readium-night-on"))
        assertTrue(script.contains("filter: none !important"))
    }

    @Test
    fun `script counter-inverts images when the parent view is inverted`() {
        val invertedScript = buildEpubImageInteractionInstallScript(
            longPressTimeoutMs = 500,
            touchSlopCssPx = 8f,
            preserveImageColors = true,
            parentColorsInverted = true,
            paginated = true,
        )

        assertTrue(invertedScript.contains("const parentColorsInverted = true"))
        assertTrue(invertedScript.contains("filter: invert(100%) !important"))
        assertTrue(invertedScript.contains("document.createElementNS(styleNamespace, 'style')"))
        assertTrue(
            invertedScript.contains("document.documentElement.namespaceURI === 'http://www.w3.org/2000/svg'"),
        )
        assertTrue(invertedScript.contains(":root svg svg"))
    }

    @Test
    fun `paginated standalone images fit without disabling preview`() {
        assertTrue(script.contains("function standaloneImageContainer(image)"))
        assertTrue(script.contains("function fitStandaloneImage(state)"))
        assertTrue(script.contains("containerRect.width / imageRect.width"))
        assertTrue(script.contains("renderedWidth * scale"))
        assertTrue(script.contains("renderedHeight * scale"))
        assertTrue(script.contains("restoreState(previousState)"))
        assertTrue(script.contains("return previousState ? 'removed' : 'ignored'"))
        assertTrue(script.contains("standaloneContainer.style.setProperty('visibility', 'hidden', 'important')"))
        assertTrue(script.contains("standaloneImage.addEventListener('load', state.loadListener, { once: true })"))
        assertTrue(script.contains("container.style.setProperty('overflow-x', 'hidden', 'important')"))
        assertFalse(script.contains("if (paginated && standaloneImageContainer(image)) return"))
        assertTrue(script.contains("if (notify('preview', image))"))
    }

    @Test
    fun `continuous scroll hides frames until image policy is installed`() {
        val continuousScript = buildEpubContinuousScrollInstallScript(
            resources = listOf(
                EpubContinuousScrollResource(0, "chapter.xhtml", "https://readium/chapter.xhtml"),
            ),
            currentIndex = 0,
            initialProgression = 0.0,
            imageInteractionScript = script,
            contentPreparationScript = "",
        )

        assertTrue(continuousScript.contains("iframe.style.visibility = 'hidden'"))
        assertTrue(continuousScript.contains("iframe.style.visibility = 'visible'"))
    }
}
