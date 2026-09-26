package koharia.cover

import android.content.Context
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.source.local.LocalFolderSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

class CustomCoverStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val context = mockk<Context>()
    private val storageManager = mockk<StorageManager>()
    private val storagePreferences = mockk<StoragePreferences>()
    private val mangaRepository = mockk<MangaRepository>()
    private val sourceManager = mockk<SourceManager>().also { manager ->
        every { manager.get(any()) } returns null
    }
    private val store = CustomCoverStore(context, storageManager, storagePreferences, mangaRepository, sourceManager) {
        true
    }
    private val manga = Manga.create().copy(id = 1, source = 42, url = "/series/one")

    @Test
    fun `restored database ID resolves existing shared image`() = runBlocking {
        val directory = InMemoryCoverDirectory()
        directory.put("${SharedCoverFiles.key(manga.source, manga.url)}.img", "cover")
        every { storageManager.getCustomCoversDirectory() } returns directory.directory
        coEvery { mangaRepository.getMangaByIdOrNull(1) } returns manga
        coEvery { mangaRepository.getMangaByIdOrNull(999) } returns manga.copy(id = 999)

        assertEquals("cover", store.open(1, 42)?.use { it.readBytes().decodeToString() })
        assertEquals("cover", store.open(999, 42)?.use { it.readBytes().decodeToString() })
        verify { context wasNot Called }
    }

    @Test
    fun `source mismatch cannot expose another connection cover`() = runBlocking {
        coEvery { mangaRepository.getMangaByIdOrNull(1) } returns manga

        assertNull(store.open(1, 43))

        verify { storageManager wasNot Called }
    }

    @Test
    fun `unavailable directory rejects deletion before touching legacy files`() {
        every { storageManager.getCustomCoversDirectory(create = true) } returns null

        assertThrows(IOException::class.java) { runBlocking { store.delete(manga) } }

        verify { context wasNot Called }
        assertEquals(0L, store.changes.value)
    }

    @Test
    fun `storage root switch reads only selected root and changes image cache identity`() = runBlocking {
        val first = InMemoryCoverDirectory()
        val second = InMemoryCoverDirectory()
        val filename = "${SharedCoverFiles.key(manga.source, manga.url)}.img"
        first.put(filename, "first root")
        second.put(filename, "second root")
        var selected = first
        var root = "first"
        val preference = mockk<Preference<String>>()
        every { storagePreferences.baseStorageDirectory } returns preference
        every { preference.get() } answers { root }
        every { storageManager.getCustomCoversDirectory() } answers { selected.directory }

        val originalKey = store.cacheKey
        assertEquals("first root", store.open(manga)?.use { it.readBytes().decodeToString() })
        selected = second
        root = "second"

        assertNotEquals(originalKey, store.cacheKey)
        assertEquals("second root", store.open(manga)?.use { it.readBytes().decodeToString() })
        assertEquals("first root", first.read(filename))
    }

    @Test
    fun `cleanup deletes only confirmed removed covers within the owning source`() = runBlocking {
        val directory = InMemoryCoverDirectory()
        val source = mockk<LocalFolderSource>()
        every { source.id } returns 42
        val store = storeFor(source)
        coEvery { source.customCoverCandidates() } returns setOf("removed", "active", "offline", "restored")
        coEvery { source.removeCustomCoverIfMissing(any(), any()) } coAnswers {
            if (firstArg<String>() == "removed") {
                secondArg<() -> Unit>().invoke()
                true
            } else {
                false
            }
        }
        val removed = "${SharedCoverFiles.key(42, "removed")}.img"
        val preserved = listOf("active", "offline", "restored", "unknown").map {
            "${SharedCoverFiles.key(42, it)}.img"
        } + "${SharedCoverFiles.key(43, "removed")}.img"
        (preserved + removed).forEach { directory.put(it, "cover") }
        every { storageManager.getCustomCoversDirectory() } returns directory.directory

        assertEquals(1, store.clearRemovedLocalCovers())
        assertEquals(preserved.toSet(), directory.names())
        assertEquals(1L, store.changes.value)
    }

    @Test
    fun `cleanup invalidates cover cache even when provider deletion fails`() {
        val directory = InMemoryCoverDirectory()
        val source = mockk<LocalFolderSource>()
        every { source.id } returns 42
        val store = storeFor(source)
        coEvery { source.customCoverCandidates() } returns setOf("removed")
        coEvery { source.removeCustomCoverIfMissing(any(), any()) } coAnswers {
            secondArg<() -> Unit>().invoke()
            true
        }
        val key = SharedCoverFiles.key(42, "removed")
        directory.put("$key.img", "current")
        directory.put("$key.previous", "previous")
        directory.failDelete = { it.endsWith(".img") }
        every { storageManager.getCustomCoversDirectory() } returns directory.directory

        assertThrows(IOException::class.java) { runBlocking { store.clearRemovedLocalCovers() } }
        assertEquals(setOf("$key.img"), directory.names())
        assertEquals(1L, store.changes.value)
    }

    @Test
    fun `legacy private cover migrates once and is removed after verified publication`() = runBlocking {
        val directory = InMemoryCoverDirectory()
        every { storageManager.getCustomCoversDirectory(create = true) } returns directory.directory
        every { context.getExternalFilesDir(null) } returns null
        every { context.filesDir } returns temporaryDirectory.toFile()
        val legacy = temporaryDirectory
            .resolve("covers/custom/${DiskUtil.hashKeyForDisk(manga.id.toString())}")
        legacy.parent.createDirectories()
        legacy.writeBytes(PNG_HEADER)

        store.migrate(listOf(manga))

        val published = "${SharedCoverFiles.key(manga.source, manga.url)}.img"
        assertEquals(PNG_HEADER.toList(), directory.readBytes(published)?.toList())
        assertEquals(false, legacy.exists())
        assertEquals(1L, store.changes.value)
    }

    @Test
    fun `nonfavorite provider managed cover is included in startup migration`() = runBlocking {
        val providerManga = manga.copy(favorite = false)
        val source = mockk<CatalogueSource>(moreInterfaces = arrayOf(ConnectionMangaBehaviorAdapter::class))
        every { source.id } returns providerManga.source
        every { (source as ConnectionMangaBehaviorAdapter).mangaBehavior } returns
            ConnectionMangaBehavior(providerManagedLibrary = true)
        val managedSourceManager = object : SourceManager {
            override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
            override val catalogueSources: Flow<List<CatalogueSource>> = MutableStateFlow(listOf(source))
            override fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long): Source = source
            override fun getOnlineSources(): List<HttpSource> = emptyList()
            override fun getCatalogueSources(): List<CatalogueSource> = listOf(source)
            override fun getStubSources(): List<StubSource> = emptyList()
        }
        val managedStore = CustomCoverStore(
            context,
            storageManager,
            storagePreferences,
            mangaRepository,
            managedSourceManager,
        ) { true }
        coEvery { mangaRepository.getFavorites() } returns emptyList()
        coEvery { mangaRepository.getMangaBySourceId(providerManga.source) } returns listOf(providerManga)

        val directory = InMemoryCoverDirectory()
        every { storageManager.getCustomCoversDirectory(create = true) } returns directory.directory
        every { context.getExternalFilesDir(null) } returns null
        every { context.filesDir } returns temporaryDirectory.toFile()
        val legacy = temporaryDirectory.resolve("covers/custom/${DiskUtil.hashKeyForDisk(providerManga.id.toString())}")
        legacy.parent.createDirectories()
        legacy.writeBytes(PNG_HEADER)

        managedStore.migrateLegacyCovers()

        assertEquals(
            PNG_HEADER.toList(),
            directory.readBytes("${SharedCoverFiles.key(providerManga.source, providerManga.url)}.img")?.toList(),
        )
        assertEquals(false, legacy.exists())
    }

    private fun storeFor(source: CatalogueSource): CustomCoverStore {
        val manager = object : SourceManager {
            override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
            override val catalogueSources: Flow<List<CatalogueSource>> = MutableStateFlow(listOf(source))
            override fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long): Source = source
            override fun getOnlineSources(): List<HttpSource> = emptyList()
            override fun getCatalogueSources(): List<CatalogueSource> = listOf(source)
            override fun getStubSources(): List<StubSource> = emptyList()
        }
        return CustomCoverStore(context, storageManager, storagePreferences, mangaRepository, manager) { true }
    }

    private companion object {
        val PNG_HEADER = byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10,
            0, 0, 0, 13, 73, 72, 68, 82,
            0, 0, 0, 1, 0, 0, 0, 1,
            8, 6, 0, 0, 0, 31, 21, -60,
        )
    }
}
