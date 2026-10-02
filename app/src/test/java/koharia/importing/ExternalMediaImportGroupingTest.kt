package koharia.importing

import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionMediaGrouping
import koharia.connection.ConnectionMediaImportDestination
import koharia.connection.ConnectionMediaImportItem
import koharia.connection.ConnectionMediaType
import koharia.connection.LibraryContentScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalMediaImportGroupingTest {

    @Test
    fun `images can import directly only when selected directory accepts them`() {
        val destination = ConnectionMediaImportDestination(
            id = "folder-root",
            name = "Images",
            mediaType = ConnectionMediaType.COMIC,
            supportedExtensions = setOf("png", "cbz"),
            grouping = ConnectionMediaGrouping.INDIVIDUAL,
            compatibleShelfIds = setOf("folders"),
        )
        val connection = ExternalMediaImportScreenModel.ImportConnection(
            id = 42L,
            name = "Local",
            destinations = listOf(destination),
            shelves = listOf(ConnectionLibraryShelf("folders", "Folders", LibraryContentScope.COMIC)),
        )
        val state = ExternalMediaImportScreenModel.State(
            items = listOf(importItem().copy(displayName = "001.png", extension = "png")),
            connections = listOf(connection),
            selectedConnectionId = 42L,
            selectedDestinationId = destination.id,
            selectedShelfId = "folders",
        )
        assertTrue(state.canConfigureImport)
        assertTrue(state.canImport)
        assertFalse(
            state.copy(
                connections = listOf(
                    connection.copy(destinations = listOf(destination.copy(supportedExtensions = setOf("cbz")))),
                ),
            ).canImport,
        )
    }

    @Test
    fun `individual destination does not require a series name`() {
        val destination = individualDestination()
        val state = ExternalMediaImportScreenModel.State(
            items = listOf(importItem()),
            connections = listOf(
                ExternalMediaImportScreenModel.ImportConnection(
                    id = 42L,
                    name = "Local",
                    destinations = listOf(destination),
                    shelves = listOf(
                        ConnectionLibraryShelf(
                            id = "individual-books",
                            name = "Loose books",
                            contentScope = LibraryContentScope.BOOK,
                        ),
                    ),
                ),
            ),
            selectedConnectionId = 42L,
            selectedDestinationId = destination.id,
            selectedShelfId = "individual-books",
            seriesName = "",
        )

        assertTrue(state.isIndividualDestination)
        assertTrue(state.canImport)
    }

    @Test
    fun `individual destination exposes only compatible shelves`() {
        val destination = individualDestination()
        val state = ExternalMediaImportScreenModel.State(
            items = listOf(importItem()),
            connections = listOf(
                ExternalMediaImportScreenModel.ImportConnection(
                    id = 42L,
                    name = "Local",
                    destinations = listOf(destination),
                    shelves = listOf(
                        ConnectionLibraryShelf(
                            id = "series-books",
                            name = "Series",
                            contentScope = LibraryContentScope.BOOK,
                        ),
                        ConnectionLibraryShelf(
                            id = "individual-books",
                            name = "Loose books",
                            contentScope = LibraryContentScope.BOOK,
                        ),
                    ),
                ),
            ),
            selectedConnectionId = 42L,
            selectedDestinationId = destination.id,
        )

        assertEquals(listOf("individual-books"), state.availableShelves.map { it.id })
    }

    @Test
    fun `bookshelf selection can switch from a series destination to an individual destination`() {
        val seriesDestination = ConnectionMediaImportDestination(
            id = "series-root",
            name = "Series books",
            mediaType = ConnectionMediaType.BOOK,
            supportedExtensions = setOf("epub", "pdf"),
            grouping = ConnectionMediaGrouping.SERIES,
            compatibleShelfIds = setOf("series-books"),
        )
        val individualDestination = individualDestination()
        val state = ExternalMediaImportScreenModel.State(
            items = listOf(importItem()),
            connections = listOf(
                ExternalMediaImportScreenModel.ImportConnection(
                    id = 42L,
                    name = "Local",
                    destinations = listOf(seriesDestination, individualDestination),
                    shelves = listOf(
                        ConnectionLibraryShelf(
                            id = "series-books",
                            name = "Series",
                            contentScope = LibraryContentScope.BOOK,
                        ),
                        ConnectionLibraryShelf(
                            id = "individual-books",
                            name = "Loose books",
                            contentScope = LibraryContentScope.BOOK,
                        ),
                    ),
                ),
            ),
            selectedConnectionId = 42L,
            selectedDestinationId = seriesDestination.id,
            selectedShelfId = "series-books",
        )

        assertEquals(listOf("series-books", "individual-books"), state.selectableShelves.map { it.id })
        assertEquals(individualDestination, state.destinationForShelf("individual-books"))
        assertEquals(
            listOf(individualDestination),
            state.copy(selectedShelfId = "individual-books").selectableDestinations,
        )
    }

    @Test
    fun `initial target uses the current compatible bookshelf`() {
        val seriesDestination = ConnectionMediaImportDestination(
            id = "series-root",
            name = "Series books",
            mediaType = ConnectionMediaType.BOOK,
            supportedExtensions = setOf("epub", "pdf"),
            grouping = ConnectionMediaGrouping.SERIES,
            compatibleShelfIds = setOf("series-books"),
        )
        val individualDestination = individualDestination()
        val connection = ExternalMediaImportScreenModel.ImportConnection(
            id = 42L,
            name = "Local",
            destinations = listOf(seriesDestination, individualDestination),
            shelves = listOf(
                ConnectionLibraryShelf(
                    id = "series-books",
                    name = "Series",
                    contentScope = LibraryContentScope.BOOK,
                ),
                ConnectionLibraryShelf(
                    id = "individual-books",
                    name = "Loose books",
                    contentScope = LibraryContentScope.BOOK,
                ),
            ),
        )

        val target = selectInitialImportTarget(connection, "individual-books", listOf(importItem()))

        assertEquals(individualDestination, target.destination)
        assertEquals("individual-books", target.shelfId)
    }

    @Test
    fun `initial target falls back when the current bookshelf is incompatible`() {
        val destination = individualDestination()
        val connection = ExternalMediaImportScreenModel.ImportConnection(
            id = 42L,
            name = "Local",
            destinations = listOf(destination),
            shelves = listOf(
                ConnectionLibraryShelf(
                    id = "series-books",
                    name = "Series",
                    contentScope = LibraryContentScope.BOOK,
                ),
                ConnectionLibraryShelf(
                    id = "individual-books",
                    name = "Loose books",
                    contentScope = LibraryContentScope.BOOK,
                    isDefault = true,
                ),
            ),
        )

        val target = selectInitialImportTarget(connection, "series-books", listOf(importItem()))

        assertEquals(destination, target.destination)
        assertEquals("individual-books", target.shelfId)
    }

    private fun individualDestination() = ConnectionMediaImportDestination(
        id = "books-root",
        name = "Books",
        mediaType = ConnectionMediaType.BOOK,
        supportedExtensions = setOf("epub", "pdf"),
        grouping = ConnectionMediaGrouping.INDIVIDUAL,
        compatibleShelfIds = setOf("individual-books"),
    )

    private fun importItem() = ConnectionMediaImportItem(
        uri = "content://book",
        displayName = "Book.epub",
        mimeType = "application/epub+zip",
        sizeBytes = 42L,
        extension = "epub",
    )
}
