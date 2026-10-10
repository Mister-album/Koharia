package koharia.connection

import koharia.connection.ConnectionOrganizationPage.COLLECTIONS
import koharia.connection.ConnectionOrganizationPage.READ_LISTS
import koharia.connection.ui.CollectionsTab
import koharia.connection.ui.OrganizationsTab
import koharia.connection.ui.ReadListsTab
import koharia.connection.ui.organizationNavigationTabs
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.ScopedPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

class ConnectionOrganizationNavigationTest {
    @Test
    fun `visibility and merge settings select the available destinations`() {
        val supported = setOf(COLLECTIONS, READ_LISTS)
        val cases = listOf(
            Triple(true, true, false) to listOf(CollectionsTab, ReadListsTab),
            Triple(true, true, true) to listOf(OrganizationsTab),
            Triple(true, false, false) to listOf(CollectionsTab),
            Triple(true, false, true) to listOf(CollectionsTab),
            Triple(false, true, false) to listOf(ReadListsTab),
            Triple(false, true, true) to listOf(ReadListsTab),
            Triple(false, false, false) to emptyList(),
            Triple(false, false, true) to emptyList(),
        )
        cases.forEach { (settings, expected) ->
            val navigation = ConnectionOrganizationNavigation.create(
                supported,
                settings.first,
                settings.second,
                settings.third,
            )
            assertEquals(expected, organizationNavigationTabs(navigation), settings.toString())
        }
    }

    @Test
    fun `unsupported destinations stay absent regardless of user settings`() {
        assertEquals(
            emptyList<ConnectionOrganizationPage>(),
            ConnectionOrganizationNavigation.create(emptySet(), true, true, true).pages,
        )
        listOf(COLLECTIONS, READ_LISTS).forEach { page ->
            val navigation = ConnectionOrganizationNavigation.create(setOf(page), true, true, true)
            assertEquals(listOf(page), navigation.pages)
            assertFalse(navigation.combined)
        }
    }

    @Test
    fun `navigation preferences keep defaults and persist in the shared scope across connections`() {
        val store = MutableTestPreferenceStore()
        val scoped = ScopedPreferenceStore(store, SharedAppPreferenceScope)
        val preferences = LibraryPreferences(scoped)
        val connections = ConnectionPreferences(store, Json)
        assertTrue(preferences.showCollections.get())
        assertTrue(preferences.showReadLists.get())
        assertFalse(preferences.mergeOrganizationPages.get())
        connections.activeConnectionId.set(42)
        preferences.showCollections.set(false)
        preferences.mergeOrganizationPages.set(true)
        connections.activeConnectionId.set(91)
        val restored = LibraryPreferences(ScopedPreferenceStore(store, SharedAppPreferenceScope))
        assertFalse(restored.showCollections.get())
        assertTrue(restored.showReadLists.get())
        assertTrue(restored.mergeOrganizationPages.get())
        assertFalse(store.getBoolean("connection_shared::show_collections", true).get())
        assertTrue(store.getBoolean("connection_shared::merge_organization_pages", false).get())
        assertTrue(store.getBoolean("show_collections", true).get())
    }
}
