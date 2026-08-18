package koharia.importing

import koharia.connection.ConnectionMediaImportDestination
import koharia.connection.ConnectionMediaImportItem
import koharia.connection.ConnectionMediaImportSeries
import koharia.connection.ConnectionMediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IncomingMediaParserTest {

    @Test
    fun `supported filename extension takes priority over generic mime`() {
        assertEquals(
            "epub",
            detectMediaExtension("Book.EPUB", "application/octet-stream", byteArrayOf()),
        )
    }

    @Test
    fun `provider mime identifies files without useful names`() {
        assertEquals(
            "pdf",
            detectMediaExtension("download", "application/pdf", byteArrayOf()),
        )
        assertEquals(
            "cbr",
            detectMediaExtension("download", "application/vnd.comicbook-rar", byteArrayOf()),
        )
    }

    @Test
    fun `magic bytes recover generic binary formats`() {
        assertEquals(
            "pdf",
            detectMediaExtension("download.bin", "application/octet-stream", "%PDF-1.7".encodeToByteArray()),
        )
        assertEquals(
            "7z",
            detectMediaExtension(
                "download.bin",
                "application/octet-stream",
                byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c),
            ),
        )
    }

    @Test
    fun `EPUB mimetype distinguishes a generically named zip container`() {
        val header = byteArrayOf(0x50, 0x4b, 0x03, 0x04) +
            "mimetypeapplication/epub+zip".encodeToByteArray()

        assertEquals(
            "epub",
            detectMediaExtension("download", "application/zip", header),
        )
    }

    @Test
    fun `unsupported media is rejected`() {
        assertNull(detectMediaExtension("notes.txt", "text/plain", "hello".encodeToByteArray()))
    }

    @Test
    fun `multiple numbered files suggest their common series prefix`() {
        val items = listOf(
            importItem("Series Name - 01.cbz"),
            importItem("Series Name - 02.cbz"),
        )

        assertEquals("Series Name", suggestedSeriesName(items))
    }

    @Test
    fun `embedded series takes priority over embedded title`() {
        assertEquals(
            "Collection Name",
            selectIncomingSeriesName(
                listOf(IncomingMediaMetadata(series = "Collection Name", title = "Volume One")),
            ),
        )
    }

    @Test
    fun `single file can use its embedded title when no series exists`() {
        assertEquals(
            "Standalone Book",
            selectIncomingSeriesName(listOf(IncomingMediaMetadata(title = "Standalone Book"))),
        )
    }

    @Test
    fun `multiple files use a shared embedded series`() {
        assertEquals(
            "Shared Series",
            selectIncomingSeriesName(
                listOf(
                    IncomingMediaMetadata(series = "Shared Series", title = "Volume 1"),
                    IncomingMediaMetadata(series = "shared series", title = "Volume 2"),
                ),
            ),
        )
    }

    @Test
    fun `conflicting embedded series fall back to filename suggestion`() {
        val embedded = selectIncomingSeriesName(
            listOf(
                IncomingMediaMetadata(series = "First Series"),
                IncomingMediaMetadata(series = "Second Series"),
            ),
        )
        val items = listOf(importItem("Filename Series - 01.cbz"), importItem("Filename Series - 02.cbz"))

        assertNull(embedded)
        assertEquals("Filename Series", embedded ?: suggestedSeriesName(items))
    }

    @Test
    fun `missing metadata keeps the complete single filename`() {
        val items = listOf(importItem("[再见绘梨][藤本树][Vol 01][完结][bili].cbz"))

        assertNull(selectIncomingSeriesName(listOf(null)))
        assertEquals("[再见绘梨][藤本树][Vol 01][完结][bili]", suggestedSeriesName(items))
    }

    @Test
    fun `placeholder metadata is ignored`() {
        assertNull(selectIncomingSeriesName(listOf(IncomingMediaMetadata(series = "Untitled", title = "Unknown"))))
    }

    @Test
    fun `external media flow starts with actions and routes multiple files to import`() {
        val state = ExternalMediaImportScreenModel.State(
            items = listOf(
                importItem("Series Name - 01.cbz"),
                importItem("Series Name - 02.cbz"),
            ),
            openSourceId = 42L,
        )

        assertEquals(ExternalMediaImportScreenModel.Step.ACTIONS, state.step)
        assertFalse(state.canOpen)
        assertTrue(state.canConfigureImport)
    }

    @Test
    fun `existing series selection is a searchable third level`() {
        val destination = ConnectionMediaImportDestination(
            id = "comic-root",
            name = "Comics",
            mediaType = ConnectionMediaType.COMIC,
            supportedExtensions = setOf("cbz"),
        )
        val alpha = ConnectionMediaImportSeries(
            id = "series-alpha",
            name = "Alpha Series",
            destinationId = destination.id,
            shelfId = "favorites",
        )
        val state = ExternalMediaImportScreenModel.State(
            step = ExternalMediaImportScreenModel.Step.SERIES_SELECTION,
            items = listOf(importItem("Chapter.cbz")),
            connections = listOf(
                ExternalMediaImportScreenModel.ImportConnection(
                    id = 42L,
                    name = "Local",
                    destinations = listOf(destination),
                    shelves = emptyList(),
                ),
            ),
            selectedConnectionId = 42L,
            selectedDestinationId = destination.id,
            selectedShelfId = "favorites",
            seriesTargetMode = ExternalMediaImportScreenModel.SeriesTargetMode.EXISTING,
            existingSeries = listOf(
                alpha,
                ConnectionMediaImportSeries(
                    id = "series-beta",
                    name = "Beta Series",
                    destinationId = destination.id,
                    shelfId = "other",
                ),
            ),
            existingSeriesSearchQuery = "alpha",
        )

        assertEquals(listOf(alpha), state.filteredExistingSeries)
        assertFalse(state.canImport)
        assertTrue(state.copy(selectedExistingSeriesId = alpha.id).canImport)
    }

    private fun importItem(name: String) = ConnectionMediaImportItem(
        uri = "content://test/$name",
        displayName = name,
        mimeType = null,
        sizeBytes = null,
        extension = name.substringAfterLast('.'),
    )
}
