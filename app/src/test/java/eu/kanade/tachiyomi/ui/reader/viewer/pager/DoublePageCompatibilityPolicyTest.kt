package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoublePageCompatibilityPolicyTest {

    @Test
    fun `regular portrait pages with matching proportions can pair`() {
        assertTrue(policy(1200, 1800, 1600, 2400))
    }

    @Test
    fun `matching proportions with clearly different source sizes stay separate`() {
        assertFalse(policy(800, 1200, 2400, 3600))
    }

    @Test
    fun `a clearly different width or height stays separate`() {
        assertFalse(policy(1200, 1800, 1700, 1800))
        assertFalse(policy(1200, 1800, 1200, 2500))
    }

    @Test
    fun `pages with visibly different proportions stay separate`() {
        assertFalse(policy(900, 1800, 1500, 1800))
    }

    @Test
    fun `unusually narrow and nearly square pages stay separate`() {
        assertFalse(policy(500, 1800, 1200, 1800))
        assertFalse(policy(1700, 1800, 1200, 1800))
    }

    @Test
    fun `wide and animated pages never pair`() {
        val portrait = info(1200, 1800)
        assertFalse(
            DoublePageCompatibilityPolicy.canPair(
                info(2000, 1200, ReaderPage.SpreadKind.WIDE),
                portrait,
            ),
        )
        assertFalse(
            DoublePageCompatibilityPolicy.canPair(
                info(1200, 1800, ReaderPage.SpreadKind.ANIMATED),
                portrait,
            ),
        )
    }

    @Test
    fun `unknown pages pair provisionally so their headers can be loaded`() {
        assertTrue(
            DoublePageCompatibilityPolicy.canPair(
                ReaderPage.SpreadInfo.UNKNOWN,
                info(1200, 1800),
            ),
        )
    }

    private fun policy(firstWidth: Int, firstHeight: Int, secondWidth: Int, secondHeight: Int): Boolean {
        return DoublePageCompatibilityPolicy.canPair(
            info(firstWidth, firstHeight),
            info(secondWidth, secondHeight),
        )
    }

    private fun info(
        width: Int,
        height: Int,
        kind: ReaderPage.SpreadKind = ReaderPage.SpreadKind.PAIRABLE,
    ) = ReaderPage.SpreadInfo(kind, width, height)
}
