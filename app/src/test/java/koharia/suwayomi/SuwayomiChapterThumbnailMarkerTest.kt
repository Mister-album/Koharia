package koharia.suwayomi

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The chapter preview marker. Suwayomi has no chapter thumbnail, so a preview is the chapter's own
 * first page, fetched lazily through the connection's authenticated client. The marker carries the
 * chapter URL because the cover fetcher only passes a URL back to the source.
 */
class SuwayomiChapterThumbnailMarkerTest {
    private val prefix = "koharia-local-v1:suwayomi/"

    private fun marker(chapterUrl: String) = prefix + chapterUrl.encodeUtf8().base64Url()

    @Test
    fun `the marker carries the chapter url`() {
        val chapterUrl = "/suwayomi/10/355bd01d/manga/564/chapter/564/9001"

        val decoded = marker(chapterUrl).removePrefix(prefix).decodeBase64()?.utf8()

        assertEquals(chapterUrl, decoded)
    }

    @Test
    fun `the marker keeps the characters the cover fetcher looks for`() {
        // `MangaCoverFetcher.getResourceType` routes only `koharia-local-v*` through the adapter.
        assertEquals(true, marker("/suwayomi/10/acct/manga/1/chapter/1/2").startsWith("koharia-local-v"))
    }

    @Test
    fun `a malformed marker decodes to nothing rather than throwing`() {
        assertNull("!!!not-base64!!!".decodeBase64())
    }

    @Test
    fun `account identity survives the round trip`() {
        // The account is part of the chapter URL, so a preview can never be requested for another one.
        val account = "a".repeat(64)
        val chapterUrl = "/suwayomi/7/$account/chapter/2/3"

        val decoded = marker(chapterUrl).removePrefix(prefix).decodeBase64()?.utf8().orEmpty()

        assertEquals(chapterUrl, decoded)
        val parts = decoded.split('/')
        assertEquals("suwayomi", parts[1])
        assertEquals(7L, parts[2].toLong())
        assertEquals(account, parts[3])
    }
}
