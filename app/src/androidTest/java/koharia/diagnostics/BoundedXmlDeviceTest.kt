package koharia.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.core.archive.BoundedXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BoundedXmlDeviceTest {
    @Test
    fun androidParserRetainsMetadataAndRejectsExcessiveNesting() {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>中文 EPUB</dc:title>
                </metadata>
            </package>
        """.trimIndent()
        assertEquals("中文 EPUB", BoundedXml.parse(opf).selectFirst("*|title")?.text())
        assertThrows(IOException::class.java) {
            BoundedXml.parse("<root>".repeat(3000) + "</root>".repeat(3000))
        }
        val declared = """
            <!DOCTYPE root SYSTEM "http://127.0.0.1:1/never-fetch.dtd" [<!ENTITY local "expanded">]>
            <root>&local;</root>
        """.trimIndent()
        assertEquals("&local;", BoundedXml.parse(declared).selectFirst("root")?.text())
    }
}
