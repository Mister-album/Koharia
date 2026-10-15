package koharia.connection

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

class ConnectionContentScopeLifecycleTest {
    @Test
    fun `replacing the selected instance switches subscriptions and ignores old emissions`() = runTest {
        val preferences = ConnectionPreferences(MutableTestPreferenceStore(), Json)
        preferences.activeConnectionId.set(7L)
        val previousScopes = MutableStateFlow(setOf(LibraryContentScope.COMIC))
        val nextScopes = MutableStateFlow(setOf(LibraryContentScope.BOOK))
        val previous = mockk<KavitaSource> {
            every { id } returns 7L
            every { contentScopesChanges() } returns previousScopes
        }
        val next = mockk<KavitaSource> {
            every { id } returns 7L
            every { contentScopesChanges() } returns nextScopes
        }
        val sources = MutableStateFlow<List<CatalogueSource>>(listOf(previous))
        val manager = object : SourceManager by mockk<SourceManager>() {
            override val catalogueSources = sources
        }
        val observed = mutableListOf<Set<LibraryContentScope>>()
        backgroundScope.launch {
            ConnectionContentScopeController(preferences, manager).activeScopesChanges().collect { observed += it }
        }
        runCurrent()
        sources.value = listOf(next)
        runCurrent()
        previousScopes.value = setOf(LibraryContentScope.ALL)
        runCurrent()
        assertEquals(listOf(setOf(LibraryContentScope.COMIC), setOf(LibraryContentScope.BOOK)), observed)
        sources.value = emptyList()
        runCurrent()
        assertEquals(setOf(LibraryContentScope.ALL), observed.last())
    }

    @Test
    fun `selected connection scopes update when its source registers late`() = runTest {
        val preferences = ConnectionPreferences(MutableTestPreferenceStore(), Json)
        preferences.activeConnectionId.set(7L)
        val sources = MutableStateFlow<List<CatalogueSource>>(emptyList())
        val manager = object : SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources = sources
            override fun get(sourceKey: Long) = sources.value.firstOrNull { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long) = requireNotNull(get(sourceKey))
            override fun getOnlineSources() = sources.value.filterIsInstance<HttpSource>()
            override fun getCatalogueSources() = sources.value
            override fun getStubSources() = emptyList<StubSource>()
        }
        val controller = ConnectionContentScopeController(preferences, manager)
        val observed = mutableListOf<Set<LibraryContentScope>>()
        backgroundScope.launch { controller.activeScopesChanges().collect { observed += it } }
        runCurrent()
        assertEquals(listOf(setOf(LibraryContentScope.ALL)), observed)

        val scopes = setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK)
        val source = mockk<KavitaSource>()
        every { source.id } returns 7L
        every { source.availableContentScopes() } returns scopes
        every { source.contentScopesChanges() } returns MutableStateFlow(scopes)
        sources.value = listOf(source)
        runCurrent()

        assertEquals(scopes, controller.activeScopes())
        assertEquals(scopes, observed.last(), "The active selection did not change, but its source became available")
    }
}
