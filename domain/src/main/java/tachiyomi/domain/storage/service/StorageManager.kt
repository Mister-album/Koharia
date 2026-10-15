package tachiyomi.domain.storage.service

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

class StorageManager(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory.get())

    private val downloadsDirectoryLock = Any()

    @Volatile
    private var cachedDownloadsDirectory: CachedDownloadsDirectory? = null

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                    parent.createDirectory(DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return baseDir?.createDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        return synchronized(downloadsDirectoryLock) {
            val root = baseDir
            val directory = root?.createDirectory(DOWNLOADS_PATH)
            cachedDownloadsDirectory = if (root != null && directory != null && root.uri.scheme != "file") {
                CachedDownloadsDirectory(root, directory, currentTimeMillis())
            } else {
                null
            }
            directory
        }
    }

    fun getDownloadsDirectoryForRead(): UniFile? {
        cachedDownloadsDirectory?.takeIf { it.isValid(baseDir, currentTimeMillis()) }?.let { return it.directory }
        return synchronized(downloadsDirectoryLock) {
            cachedDownloadsDirectory?.takeIf { it.isValid(baseDir, currentTimeMillis()) }?.directory
                ?: getDownloadsDirectory()
        }
    }

    fun invalidateDownloadsDirectoryCache() {
        synchronized(downloadsDirectoryLock) {
            cachedDownloadsDirectory = null
        }
    }

    fun getLocalSourceDirectory(): UniFile? {
        return baseDir?.createDirectory(LOCAL_SOURCE_PATH)
    }

    fun getCustomCoversDirectory(create: Boolean = false): UniFile? {
        // Resolve the current URI here as directory changes are otherwise processed asynchronously.
        val root = getBaseDir(storagePreferences.baseStorageDirectory.get()) ?: return null
        val directory = root.findFile(CUSTOM_COVERS_PATH)
            ?: if (create) root.createDirectory(CUSTOM_COVERS_PATH) else null
        if (create) DiskUtil.createNoMediaFile(directory, context)
        return directory?.takeIf { it.isDirectory }
    }

    private data class CachedDownloadsDirectory(val root: UniFile, val directory: UniFile, val resolvedAt: Long) {
        fun isValid(currentRoot: UniFile?, now: Long): Boolean =
            root === currentRoot && now - resolvedAt in 0 until 10_000L
    }
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"
private const val CUSTOM_COVERS_PATH = "covers"
