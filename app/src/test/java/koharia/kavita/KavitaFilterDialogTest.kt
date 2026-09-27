package koharia.kavita

import koharia.kavita.ui.choiceState
import koharia.kavita.ui.replaceField
import koharia.kavita.ui.toggleChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState

class KavitaFilterDialogTest {
    @Test fun includeExcludeCycleProducesDisjointServerPredicates() {
        val original = KavitaFilter(statements = listOf(KavitaFilterStatement(19, 5, "4")))
        val included = original.toggleChoice(6, "12").toggleChoice(6, "9")
        assertEquals(TriState.ENABLED_IS, included.choiceState(6, "12"))
        val excluded = included.toggleChoice(6, "12")
        assertEquals(
            listOf(KavitaFilterStatement(6, 5, "9"), KavitaFilterStatement(6, 8, "12")),
            excluded.statements.filter { it.field == 6 },
        )
        val removed = excluded.toggleChoice(6, "12")
        assertEquals(TriState.DISABLED, removed.choiceState(6, "12"))
        assertTrue(removed.statements.contains(KavitaFilterStatement(19, 5, "4")))
        assertEquals(listOf(KavitaFilterStatement(19, 5, "4")), original.statements)
    }

    @Test fun editingExistingEqualityKeepsOtherSelectedValuesAndScope() {
        val filter = KavitaFilter(
            statements = listOf(
                KavitaFilterStatement(18, 0, "2"),
                KavitaFilterStatement(18, 9, "3"),
                KavitaFilterStatement(21, 5, "3"),
            ),
        )
        val changed = filter.toggleChoice(18, "4")
        assertEquals(TriState.ENABLED_IS, changed.choiceState(18, "2"))
        assertEquals(TriState.ENABLED_NOT, changed.choiceState(18, "3"))
        assertEquals(TriState.ENABLED_IS, changed.choiceState(18, "4"))
        assertEquals(listOf(KavitaFilterStatement(21, 5, "3")), changed.replaceField(18, emptyList()).statements)
    }

    @Test fun applyingGroupedFiltersPreservesSearchAndLibraryRestrictions() {
        val base = kavitaShelfFilter(listOf(8), "example", "2 desc")
        val draft = KavitaFilter().toggleChoice(6, "10")
        val applied = combineKavitaFilters(base, draft, unrestricted = false)
        assertTrue(applied.statements.containsAll(base.statements))
        assertTrue(applied.statements.contains(KavitaFilterStatement(6, 5, "10")))
        assertEquals(1, applied.combination)
        assertEquals(KavitaSort(2, false), applied.sortOptions)
        assertTrue(KavitaFilter().statements.isEmpty())
    }
}
