package koharia.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResolveHeadingsTest {
    @Test
    fun `intro mentioning a heading does not become its destination`() {
        val pages = listOf("Chapter in contents\n", "Chapter\nbody")
        assertEquals(
            listOf(DocumentHeading(1, "Chapter", 1)),
            resolveHeadings(listOf(RawDocumentHeading(1, "Chapter", pages[0].length)), pages),
        )
    }

    @Test
    fun `heading crossing page boundary keeps its starting page`() {
        assertEquals(
            listOf(DocumentHeading(1, "Long title", 0)),
            resolveHeadings(listOf(RawDocumentHeading(1, "Long title", 5)), listOf("body Long", " title")),
        )
    }

    @Test
    fun `repeated headings remain distinct across reflow`() {
        val headings = listOf(RawDocumentHeading(1, "Notes", 0), RawDocumentHeading(2, "Notes", 6))
        assertEquals(
            listOf(DocumentHeading(1, "Notes", 0), DocumentHeading(2, "Notes", 1)),
            resolveHeadings(headings, listOf("Notes\n", "Notes")),
        )
        assertEquals(
            listOf(DocumentHeading(1, "Notes", 0), DocumentHeading(2, "Notes", 0)),
            resolveHeadings(headings, listOf("Notes\nNotes")),
        )
    }

    @Test
    fun `invalid offsets and empty titles are excluded`() {
        assertEquals(
            emptyList<DocumentHeading>(),
            resolveHeadings(
                listOf(RawDocumentHeading(1, "x", -1), RawDocumentHeading(1, "x", 4), RawDocumentHeading(1, "", 0)),
                listOf("body"),
            ),
        )
        assertEquals(emptyList<DocumentHeading>(), resolveHeadings(emptyList(), emptyList()))
    }
}
