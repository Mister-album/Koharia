package koharia.connection

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import koharia.source.komga.KomgaConnectionMigration
import koharia.source.komga.KomgaConnectionProvider
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

class ConnectionArchitectureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy Komga state migrates without changing old keys`() {
        val legacyProfile = """{"id":42,"name":"Home"}"""
        val store = MutableTestPreferenceStore(
            mutableMapOf(
                KomgaConnectionMigration.PREF_LEGACY_PROFILES to setOf(legacyProfile),
                KomgaConnectionMigration.PREF_LEGACY_ACTIVE_SERVER_ID to 42L,
                KomgaConnectionMigration.PREF_LEGACY_LOCAL_CONFIG_MODE to "Separate",
                KomgaConnectionMigration.PREF_LEGACY_HAS_INITIALIZED_PROFILES to true,
                "komga_local_shared::reader_key" to "shared-value",
                "komga_local_server_42::reader_key" to "server-value",
            ),
        )
        val preferences = ConnectionPreferences(store, json)

        migration(store, preferences).migrate()

        assertEquals(
            listOf(LibraryConnectionProfile(42L, KomgaConnectionProvider.ID, "Home")),
            preferences.getProfiles(),
        )
        assertEquals(42L, preferences.activeConnectionId.get())
        assertEquals(ConnectionConfigMode.Separate, preferences.configMode.get())
        assertEquals(KomgaConnectionProvider.ID, preferences.providerIdForSource(42L))
        assertEquals("shared-value", store.values["connection_shared::reader_key"])
        assertEquals("server-value", store.values["connection_42::reader_key"])
        assertEquals(setOf(legacyProfile), store.values[KomgaConnectionMigration.PREF_LEGACY_PROFILES])
        assertTrue(store.values[KomgaConnectionMigration.PREF_MIGRATION_COMPLETED] as Boolean)
    }

    @Test
    fun `existing generic values win and completed migration is idempotent`() {
        val genericProfile = LibraryConnectionProfile(7L, KomgaConnectionProvider.ID, "Current")
        val store = MutableTestPreferenceStore(
            mutableMapOf(
                ConnectionPreferences.PREF_PROFILES to setOf(json.encodeToString(genericProfile)),
                ConnectionPreferences.PREF_ACTIVE_CONNECTION_ID to 7L,
                "connection_shared::reader_key" to "current-value",
                KomgaConnectionMigration.PREF_LEGACY_PROFILES to setOf("""{"id":42,"name":"Old"}"""),
                KomgaConnectionMigration.PREF_LEGACY_ACTIVE_SERVER_ID to 42L,
                "komga_local_shared::reader_key" to "old-value",
            ),
        )
        val preferences = ConnectionPreferences(store, json)
        val migration = migration(store, preferences)

        migration.migrate()
        store.getString("komga_local_shared::reader_key").set("changed-after-migration")
        migration.migrate()

        assertEquals(listOf(genericProfile), preferences.getProfiles())
        assertEquals(7L, preferences.activeConnectionId.get())
        assertEquals("current-value", store.values["connection_shared::reader_key"])
        assertNull(preferences.providerIdForSource(42L))
    }

    @Test
    fun `connection ids stay reserved after profile removal`() {
        val store = MutableTestPreferenceStore()
        val preferences = ConnectionPreferences(store, json)
        val profile = LibraryConnectionProfile(12L, KomgaConnectionProvider.ID, "Removed")

        preferences.setProfiles(listOf(profile))
        preferences.setProfiles(emptyList())

        assertTrue(preferences.isKnownConnectionId(12L))
        assertEquals(KomgaConnectionProvider.ID, preferences.providerIdForSource(12L))
        assertFalse(preferences.allocateConnectionId() in setOf(0L, 12L))
    }

    @Test
    fun `registry retains unknown providers as unavailable profiles`() {
        val registry = ConnectionRegistry(listOf(KomgaConnectionProvider()))
        val unknown = LibraryConnectionProfile(99L, "future-provider", "Unavailable")

        assertNull(registry.createSource(unknown))
        assertEquals(listOf(KomgaConnectionProvider.ID), registry.availableProviders().map { it.id })
    }

    @Test
    fun `registered provider creates its own browse screen`() {
        val profile = LibraryConnectionProfile(24L, "test-provider", "Home")
        val source = TestConnectionSource(profile)
        val provider = object : ConnectionProvider {
            override val id = profile.providerId
            override val displayName = "Test"
            override fun createSource(profile: LibraryConnectionProfile) = source
        }

        val createdSource = ConnectionRegistry(listOf(provider)).createSource(profile)
        val browseScreen = (createdSource as ConnectionBrowseAdapter).createBrowseScreen(
            scope = LibraryContentScope.BOOK,
            showNavigationUp = false,
        )

        assertSame(source, createdSource)
        assertFalse(createdSource is ConnectionPageAdapter)
        assertEquals(profile.id, browseScreen.sourceId)
        assertEquals(LibraryContentScope.BOOK, (browseScreen as TestBrowseScreen).scope)
        assertFalse(browseScreen.showNavigationUp)
    }

    @Test
    fun `shared and separate connection scopes remain provider neutral`() {
        assertEquals(
            "connection_shared::",
            ConnectionPreferenceScopes.forConnection(ConnectionConfigMode.Shared, 42L).prefix,
        )
        assertTrue(
            ConnectionPreferenceScopes.forConnection(ConnectionConfigMode.Shared, 42L).allowLegacyFallback,
        )
        assertEquals(
            "connection_42::",
            ConnectionPreferenceScopes.forConnection(ConnectionConfigMode.Separate, 42L).prefix,
        )
        assertFalse(
            ConnectionPreferenceScopes.forConnection(ConnectionConfigMode.Separate, 42L).allowLegacyFallback,
        )
        assertEquals(
            "connection_shared::",
            ConnectionPreferenceScopes.forConnection(
                ConnectionConfigMode.Separate,
                NO_ACTIVE_CONNECTION,
            ).prefix,
        )
    }

    @Test
    fun `profile manager creates registered provider profile and initializes its scope`() {
        val store = MutableTestPreferenceStore()
        val preferences = ConnectionPreferences(store, json)
        val provider = mockk<ConnectionProvider> {
            every { id } returns "test-provider"
            every { displayName } returns "Test"
        }
        val configManager = mockk<ConnectionConfigManager> {
            every { initializeScopeForNewConnection(any()) } just runs
        }
        val manager = ConnectionProfileManager(
            preferences = preferences,
            registry = ConnectionRegistry(listOf(provider)),
            configManager = configManager,
        )

        val profile = manager.add(providerId = provider.id, name = "  Home  ")

        assertEquals("test-provider", profile.providerId)
        assertEquals("Home", profile.name)
        assertEquals(listOf(profile), preferences.getProfiles())
        assertEquals("test-provider", preferences.providerIdForSource(profile.id))
        verify(exactly = 1) { configManager.initializeScopeForNewConnection(profile.id) }
    }

    @Test
    fun `content scopes follow provider changes and active connection switches`() = runTest {
        val store = MutableTestPreferenceStore()
        val preferences = ConnectionPreferences(store, json)
        val comicScopes = MutableStateFlow(setOf(LibraryContentScope.COMIC))
        val bookScopes = MutableStateFlow(setOf(LibraryContentScope.BOOK))
        val comicSource = TestConnectionSource(
            LibraryConnectionProfile(1L, "test-provider", "Comics"),
            comicScopes,
        )
        val bookSource = TestConnectionSource(
            LibraryConnectionProfile(2L, "test-provider", "Books"),
            bookScopes,
        )
        val sourceManager = mockk<SourceManager> {
            every { get(1L) } returns comicSource
            every { get(2L) } returns bookSource
        }
        preferences.activeConnectionId.set(1L)
        val controller = ConnectionContentScopeController(preferences, sourceManager)

        val emissions = async { controller.activeScopesChanges().take(3).toList() }
        runCurrent()
        comicScopes.value = setOf(LibraryContentScope.ALL)
        runCurrent()
        preferences.activeConnectionId.set(2L)

        assertEquals(
            listOf(
                setOf(LibraryContentScope.COMIC),
                setOf(LibraryContentScope.ALL),
                setOf(LibraryContentScope.BOOK),
            ),
            emissions.await(),
        )
        assertEquals(setOf(LibraryContentScope.BOOK), controller.activeScopes())
    }

    @Test
    fun `provider capabilities dispatch without registry type knowledge`() {
        val profile = LibraryConnectionProfile(25L, "capability-provider", "Remote")
        val source = CapabilityConnectionSource(profile)
        val provider = object : ConnectionProvider {
            override val id = profile.providerId
            override val displayName = "Capability provider"
            override fun createSource(profile: LibraryConnectionProfile) = source
        }

        val createdSource = ConnectionRegistry(listOf(provider)).createSource(profile)

        assertSame(source, createdSource as ConnectionMangaProgressAdapter)
        assertSame(source, createdSource as ConnectionHistorySyncAdapter)
        assertSame(source, createdSource as ConnectionPageProgressAdapter)
        assertSame(source, createdSource as ConnectionDownloadStorageAdapter)
    }

    @Test
    fun `connection capabilities stay independently opt in`() {
        val profile = LibraryConnectionProfile(26L, "history-provider", "History")
        val source: ConnectionSource = object : ConnectionSource, ConnectionHistorySyncAdapter {
            override val connectionProfile = profile
            override val id = profile.id
            override val name = profile.name

            override suspend fun syncConnectionHistory() = Unit
        }

        assertTrue(source is ConnectionHistorySyncAdapter)
        assertFalse(source is ConnectionViewerSettingsAdapter)
        assertFalse(source is ConnectionMangaProgressAdapter)
        assertFalse(source is ConnectionPageProgressAdapter)
        assertFalse(source is ConnectionBrowseAdapter)
        assertFalse(source is ConnectionPageAdapter)
    }

    @Test
    fun `provider management extensions compose without registry special cases`() {
        val preparedModes = mutableListOf<ConnectionConfigMode>()
        val provider = object : ConnectionProvider, ConnectionManagementAdapter, ConnectionConfigModeInterceptor {
            override val id = "managed-provider"
            override val displayName = "Managed"

            override fun createSource(profile: LibraryConnectionProfile) = TestConnectionSource(profile)

            @Composable
            override fun ConnectionManagementPreferences() = Unit

            override fun warningForConfigMode(mode: ConnectionConfigMode): ConnectionConfigModeWarning? {
                return if (mode == ConnectionConfigMode.Shared) {
                    ConnectionConfigModeWarning(MR.strings.action_ok, MR.strings.action_cancel)
                } else {
                    null
                }
            }

            override fun prepareConfigModeChange(mode: ConnectionConfigMode) {
                preparedModes += mode
            }
        }
        val registered = ConnectionRegistry(listOf(provider)).availableProviders()
        val managementAdapters = registered.filterIsInstance<ConnectionManagementAdapter>()
        val interceptors = registered.filterIsInstance<ConnectionConfigModeInterceptor>()

        assertEquals(1, managementAdapters.size)
        assertEquals(1, interceptors.size)
        assertNull(interceptors.single().warningForConfigMode(ConnectionConfigMode.Separate))
        assertEquals(
            MR.strings.action_ok,
            interceptors.single().warningForConfigMode(ConnectionConfigMode.Shared)?.title,
        )
        interceptors.forEach { it.prepareConfigModeChange(ConnectionConfigMode.Shared) }
        assertEquals(listOf(ConnectionConfigMode.Shared), preparedModes)
    }

    @Test
    fun `backup restore policy keeps generic profiles authoritative and recovers legacy inventory`() {
        val legacyAppKey = "legacy_profiles"
        val legacySourceKey = "source_legacy"

        val genericPolicy = ConnectionBackupRestorePolicy(
            genericKeyPrefix = "connection_",
            legacyAppKeys = setOf(legacyAppKey),
            legacySourceKeys = setOf(legacySourceKey),
        )
        assertFalse(genericPolicy.recordAppRestore(listOf("connection_profiles", legacyAppKey)))
        assertFalse(
            genericPolicy.shouldForceLegacyInventoryAfterSourceRestore(
                sourceKeys = listOf(legacySourceKey),
                hasConnectionProfiles = true,
            ),
        )

        val legacyPolicy = ConnectionBackupRestorePolicy(
            genericKeyPrefix = "connection_",
            legacyAppKeys = setOf(legacyAppKey),
            legacySourceKeys = setOf(legacySourceKey),
        )
        assertTrue(legacyPolicy.recordAppRestore(listOf(legacyAppKey)))
        assertTrue(
            legacyPolicy.shouldForceLegacyInventoryAfterSourceRestore(
                sourceKeys = listOf(legacySourceKey),
                hasConnectionProfiles = true,
            ),
        )

        val sourceOnlyPolicy = ConnectionBackupRestorePolicy(
            genericKeyPrefix = "connection_",
            legacyAppKeys = setOf(legacyAppKey),
            legacySourceKeys = setOf(legacySourceKey),
        )
        assertFalse(sourceOnlyPolicy.recordAppRestore(emptyList()))
        assertTrue(
            sourceOnlyPolicy.shouldForceLegacyInventoryAfterSourceRestore(
                sourceKeys = listOf(legacySourceKey),
                hasConnectionProfiles = false,
            ),
        )
    }

    @Test
    fun `manga behavior stays provider opt in while Komga preserves its policies`() {
        val defaultBehavior = ConnectionMangaBehavior.Default
        assertFalse(defaultBehavior.usesCacheTerminology)
        assertFalse(defaultBehavior.supportsChapterCoverGrid)
        assertTrue(defaultBehavior.allowsLocalLibraryManagement)
        assertTrue(defaultBehavior.allowsFetchIntervalManagement)
        assertTrue(defaultBehavior.showSourceName)
        assertNull(defaultBehavior.detailsRefreshIntervalMillis)

        val behavior = KomgaSource.MANGA_BEHAVIOR

        assertTrue(behavior.usesCacheTerminology)
        assertTrue(behavior.supportsChapterCoverGrid)
        assertFalse(behavior.allowsLocalLibraryManagement)
        assertFalse(behavior.allowsFetchIntervalManagement)
        assertFalse(behavior.showSourceName)
        assertEquals(5 * 60 * 1_000L, behavior.detailsRefreshIntervalMillis)
    }

    @Test
    fun `download storage lifecycle defaults are safe no ops`() = runTest {
        val adapter = object : ConnectionDownloadStorageAdapter {
            override val usesSharedDownloadStorage = false
            override fun downloadDirectoryName() = "Provider"
            override fun downloadDirectoryNames() = listOf(downloadDirectoryName())
            override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())
            override fun legacyDownloadDirectoryNames() = emptyList<String>()
        }
        val file = mockk<UniFile>()

        adapter.deleteIndexedFile(file)
        adapter.deleteIndexedPathPrefix("Provider/Book")
        adapter.deleteIndexedManga(1L)
        adapter.updateIndexedFilePath("Provider/Book/Old.cbz", file)
        adapter.updateIndexedPathPrefix("Provider/Old", "Provider/New")

        assertNull(adapter.indexedRelativePath(file))
    }

    @Test
    fun `chapter metadata is provider neutral`() {
        val memo = buildJsonObject {
            put("sizeBytes", 2048L)
            put("pagesCount", 12)
            put("embeddedFileSize", "2 MB")
            put("fileHash", "abc123")
        }

        assertEquals(2048L, ConnectionChapterMetadata.sizeBytes(memo))
        assertEquals(12, ConnectionChapterMetadata.pagesCount(memo))
        assertEquals("hash:abc123", ConnectionChapterMetadata.publicationVersion(memo))
        assertEquals(
            "Chapter 1",
            ConnectionChapterMetadata.removeTrailingEmbeddedFileSize("Chapter 1 (2 MB)", memo),
        )
    }

    private fun migration(
        store: PreferenceStore,
        preferences: ConnectionPreferences,
    ): KomgaConnectionMigration {
        val sourcePreferences = mockk<SharedPreferences> {
            every { contains(any()) } returns false
        }
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns sourcePreferences
        }
        return KomgaConnectionMigration(context, store, preferences, json)
    }
}

private class TestConnectionSource(
    override val connectionProfile: LibraryConnectionProfile,
    private val scopes: MutableStateFlow<Set<LibraryContentScope>> = MutableStateFlow(
        setOf(LibraryContentScope.ALL),
    ),
) : ConnectionSource, ConnectionBrowseAdapter {
    override val id: Long = connectionProfile.id
    override val name: String = connectionProfile.name

    override fun availableContentScopes(): Set<LibraryContentScope> = scopes.value

    override fun contentScopesChanges(): Flow<Set<LibraryContentScope>> = scopes

    override fun createBrowseScreen(
        scope: LibraryContentScope,
        listingQuery: String?,
        showNavigationUp: Boolean,
    ): ConnectionBrowseScreen = TestBrowseScreen(id, scope, showNavigationUp)
}

private class CapabilityConnectionSource(
    override val connectionProfile: LibraryConnectionProfile,
) :
    ConnectionSource,
    ConnectionMangaProgressAdapter,
    ConnectionHistorySyncAdapter,
    ConnectionPageProgressAdapter,
    ConnectionDownloadStorageAdapter {
    override val id: Long = connectionProfile.id
    override val name: String = connectionProfile.name
    override val usesSharedDownloadStorage = false

    override suspend fun syncMangaProgress(manga: Manga) = Unit

    override suspend fun syncConnectionHistory() = Unit

    override suspend fun pullPageProgress(
        chapterUrl: String,
        chapterMemo: JsonObject,
    ): ConnectionPageProgressSnapshot? = null

    override suspend fun pushPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int) = Unit

    override fun downloadDirectoryName() = "Capability provider"

    override fun downloadDirectoryNames() = listOf(downloadDirectoryName())

    override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())

    override fun legacyDownloadDirectoryNames() = emptyList<String>()
}

private data class TestBrowseScreen(
    override val sourceId: Long,
    val scope: LibraryContentScope,
    val showNavigationUp: Boolean,
) : ConnectionBrowseScreen {
    @Composable
    override fun Content() = Unit

    override suspend fun search(query: String) = Unit

    override suspend fun searchGenre(name: String) = Unit

    override suspend fun refresh() = Unit
}

private class MutableTestPreferenceStore(
    val values: MutableMap<String, Any> = mutableMapOf(),
) : PreferenceStore {
    override fun getString(key: String, defaultValue: String): Preference<String> =
        PrimitivePreference(values, key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        PrimitivePreference(values, key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        PrimitivePreference(values, key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        PrimitivePreference(values, key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        PrimitivePreference(values, key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        PrimitivePreference(values, key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = SerializedPreference(values, key, defaultValue, serializer, deserializer)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = SerializedPreference(values, key, defaultValue, serializer, deserializer)

    override fun getAll(): Map<String, *> = values.toMap()
}

private class PrimitivePreference<T>(
    private val values: MutableMap<String, Any>,
    private val key: String,
    private val defaultValue: T,
) : Preference<T> {
    private val state = MutableStateFlow(get())

    override fun key(): String = key

    @Suppress("UNCHECKED_CAST")
    override fun get(): T = values[key] as? T ?: defaultValue

    override fun isSet(): Boolean = key in values

    override fun delete() {
        values.remove(key)
    }

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> = state

    override fun stateIn(scope: CoroutineScope) = changes().stateIn(scope, SharingStarted.Eagerly, get())

    override fun set(value: T) {
        values[key] = value as Any
        state.value = value
    }
}

private class SerializedPreference<T, R : Any>(
    private val values: MutableMap<String, Any>,
    private val key: String,
    private val defaultValue: T,
    private val serializer: (T) -> R,
    private val deserializer: (R) -> T,
) : Preference<T> {
    private val state = MutableStateFlow(get())

    override fun key(): String = key

    @Suppress("UNCHECKED_CAST")
    override fun get(): T = (values[key] as? R)?.let(deserializer) ?: defaultValue

    override fun isSet(): Boolean = key in values

    override fun delete() {
        values.remove(key)
    }

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> = state

    override fun stateIn(scope: CoroutineScope) = changes().stateIn(scope, SharingStarted.Eagerly, get())

    override fun set(value: T) {
        values[key] = serializer(value)
        state.value = value
    }
}
