package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagerLayoutPolicyTest {
    @Test
    fun `automatic double splits in portrait and restores whole images in landscape with either split preference`() {
        for (split in listOf(false, true)) {
            val portrait = PagerLayoutPolicy.resolve(PageLayout.AUTOMATIC_DOUBLE_PAGES, split, true, false)
            assertFalse(portrait.doublePages)
            assertTrue(portrait.automaticSplit)
            val landscape = PagerLayoutPolicy.resolve(PageLayout.AUTOMATIC_DOUBLE_PAGES, split, true, true)
            assertTrue(landscape.doublePages)
            assertFalse(landscape.splitsWidePages)
        }
    }

    @Test
    fun `manual splitting and automatic single remain split regardless of viewport`() {
        for (wide in listOf(false, true)) {
            val manual = PagerLayoutPolicy.resolve(PageLayout.SINGLE_PAGE, true, true, wide)
            assertFalse(manual.doublePages)
            assertTrue(manual.manualSplit)
            val automatic = PagerLayoutPolicy.resolve(PageLayout.AUTOMATIC_SINGLE_PAGE, false, true, wide)
            assertFalse(automatic.doublePages)
            assertTrue(automatic.automaticSplit)
        }
    }

    @Test
    fun `fixed double and vertical readers retain their layout limits`() {
        assertTrue(PagerLayoutPolicy.resolve(PageLayout.DOUBLE_PAGES, false, true, false).doublePages)
        assertFalse(PagerLayoutPolicy.resolve(PageLayout.DOUBLE_PAGES, false, false, true).doublePages)
        assertFalse(PagerLayoutPolicy.resolve(PageLayout.AUTOMATIC_DOUBLE_PAGES, false, false, true).splitsWidePages)
        assertTrue(PagerLayoutPolicy.resolve(PageLayout.AUTOMATIC_DOUBLE_PAGES, true, false, true).manualSplit)
        assertFalse(PagerLayoutPolicy.resolve(PageLayout.SINGLE_PAGE, false, true, true).splitsWidePages)
    }
}
