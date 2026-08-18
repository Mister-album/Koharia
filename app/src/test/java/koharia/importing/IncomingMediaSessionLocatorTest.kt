package koharia.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IncomingMediaSessionLocatorTest {

    @Test
    fun `series and chapter locators round trip`() {
        val sourceId = 42L
        val sessionId = "session 01"
        val fileName = "Book name 第1卷.epub"

        assertEquals(
            IncomingMediaSessionLocator.Location(sessionId, null),
            IncomingMediaSessionLocator.location(
                IncomingMediaSessionLocator.seriesUrl(sourceId, sessionId),
                sourceId,
            ),
        )
        assertEquals(
            IncomingMediaSessionLocator.Location(sessionId, fileName),
            IncomingMediaSessionLocator.location(
                IncomingMediaSessionLocator.chapterUrl(sourceId, sessionId, fileName),
                sourceId,
            ),
        )
    }

    @Test
    fun `locator is isolated by connection source id`() {
        val locator = IncomingMediaSessionLocator.chapterUrl(42L, "session", "book.epub")

        assertNull(IncomingMediaSessionLocator.location(locator, 43L))
    }

    @Test
    fun `unsafe and malformed locator segments are rejected`() {
        assertNull(IncomingMediaSessionLocator.location("koharia-incoming-v1://42/..", 42L))
        assertNull(IncomingMediaSessionLocator.location("koharia-incoming-v1://42/session/%2E%2E", 42L))
        assertNull(IncomingMediaSessionLocator.location("koharia-incoming-v1://42/session/%2Ftmp", 42L))
        assertNull(IncomingMediaSessionLocator.location("koharia-incoming-v1://42/session/%", 42L))
        assertNull(IncomingMediaSessionLocator.location("koharia-incoming-v1://42/session/file/extra", 42L))
    }
}
