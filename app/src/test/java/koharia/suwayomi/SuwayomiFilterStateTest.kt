package koharia.suwayomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiFilterStateTest {
    private val select = SuwayomiSelectFilter(name = "Genre", values = listOf("Any", "Action"), default = 0)
    private val text = SuwayomiTextFilter(name = "Author", default = "")
    private val check = SuwayomiCheckBoxFilter(name = "Completed", default = false)
    private val tri = SuwayomiTriStateFilter(name = "Lewd", default = "IGNORE")
    private val sort = SuwayomiSortFilter(
        name = "Order",
        values = listOf("Title", "Added"),
        default = SuwayomiSortSelection(index = 0, ascending = true),
    )

    @Test
    fun `changes carry their own position in the filter list`() {
        val states = listOf(
            UnsupportedState(SuwayomiHeaderFilter("Header")),
            SelectState(select),
            TextState(text, text = "abc"),
        )

        val changes = SuwayomiFilterState.changes(states)

        // The header sends nothing and the untouched select keeps the source default, so only the
        // text filter is sent, at its real index.
        assertEquals(listOf(2), changes.map { it.position })
        assertEquals("abc", changes.single().textState)
    }

    @Test
    fun `untouched filters are omitted so the server keeps its own defaults`() {
        val states = SuwayomiFilterState.of(listOf(select, text, check, tri, sort))

        assertTrue(SuwayomiFilterState.changes(states).isEmpty())
    }

    @Test
    fun `each filter type maps to its own GraphQL field`() {
        assertEquals(1, SelectState(select, index = 1).toChanges(0).single().selectState)
        assertEquals("abc", TextState(text, text = "abc").toChanges(0).single().textState)
        assertEquals(true, CheckBoxState(check, checked = true).toChanges(0).single().checkBoxState)
        assertEquals("EXCLUDE", TriStateState(tri, state = "EXCLUDE").toChanges(0).single().triState)
        assertEquals(
            SuwayomiSortSelection(index = 1, ascending = false),
            SortState(sort, index = 1, ascending = false).toChanges(0).single().sortState,
        )
    }

    @Test
    fun `group emits one change per nested child at the group position`() {
        val group = SuwayomiGroupFilter(
            name = "Filters",
            filters = listOf(select, check),
        )
        val state = GroupState(
            group,
            listOf(SelectState(select, index = 1), CheckBoxState(check, checked = true)),
        )

        val changes = state.toChanges(3)

        assertEquals(2, changes.size)
        assertTrue(changes.all { it.position == 3 })
        assertEquals(1, changes[0].groupChange?.selectState)
        assertEquals(true, changes[1].groupChange?.checkBoxState)
    }

    @Test
    fun `diverged reflects defaults so the chip can stay neutral`() {
        assertEquals(false, SelectState(select).diverged)
        assertEquals(true, SelectState(select, index = 1).diverged)
        assertEquals(false, TextState(text).diverged)
        assertEquals(true, TextState(text, text = "x").diverged)
        assertEquals(true, TriStateState(tri, state = "INCLUDE").diverged)
        assertEquals(true, SortState(sort, index = 1).diverged)
        assertEquals(false, SortState(sort).diverged)
    }

    @Test
    fun `unsupported filters never send state`() {
        assertTrue(UnsupportedState(SuwayomiSeparatorFilter("---")).toChanges(0).isEmpty())
        assertNull(SuwayomiFilterState.of(SuwayomiUnknownFilter("Mystery")).toChanges(0).firstOrNull())
    }
}
