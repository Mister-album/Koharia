package koharia.cover

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import koharia.connection.providerManagedLibrarySourceIds
import koharia.source.local.LocalFolderSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File
import java.io.IOException
import java.io.InputStream

class CustomCoverStore(
    private val context: Context,
    private val storageManager: StorageManager,
    private val storagePreferences: StoragePreferences,
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
    private val validateImage: (UniFile) -> Boolean = {
        ImageUtil.findImageType { it.openInputStream() } != null
    },
) {
    private val mutex = Mutex()
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    // Read by synchronous Coil keyers; never queries a document provider.
    val cacheKey: String get() = "${storagePreferences.baseStorageDirectory.get()};${changes.value}"

    fun invalidate() = _changes.update { it + 1 }

    fun initialize(scope: CoroutineScope) {
        scope.launch {
            storagePreferences.baseStorageDirectory.changes().collect {
                invalidate()
                migrateLegacyCovers()
            }
        }
    }

    internal suspend fun migrateLegacyCovers() {
        val providerManaged = sourceManager.providerManagedLibrarySourceIds()
            .flatMap { mangaRepository.getMangaBySourceId(it) }
        migrate((mangaRepository.getFavorites() + providerManaged).distinctBy(Manga::id))
    }

    suspend fun open(mangaId: Long, sourceId: Long): InputStream? {
        val manga = mangaRepository.getMangaByIdOrNull(mangaId)?.takeIf { it.source == sourceId } ?: return null
        return open(manga)
    }

    suspend fun open(manga: Manga): InputStream? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val files = storageManager.getCustomCoversDirectory()?.let(::files)
                files?.find(key(manga))?.let {
                    (sourceManager.get(manga.source) as? LocalFolderSource)?.rememberCustomCover(manga.url)
                    return@withLock it.openInputStream()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                logcat(LogPriority.WARN) { "Custom cover storage is unavailable" }
            }
            // Only pre-upgrade originals can exist here. New images are never written privately.
            legacyFiles(manga).firstOrNull { it.isFile }?.inputStream()
        }
    }

    suspend fun exists(manga: Manga): Boolean = open(manga)?.use { true } ?: false

    suspend fun write(manga: Manga, input: InputStream) = withContext(Dispatchers.IO) {
        mutex.withLock {
            (sourceManager.get(manga.source) as? LocalFolderSource)?.rememberCustomCover(manga.url)
            val directory = storageManager.getCustomCoversDirectory(create = true)
                ?: throw IOException("Select a writable storage folder for custom covers")
            files(directory).write(key(manga), input)
            runCatching { deleteLegacy(manga) }
                .onFailure { logcat(LogPriority.WARN) { "Legacy custom cover cleanup deferred" } }
        }
    }

    suspend fun savedCovers(): List<Uri> = withContext(Dispatchers.IO) {
        mutex.withLock {
            storageManager.getCustomCoversDirectory()?.listFiles().orEmpty()
                .filter { it.name?.matches(Regex("[0-9a-f]{64}\\.img")) == true && it.isFile }
                .sortedByDescending { it.lastModified() }
                .map { it.uri }
        }
    }

    suspend fun clearRemovedLocalCovers(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = storageManager.getCustomCoversDirectory() ?: return@withLock 0
            val files = files(directory)
            var deleted = 0
            try {
                for (source in sourceManager.getCatalogueSources().filterIsInstance<LocalFolderSource>()) {
                    for (url in source.customCoverCandidates()) {
                        val key = SharedCoverFiles.key(source.id, url)
                        if (files.find(key) == null) continue
                        if (source.removeCustomCoverIfMissing(url) { files.delete(key) }) deleted++
                    }
                }
            } finally {
                // A failed provider operation may already have removed part of a replacement.
                invalidate()
            }
            deleted
        }
    }

    suspend fun delete(manga: Manga) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // A missing/unreadable root is not evidence that the persistent image is absent.
            val directory = storageManager.getCustomCoversDirectory(create = true)
                ?: throw IOException("Custom cover storage is unavailable")
            deleteLegacy(manga)
            files(directory).delete(key(manga))
        }
    }

    suspend fun migrate(mangas: List<Manga>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            var published = false
            for (manga in mangas) {
                val old = legacyFiles(manga).firstOrNull { it.isFile } ?: continue
                (sourceManager.get(manga.source) as? LocalFolderSource)?.rememberCustomCover(manga.url)
                try {
                    val directory = storageManager.getCustomCoversDirectory(create = true) ?: break
                    val files = files(directory)
                    val existing = files.find(key(manga))
                    if (existing == null) {
                        old.inputStream().use { files.write(key(manga), it) }
                        published = true
                    } else {
                        if (!validImage(existing)) continue
                    }
                    deleteLegacy(manga)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    logcat(LogPriority.WARN) { "Custom cover migration deferred" }
                }
            }
            if (published) invalidate()
        }
    }

    private fun files(directory: UniFile) = SharedCoverFiles(directory, ::validImage)

    private fun validImage(file: UniFile): Boolean = validateImage(file)

    private fun key(manga: Manga) = SharedCoverFiles.key(manga.source, manga.url)

    private fun legacyFiles(manga: Manga): List<File> {
        val name = DiskUtil.hashKeyForDisk(manga.id.toString())
        return listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "covers/custom/$name") },
            File(context.filesDir, "covers/custom/$name"),
        ).distinctBy { it.absolutePath }
    }

    private fun deleteLegacy(manga: Manga) {
        legacyFiles(manga).forEach {
            if (it.exists() && !it.delete()) throw IOException("Cannot remove legacy custom cover")
        }
    }
}
