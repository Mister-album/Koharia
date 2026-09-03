package eu.kanade.tachiyomi.ui.eink

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.presentation.core.motion.EInkRefreshScheduler
import tachiyomi.presentation.core.motion.EInkRefreshScheduler.RequestResult

class EInkRefreshSchedulerTest {

    @Test
    fun `scheduler refreshes first event and follows interval`() {
        val scheduler = EInkRefreshScheduler(interval = 2)

        assertEquals(RequestResult.START, scheduler.request("route:library"))
        scheduler.finish()
        assertEquals(RequestResult.IGNORED, scheduler.request("route:details"))
        assertEquals(RequestResult.START, scheduler.request("route:settings"))
    }

    @Test
    fun `scheduler deduplicates keys and coalesces active requests`() {
        val scheduler = EInkRefreshScheduler()

        assertEquals(RequestResult.START, scheduler.request("tab:library"))
        assertEquals(RequestResult.IGNORED, scheduler.request("tab:library"))
        assertEquals(RequestResult.QUEUED, scheduler.request("tab:history"))
        assertEquals(RequestResult.QUEUED, scheduler.request("tab:more"))
        scheduler.finish()
        assertEquals(RequestResult.START, scheduler.request("tab:library"))
    }

    @Test
    fun `changing interval resets scheduler state`() {
        val scheduler = EInkRefreshScheduler()
        assertEquals(RequestResult.START, scheduler.request("route:a"))

        scheduler.setInterval(3)

        assertEquals(RequestResult.START, scheduler.request("route:a"))
        scheduler.finish()
        assertEquals(RequestResult.IGNORED, scheduler.request("route:b"))
        assertEquals(RequestResult.IGNORED, scheduler.request("route:c"))
        assertEquals(RequestResult.START, scheduler.request("route:d"))
    }
}
