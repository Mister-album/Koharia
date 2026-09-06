package koharia.importing

import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionMediaGrouping
import koharia.connection.ConnectionMediaImportDestination
import koharia.connection.ConnectionMediaImportItem
import koharia.connection.ConnectionMediaImportSeries
import koharia.connection.ConnectionMediaType
import koharia.connection.LibraryContentScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportSelectionFlowTest {
    private val directories = listOf(
        destination("series-one", "series", ConnectionMediaGrouping.SERIES),
        destination("series-two", "series", ConnectionMediaGrouping.SERIES),
        destination("single", "single", ConnectionMediaGrouping.INDIVIDUAL),
    )
    private val initial = ExternalMediaImportScreenModel.State(
        items = listOf(ConnectionMediaImportItem("file:///book.epub", "book.epub", null, 1, "epub")),
        connections = listOf(
            ExternalMediaImportScreenModel.ImportConnection(
                1,
                "Local",
                directories,
                listOf(
                    ConnectionLibraryShelf("series", "Series books", LibraryContentScope.BOOK),
                    ConnectionLibraryShelf("single", "Single books", LibraryContentScope.BOOK),
                ),
            ),
        ),
        selectedConnectionId = 1,
        seriesName = "New series",
    )

    @Test
    fun `directory selection follows an explicit bookshelf selection`() {
        assertTrue(initial.selectableDestinations.isEmpty())
        assertSame(initial, initial.selectImportDirectory("single"))
        val shelf = initial.selectImportShelf("single")
        assertEquals("single", shelf.selectedShelfId)
        assertNull(shelf.selectedDestinationId)
        assertFalse(shelf.canImport)
        assertEquals(listOf("single"), shelf.selectableDestinations.map { it.id })
        assertTrue(shelf.selectImportDirectory("single").canImport)
    }

    @Test
    fun `changing library resets directory and selected series`() {
        val state = initial.selectImportShelf("series").selectImportDirectory("series-one")
            .copy(
                seriesTargetMode = ExternalMediaImportScreenModel.SeriesTargetMode.EXISTING,
                selectedExistingSeriesId = "old",
                existingSeries = listOf(ConnectionMediaImportSeries("old", "Old", "series-one", "series")),
            )
        assertTrue(state.canImport)
        val changed = state.selectImportShelf("single")
        assertNull(changed.selectedDestinationId)
        assertNull(changed.selectedExistingSeriesId)
        assertTrue(changed.existingSeries.isEmpty())
        assertFalse(changed.canImport)
        assertSame(changed, changed.selectImportDirectory("series-one"))
    }

    @Test
    fun `directory choice keeps the selected library and invalidates stale series`() {
        val state = initial.selectImportShelf("series").selectImportDirectory("series-one")
            .copy(selectedExistingSeriesId = "old", loadedSeriesDestinationKey = "1:series-one")
        val changed = state.selectImportDirectory("series-two")
        assertEquals("series", changed.selectedShelfId)
        assertEquals("series-two", changed.selectedDestinationId)
        assertNull(changed.selectedExistingSeriesId)
        assertNull(changed.loadedSeriesDestinationKey)
        val wrongDirectorySeries = changed.copy(
            seriesTargetMode = ExternalMediaImportScreenModel.SeriesTargetMode.EXISTING,
            selectedExistingSeriesId = "old",
            existingSeries = listOf(ConnectionMediaImportSeries("old", "Old", "series-one", "series")),
        )
        assertFalse(wrongDirectorySeries.canImport)
    }

    private fun destination(id: String, shelf: String, grouping: ConnectionMediaGrouping) =
        ConnectionMediaImportDestination(
            id,
            id,
            ConnectionMediaType.BOOK,
            setOf("epub"),
            defaultShelfId = shelf,
            grouping = grouping,
            compatibleShelfIds = setOf(shelf),
        )

    @Test
    fun `cancelling a different series search preserves the confirmed selection`() {
        val alpha = ConnectionMediaImportSeries("alpha", "Alpha", "series-one", "series")
        val beta = ConnectionMediaImportSeries("beta", "Beta", "series-one", "series")
        val state = initial.selectImportShelf("series").selectImportDirectory("series-one").copy(
            seriesTargetMode = ExternalMediaImportScreenModel.SeriesTargetMode.EXISTING,
            selectedExistingSeriesId = alpha.id,
            existingSeries = listOf(alpha, beta),
            existingSeriesSearchQuery = "Beta",
            step = ExternalMediaImportScreenModel.Step.IMPORT_CONFIGURATION,
        )
        assertEquals(listOf(beta), state.filteredExistingSeries)
        assertEquals(alpha, state.selectedExistingSeries)
        assertTrue(state.canImport)
        assertNull(state.copy(selectedShelfId = "single").selectedExistingSeries)
        assertNull(state.copy(selectedDestinationId = "series-two").selectedExistingSeries)
    }
}
