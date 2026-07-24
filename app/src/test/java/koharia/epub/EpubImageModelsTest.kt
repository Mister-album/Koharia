package koharia.epub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubImageModelsTest {

    @Test
    fun `new image request invalidates every older request`() {
        val tracker = EpubImageRequestTracker()

        val firstImageRequest = tracker.next()
        val secondImageRequest = tracker.next()
        val sameFirstImageRequestedAgain = tracker.next()

        assertFalse(tracker.isCurrent(firstImageRequest))
        assertFalse(tracker.isCurrent(secondImageRequest))
        assertTrue(tracker.isCurrent(sameFirstImageRequestedAgain))
    }

    @Test
    fun `closing image overlay invalidates active request`() {
        val tracker = EpubImageRequestTracker()
        val activeRequest = tracker.next()

        tracker.invalidate()

        assertFalse(tracker.isCurrent(activeRequest))
    }
}
