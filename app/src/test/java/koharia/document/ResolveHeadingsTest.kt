package koharia.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for [resolveHeadings]. The function is the binding layer between
 * [RawDocumentHeading] descriptors (emitted by an engine at open time) and the paginated
 * [CharSequence] pages that the reader renders. Both sides are pure inputs here, so the
 * Android layout machinery is not required to exercise the resolution rules.
 */
class ResolveHeadingsTest {

    @Test
    fun `empty inputs return an empty list`() {
        assertEquals(emptyList<DocumentHeading>(), resolveHeadings(emptyList(), emptyList()))
        assertEquals(
            emptyList<DocumentHeading>(),
            resolveHeadings(listOf(RawDocumentHeading(1, "x")), emptyList()),
        )
    }

    @Test
    fun `first matching page wins and ordering is monotonic`() {
        val pages = listOf("alpha", "beta", "gamma")
        val raws = listOf(
            RawDocumentHeading(1, "alpha"),
            RawDocumentHeading(2, "beta"),
            RawDocumentHeading(1, "gamma"),
        )
        assertEquals(
            listOf(
                DocumentHeading(1, "alpha", 0),
                DocumentHeading(2, "beta", 1),
                DocumentHeading(1, "gamma", 2),
            ),
            resolveHeadings(raws, pages),
        )
    }

    @Test
    fun `duplicate titles bind to their respective occurrences, not all to the first`() {
        val pages = listOf(
            "intro",
            "Notes", // first "Notes"
            "body",
            "Notes", // second "Notes"
            "outro",
        )
        val raws = listOf(
            RawDocumentHeading(1, "Notes"),
            RawDocumentHeading(2, "Notes"),
        )
        assertEquals(
            listOf(
                DocumentHeading(1, "Notes", 1),
                DocumentHeading(2, "Notes", 3),
            ),
            resolveHeadings(raws, pages),
        )
    }

    @Test
    fun `documented limitation - text match binds to the earliest page containing the title`() {
        // "Chapter" appears as ordinary body text on page 0 and as a real heading on page 1.
        // Text-based matching cannot tell the two apart, so the earliest page wins. This test
        // pins the *current* (known-limited) behaviour so a future offset-based rewrite is a
        // deliberate, visible change rather than a silent regression.
        val pages = listOf("Chapter starts here", "Chapter", "body")
        val raws = listOf(RawDocumentHeading(1, "Chapter"))
        assertEquals(
            listOf(DocumentHeading(1, "Chapter", 0)),
            resolveHeadings(raws, pages),
        )
    }

    @Test
    fun `title found on a later page than the previous heading stays monotonic`() {
        val pages = listOf("first", "second", "third")
        val raws = listOf(
            RawDocumentHeading(1, "first"),
            RawDocumentHeading(2, "third"),
            RawDocumentHeading(3, "second"),
        )
        // "second" is requested after "third" bound to page 2; the cursor cannot move backwards,
        // so "second" is not found (it was already passed) and is dropped instead of
        // producing a non-monotonic entry.
        assertEquals(
            listOf(
                DocumentHeading(1, "first", 0),
                DocumentHeading(2, "third", 2),
            ),
            resolveHeadings(raws, pages),
        )
    }

    @Test
    fun `two identically-titled headings on the same page both bind to that page`() {
        // Short pages / repeated section names can put two "Notes" headings on one page; both
        // must be kept (the within-page cursor advances past the first match).
        val pages = listOf("Notes then more text then Notes again")
        val raws = listOf(
            RawDocumentHeading(1, "Notes"),
            RawDocumentHeading(2, "Notes"),
        )
        assertEquals(
            listOf(
                DocumentHeading(1, "Notes", 0),
                DocumentHeading(2, "Notes", 0),
            ),
            resolveHeadings(raws, pages),
        )
    }

    @Test
    fun `headings missing from the pages are dropped but the cursor still advances`() {
        val pages = listOf("intro", "real heading", "outro")
        val raws = listOf(
            RawDocumentHeading(1, "real heading"),
            RawDocumentHeading(2, "does not exist"),
            RawDocumentHeading(3, "outro"),
        )
        assertEquals(
            listOf(
                DocumentHeading(1, "real heading", 1),
                DocumentHeading(3, "outro", 2),
            ),
            resolveHeadings(raws, pages),
        )
    }

    @Test
    fun `empty title is treated as a non-match (caller should already filter these)`() {
        val pages = listOf("body")
        val raws = listOf(RawDocumentHeading(1, ""))
        assertEquals(emptyList<DocumentHeading>(), resolveHeadings(raws, pages))
    }
}
