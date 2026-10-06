package koharia.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.RemoteStorageFile
import eu.kanade.tachiyomi.network.NetworkHelper
import koharia.connection.SharedAppPreferences
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class NetworkStorageRuntime private constructor(val context: Context, val connectionId: Long) {
    @Volatile private var closed = false
    private val preferences = NetworkStoragePreferences(connectionId)
    val config = preferences.configuration
    private val username = preferences.username
    private val password = preferences.password
    val account = preferences.account
    val session = StorageSession(connectionId, account, config.rootIdentity) {
        !closed && preferences.configuration == config && preferences.username == username &&
            preferences.password == password
    }
    private val routedBackend: LibraryStorageBackend = StorageEndpointRouter(
        backend(config, config.address, username, password),
        config.internalAddress.takeIf { config.persistentIdentity && config.verifiedInternal && it.isNotBlank() }
            ?.let { backend(config, it, username, password) },
        network = {
            val monitor = context.getSystemService(ConnectivityManager::class.java)
            monitor.activeNetwork?.takeIf {
                monitor.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ==
                    true
            }
        },
        verify = {
            if (config.needsValidation) throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            if (!config.persistentIdentity) {
                if (!it.stat("").directory) throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            } else if (StorageIdentity.read(it) !=
                config.rootIdentity
            ) {
                throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            }
        },
    )
    val backend = SessionStorageBackend(
        if (config.persistentIdentity) routedBackend else ReadOnlyStorageBackend(routedBackend),
        session,
    )
    val records = StorageRecordStore(session, Injekt.get<LibraryStorageRepository>(), Injekt.get<Json>())
    val snapshot = StorageSnapshot(backend, records)
    val identities = StorageResourceIdentities(backend, records)
    val cache = StorageBlockCache(
        File(context.filesDir, "library-storage-cache"),
        session,
        backend,
        resourceIdentity = { entry -> runBlocking(Dispatchers.IO) { identities.identity(entry) } },
    ) {
        Injekt.get<SharedAppPreferences>().store().getInt("network_storage_cache_mb", 512).get().coerceIn(128, 4096) *
            1024L *
            1024
    }
    val mutations = StorageMutations(backend, records, identities, context.cacheDir)
    val progress = StorageProgress(backend, records, StorageProgress.deviceId(context), config.persistentIdentity)
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val progressCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (config.needsValidation || !config.persistentIdentity) return
            progressScope.launch {
                try {
                    progress.flush()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Pending records remain durable and visible in connection settings.
                }
            }
        }
    }
    private val observingNetwork = runCatching {
        connectivity.registerDefaultNetworkCallback(progressCallback)
    }.isSuccess

    fun uri(path: String): Uri = DocumentsContract.buildDocumentUri(
        authority(context),
        "$connectionId|$account|${StoragePath.normalize(path)}",
    )
    fun file(path: String): RemoteStorageFile = RemoteStorageFile(this, StoragePath.normalize(path))
    fun cached(path: String): StorageEntry? = runBlocking(Dispatchers.IO) { snapshot.cachedEntry(path) }
    fun cachedChildren(path: String): List<StorageEntry>? = runBlocking(Dispatchers.IO) {
        records.get<StorageDirectorySnapshot>("directories", StoragePath.normalize(path))?.children
    }
    suspend fun refreshParent(path: String) {
        snapshot.directory(StoragePath.parent(path), true)
    }
    private fun close() {
        closed = true
        progressScope.cancel()
        if (observingNetwork) runCatching { connectivity.unregisterNetworkCallback(progressCallback) }
        backend.close()
    }

    companion object {
        private val sessions = ConcurrentHashMap<Long, NetworkStorageRuntime>()
        fun authority(context: Context) = "${context.packageName}.library-storage"
        fun isRemote(context: Context, uri: Uri) = uri.authority == authority(context)
        fun get(
            context: Context,
            connectionId: Long,
        ): NetworkStorageRuntime = sessions.compute(connectionId) { _, previous ->
            if (previous != null && runCatching { previous.session.checkActive() }.isSuccess) {
                previous
            } else {
                previous?.close()
                NetworkStorageRuntime(context.applicationContext, connectionId)
            }
        }!!
        fun fromUri(context: Context, uri: Uri): RemoteStorageFile? {
            if (!isRemote(context, uri)) return null
            return fromDocumentId(context, DocumentsContract.getDocumentId(uri))
        }
        fun fromDocumentId(context: Context, id: String): RemoteStorageFile {
            val parts = id.split('|', limit = 3)
            require(parts.size == 3)
            val runtime = get(context, parts[0].toLong())
            check(runtime.account == parts[1]) { "Document belongs to another account" }
            return runtime.file(parts[2])
        }
        fun backend(
            config: NetworkStorageConfiguration,
            address: String,
            username: String,
            password: String,
        ): LibraryStorageBackend =
            when (config.mode) {
                LibraryStorageMode.WEBDAV -> WebDavStorageBackend(
                    Injekt.get<NetworkHelper>().nonCloudflareClient,
                    address,
                    username,
                    password,
                )
                LibraryStorageMode.SMB -> SmbStorageBackend(address, username, password, config.domain)
                LibraryStorageMode.LOCAL -> error("Not a network storage connection")
            }
        fun invalidate(connectionId: Long) {
            sessions.remove(connectionId)?.close()
        }
        fun removeCachedContent(context: Context, connectionId: Long) {
            val preferences = NetworkStoragePreferences(connectionId)
            val root = File(context.filesDir, "library-storage-cache").canonicalFile
            val namespace =
                storageDigest("$connectionId/${preferences.account}/${preferences.configuration.rootIdentity}")
            val owned = File(root, namespace).canonicalFile
            check(owned.parentFile == root)
            invalidate(connectionId)
            check(!owned.exists() || owned.deleteRecursively()) { "Unable to remove connection content cache" }
        }
    }
}
