package koharia.source.local

import eu.kanade.tachiyomi.source.model.SManga
import koharia.connection.LibraryMetadataField
import koharia.connection.MetadataFilenameTemplate
import koharia.connection.MetadataSuggestionSource
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalMetadataGeneratorTest {

    @Test
    fun `embedded book title does not replace folder series name without collection metadata`() {
        val embeddedBook = LocalEmbeddedMetadata(title = "Volume title")
        val embeddedSeries = LocalEmbeddedMetadata(title = "Volume title", series = "Series title")
        val directoryMetadata = LocalEmbeddedMetadata(title = "Directory title")

        assertNull(embeddedBook.forSeriesDisplay(isDirectoryMetadata = false).title)
        assertEquals(
            "Series title",
            embeddedSeries.forSeriesDisplay(isDirectoryMetadata = false).title,
        )
        assertEquals(
            "Directory title",
            directoryMetadata.forSeriesDisplay(isDirectoryMetadata = true).title,
        )
    }

    @Test
    fun `OPF parser reads Dublin Core and Calibre series metadata`() {
        val document = Jsoup.parse(
            """
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Book title</dc:title>
                <dc:creator>Author</dc:creator>
                <dc:contributor>Artist</dc:contributor>
                <dc:description>Description</dc:description>
                <dc:subject>Fantasy</dc:subject>
                <meta name="calibre:series" content="Calibre Series" />
              </metadata>
            </package>
            """.trimIndent(),
            "",
            Parser.xmlParser(),
        )

        val result = parseLocalOpfMetadata(document)

        assertEquals("Book title", result.title)
        assertEquals("Calibre Series", result.series)
        assertEquals(listOf("Author"), result.authors)
        assertEquals(listOf("Artist"), result.contributors)
        assertEquals("Description", result.description)
        assertEquals(listOf("Fantasy"), result.subjects)
    }

    @Test
    fun `OPF parser reads EPUB collection metadata without Calibre fields`() {
        val document = Jsoup.parse(
            """
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Book title</dc:title>
                <meta property="belongs-to-collection">EPUB Collection</meta>
              </metadata>
            </package>
            """.trimIndent(),
            "",
            Parser.xmlParser(),
        )

        assertEquals("EPUB Collection", parseLocalOpfMetadata(document).series)
    }

    @Test
    fun `auto mode prefers embedded EPUB series and aggregates book metadata`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Folder fallback",
            itemNames = listOf("Fallback - Vol. 01 - One.epub", "Fallback - Vol. 02 - Two.epub"),
            embeddedMetadata = listOf(
                LocalEmbeddedMetadata(
                    title = "One",
                    series = "Embedded Series",
                    authors = listOf("Author A"),
                    description = "Short",
                    subjects = listOf("Fantasy"),
                ),
                LocalEmbeddedMetadata(
                    title = "Two",
                    series = "Embedded Series",
                    authors = listOf("Author A", "Author B"),
                    description = "A longer series description",
                    subjects = listOf("Fantasy", "Adventure"),
                ),
            ),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("Embedded Series", result.metadata.title)
        assertEquals("Author A, Author B", result.metadata.author)
        assertEquals("A longer series description", result.metadata.description)
        assertEquals(listOf("Fantasy", "Adventure"), result.metadata.genres)
        assertEquals(MetadataSuggestionSource.EPUB_EMBEDDED, result.fieldSources[LibraryMetadataField.TITLE])
    }

    @Test
    fun `explicit volume template uses tolerant volume markers`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Folder fallback",
            itemNames = listOf(
                "My Series - Vol. 01 - Beginning.epub",
                "My Series_v02_End.epub",
                "My Series 第3卷 终章.epub",
                "My Series [04] Extra.epub",
            ),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.SERIES_VOLUME_TITLE,
        )

        assertEquals("My Series", result.metadata.title)
        assertEquals(4, result.matchedFilenameCount)
        assertEquals(MetadataSuggestionSource.ITEM_FILENAME, result.fieldSources[LibraryMetadataField.TITLE])
    }

    @Test
    fun `single EPUB title is used when collection metadata is absent`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Unhelpful folder",
            itemNames = listOf("book.epub"),
            embeddedMetadata = listOf(
                LocalEmbeddedMetadata(
                    title = "The Embedded Book Title",
                    authors = listOf("Author"),
                ),
            ),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("The Embedded Book Title", result.metadata.title)
        assertEquals(MetadataSuggestionSource.EPUB_EMBEDDED, result.fieldSources[LibraryMetadataField.TITLE])
    }

    @Test
    fun `chapter template recognizes English and Chinese chapter names`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Folder fallback",
            itemNames = listOf(
                "Comic Ch. 001 Start.cbz",
                "Comic 第2话 继续.cbz",
                "Comic Episode 003 Finish.cbz",
            ),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.SERIES_CHAPTER_TITLE,
        )

        assertEquals("Comic", result.metadata.title)
        assertEquals(3, result.matchedFilenameCount)
    }

    @Test
    fun `folder template only proposes fields backed by available evidence`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Folder Series",
            itemNames = listOf("01.epub", "02.epub"),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.FOLDER_ITEM_TITLE,
        )

        assertEquals("Folder Series", result.metadata.title)
        assertEquals(2, result.matchedFilenameCount)
        assertEquals(setOf(LibraryMetadataField.TITLE), result.fieldSources.keys)
        assertFalse(LibraryMetadataField.AUTHOR in result.fieldSources)
    }

    @Test
    fun `series title template keeps the first delimited segment`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Folder fallback",
            itemNames = listOf("Series Name - First Book.epub", "Series Name — Second Book.epub"),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.SERIES_TITLE,
        )

        assertEquals("Series Name", result.metadata.title)
        assertEquals(2, result.matchedFilenameCount)
    }

    @Test
    fun `auto mode recognizes bracketed title author volume status and source tag`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Import",
            itemNames = listOf("[再见绘梨][藤本树][Vol 01][完结][bili].cbz"),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("再见绘梨", result.metadata.title)
        assertEquals("藤本树", result.metadata.author)
        assertEquals(SManga.COMPLETED, result.metadata.status)
        assertEquals(MetadataSuggestionSource.ITEM_FILENAME, result.fieldSources[LibraryMetadataField.TITLE])
        assertEquals(MetadataSuggestionSource.ITEM_FILENAME, result.fieldSources[LibraryMetadataField.AUTHOR])
        assertEquals(MetadataSuggestionSource.ITEM_FILENAME, result.fieldSources[LibraryMetadataField.STATUS])
        assertEquals(1, result.matchedFilenameCount)
    }

    @Test
    fun `auto mode recognizes bracketed folder metadata`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "【再见绘梨】【藤本树】【Vol 01】【完结】【bili】",
            itemNames = listOf("001.cbz"),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("再见绘梨", result.metadata.title)
        assertEquals("藤本树", result.metadata.author)
        assertEquals(SManga.COMPLETED, result.metadata.status)
        assertEquals(MetadataSuggestionSource.FOLDER, result.fieldSources[LibraryMetadataField.TITLE])
        assertEquals(MetadataSuggestionSource.FOLDER, result.fieldSources[LibraryMetadataField.AUTHOR])
        assertEquals(MetadataSuggestionSource.FOLDER, result.fieldSources[LibraryMetadataField.STATUS])
    }

    @Test
    fun `auto mode progressively extracts aliases multiple authors and structural tags`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Import",
            itemNames = listOf(
                "[再见绘梨_さよなら絵梨][藤本树×Fujimoto Tatsuki][Vol 01][完结][境外版][bili].cbz",
            ),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("再见绘梨", result.metadata.title)
        assertEquals("藤本树, Fujimoto Tatsuki", result.metadata.author)
        assertEquals(SManga.COMPLETED, result.metadata.status)
        assertEquals(1, result.matchedFilenameCount)
    }

    @Test
    fun `auto mode extracts an outside title before classifying bracketed metadata`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Import",
            itemNames = listOf(
                "再见绘梨_さよなら絵梨[藤本树×Fujimoto Tatsuki][境外版][第1卷][完结][bili].cbz",
            ),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("再见绘梨", result.metadata.title)
        assertEquals("藤本树, Fujimoto Tatsuki", result.metadata.author)
        assertEquals(SManga.COMPLETED, result.metadata.status)
    }

    @Test
    fun `auto mode removes edition and status suffixes from a folder title`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "再见绘梨（境外版）（单行本）（完结）",
            itemNames = listOf("001.cbz"),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("再见绘梨", result.metadata.title)
        assertEquals(SManga.COMPLETED, result.metadata.status)
        assertEquals(MetadataSuggestionSource.FOLDER, result.fieldSources[LibraryMetadataField.STATUS])
    }

    @Test
    fun `explicit volume template recognizes trailing bare volume numbers`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Folder fallback",
            itemNames = listOf("Series Name 01.cbz", "Series Name (02).cbz"),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.SERIES_VOLUME_TITLE,
        )

        assertEquals("Series Name", result.metadata.title)
        assertEquals(2, result.matchedFilenameCount)
    }

    @Test
    fun `metadata title keeps meaningful punctuation while normalizing aliases`() {
        val result = generateLocalMetadataSuggestion(
            folderName = "Import",
            itemNames = listOf("[咒术回战：怀玉·玉折_呪術廻戦][芥见下下][Vol 01][完结].cbz"),
            embeddedMetadata = emptyList(),
            filenameTemplate = MetadataFilenameTemplate.AUTO,
        )

        assertEquals("咒术回战：怀玉·玉折", result.metadata.title)
        assertEquals("芥见下下", result.metadata.author)
    }
}
