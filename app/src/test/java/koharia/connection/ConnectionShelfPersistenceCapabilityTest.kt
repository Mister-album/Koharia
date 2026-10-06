package koharia.connection

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every shelf must offer the same persistence capability. A provider that renders the shared filter
 * sheet without a persistence value, or that writes its sort straight to preferences, silently loses
 * the ability to remember filters and sort — so the wiring is asserted from the source itself.
 */
class ConnectionShelfPersistenceCapabilityTest {
    private val appSources = File("src/main/java")

    private fun source(relative: String): String {
        val file = File(appSources, relative)
        assertTrue(file.isFile, "expected $relative to exist")
        return file.readText()
    }

    /** Providers that render the shared sheet and therefore must pass a persistence value. */
    private val sharedSheetHosts = mapOf(
        "Suwayomi" to "koharia/suwayomi/ui/SuwayomiLibraryScreen.kt",
        "Smanga" to "koharia/smanga/ui/SmangaLibraryScreen.kt",
    )

    @Test
    fun `shared sheet hosts pass the persistence value both ways`() {
        sharedSheetHosts.forEach { (name, path) ->
            val text = source(path)
            assertTrue(
                text.contains("persistentFilters = state.persistentFilters"),
                "$name must pass persistentFilters into ConnectionPagedShelfState",
            )
            assertTrue(
                text.contains("persistentFilters = persistent") || text.contains("persistentFilters,"),
                "$name must forward the session's persistence choice to its model",
            )
        }
    }

    /** Providers with their own filter dialog must still render the shared label. */
    private val ownDialogProviders = mapOf(
        "Komga" to "koharia/komga/ui/library/KomgaFilterDialog.kt",
        "Kavita" to "koharia/kavita/ui/KavitaFilterDialog.kt",
        "LANraragi" to "koharia/lanraragi/ui/LanraragiFilterSheet.kt",
        "Local" to "koharia/source/local/LocalLibraryFilterDialog.kt",
    )

    @Test
    fun `every filter dialog renders the shared persistence label`() {
        ownDialogProviders.forEach { (name, path) ->
            assertTrue(
                source(path).contains("MR.strings.shelf_persistent_filters"),
                "$name must render the shared persistence checkbox",
            )
        }
    }

    /** No provider may write its sort to preferences outside the opt-in path. */
    @Test
    fun `sort writes go through the persistence rule`() {
        val stores = mapOf(
            "Suwayomi" to "koharia/source/suwayomi/SuwayomiPreferences.kt",
            "Smanga" to "koharia/source/smanga/SmangaPreferences.kt",
            "Kavita" to "koharia/source/kavita/KavitaPreferences.kt",
        )
        stores.forEach { (name, path) ->
            val text = source(path)
            assertTrue(
                text.contains("ConnectionShelfFilterPersistence"),
                "$name must gate its sort through the shared persistence rule",
            )
        }
    }
}
