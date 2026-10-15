package koharia.kavita

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import koharia.connection.ConnectionReflowProgressAdapter
import koharia.epub.service.PdfReflowProgressAdapter
import koharia.pdf.reflow.PdfBox
import koharia.pdf.reflow.PdfMappedBlock
import koharia.pdf.reflow.PdfProgressMapper
import koharia.pdf.reflow.PdfReflowManifest
import koharia.pdf.reflow.PdfSourceSpan
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.util.Date

class KavitaPdfProgressTest {
    @Test fun persistedVisibleBlockControlsConfirmationInsteadOfVisualProgression() = runTest {
        val remote = mockk<ConnectionReflowProgressAdapter>(relaxed = true)
        val blocks = (0..3).map { page ->
            PdfMappedBlock("page-$page", "part.xhtml", PdfSourceSpan(page, 0, 50, PdfBox(0f, 0f, 1f, 1f)), "text")
        }
        val manifest = PdfReflowManifest("revision", "hash", 4, blocks)
        val href = mockk<org.readium.r2.shared.util.Url>()
        every { href.toString() } returns "part.xhtml"
        val locator = org.readium.r2.shared.publication.Locator(
            href = href,
            mediaType = org.readium.r2.shared.util.mediatype.MediaType.XHTML,
            locations = org.readium.r2.shared.publication.Locator.Locations(progression = 0.25),
        )
        assertEquals(0, PdfProgressMapper.block(manifest, locator, null)?.source?.page)
        val saved = "persisted-visible-page-one"
        val source = mockk<JSONObject> {
            every { optString("sourceHash") } returns "hash"
            every { optString("revision") } returns "revision"
            every { optString("blockId") } returns "page-1"
            every { optInt("page", -1) } returns 1
        }
        mockkConstructor(JSONObject::class)
        try {
            every { anyConstructed<JSONObject>().optJSONObject("kohariaPdfSource") } returns source
            val mapped = PdfProgressMapper.progressLocator(manifest, locator, saved)
            assertEquals(1, PdfProgressMapper.block(manifest, mapped, null)?.source?.page)
            assertEquals(locator.locations.progression, mapped.locations.progression)
            val chapter = Chapter.create().copy(id = 1, mangaId = 2, url = "chapter.pdf")
            val adapter = PdfReflowProgressAdapter(remote, chapter, manifest)
            coEvery { remote.isPageProgressConfirmed("chapter.pdf", 1, 4, 100) } returns true
            assertEquals(true, adapter.isEpubProgressConfirmed(chapter.url, mapped, Date(100)))
            adapter.recordLocalEpubProgress(chapter.url, mapped, Date(100))
            coVerify { remote.recordLocalPageProgress("chapter.pdf", 1, 4, 100, false) }
            assertEquals(
                locator,
                PdfProgressMapper.progressLocator(manifest.copy(revision = "other"), locator, saved),
            )
            assertEquals(
                locator,
                PdfProgressMapper.progressLocator(manifest.copy(sourceHash = "other"), locator, saved),
            )
        } finally {
            unmockkConstructor(JSONObject::class)
        }
    }

    @Test fun reflowUploadsOriginalPdfPagesAndNotReadiumPagination() = runTest {
        val remote = mockk<ConnectionReflowProgressAdapter>(relaxed = true)
        val manifest = PdfReflowManifest(
            "revision",
            "hash",
            12,
            listOf(PdfMappedBlock("block", "part.xhtml", PdfSourceSpan(7, 10, 50, PdfBox(0f, 0f, 1f, 1f)), "text")),
        )
        val chapter = Chapter.create().copy(id = 1, mangaId = 2, url = "chapter.pdf")
        val adapter = PdfReflowProgressAdapter(remote, chapter, manifest)
        val href = mockk<org.readium.r2.shared.util.Url>()
        every { href.toString() } returns "part.xhtml"
        val locator = org.readium.r2.shared.publication.Locator(
            href = href,
            mediaType = org.readium.r2.shared.util.mediatype.MediaType.XHTML,
            locations = org.readium.r2.shared.publication.Locator.Locations(
                fragments = listOf("block"),
                position = 999,
                totalProgression = 0.95,
            ),
        )
        adapter.recordLocalEpubProgress(chapter.url, locator, Date(100))
        adapter.pushEpubProgress(chapter.url, locator, emptyList(), Date(100))
        coVerify(exactly = 1) { remote.recordLocalPageProgress("chapter.pdf", 7, 12, 100, false) }
        coVerify(exactly = 1) { remote.pushPageProgress("chapter.pdf", 7, 12) }
        assertEquals(7, PdfProgressMapper.block(manifest, locator, null)?.source?.page)
        coEvery { remote.isPageProgressConfirmed("chapter.pdf", 7, 12, 100) } returns false
        assertEquals(false, adapter.isEpubProgressConfirmed(chapter.url, locator, Date(100)))
        coEvery { remote.isPageProgressConfirmed("chapter.pdf", 7, 12, 100) } returns true
        assertEquals(true, adapter.isEpubProgressConfirmed(chapter.url, locator, Date(100)))
    }
}
