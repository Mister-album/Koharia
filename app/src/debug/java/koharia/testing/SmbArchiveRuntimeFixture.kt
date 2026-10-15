package koharia.testing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.system.Os
import androidx.annotation.Keep
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.core.archive.ArchiveReader
import koharia.domain.storage.LibraryStorageRepository
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StorageCacheIndex
import koharia.storage.StorageDirectorySnapshot
import koharia.storage.StorageEntry
import koharia.storage.storageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.function.Consumer

object SmbArchiveRuntimeFixture {
    @Keep
    @JvmStatic
    fun run(context: Context, address: String, imageHash: String, status: Consumer<String>): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        check(URI(address).scheme == "smb" && URI(address).host == "127.0.0.1")
        check(imageHash.matches(Regex("[a-f0-9]{64}")))
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val previous = connections.activeConnectionId.get()
        val hadPrevious = connections.activeConnectionId.isSet()
        val profile = profiles.add("local-folder", "Owned SMB runtime ${UUID.randomUUID()}")
        val root = File(context.filesDir, "library-storage-cache").canonicalFile
        var owned: File? = null
        try {
            val network = NetworkStoragePreferences(profile.id)
            val config = NetworkStorageConfiguration(
                mode = LibraryStorageMode.SMB,
                address = address,
                rootIdentity = "e13f2ba1-8040-4bca-960a-5ac54ec78c34",
            )
            network.save(config, "root", "owned-fixture")
            check(network.configuration.copy(accountIdentity = "") == config)
            val runtime = NetworkStorageRuntime.get(context, profile.id)
            val folder = File(root, storageDigest("${profile.id}/${runtime.account}/${config.rootIdentity}"))
                .canonicalFile
            check(folder.parentFile == root && !folder.exists())
            owned = folder
            val local = LocalLibraryPreferences(profile.id, Injekt.get<Json>())
            val library = LocalLibraryConfig(
                roots = listOf(LocalLibraryRootConfig(id = "owned-root", treeUri = runtime.uri("").toString())),
            )
            local.setConfig(library)
            check(local.getConfig().roots == library.roots)
            withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is LocalFolderSource) delay(25)
            }
            val entry = runtime.backend.stat("book.cbz")
            check(!entry.directory && entry.size > 0)
            val budget = Injekt.get<SharedAppPreferences>().store().getInt("network_storage_cache_mb", 512)
                .get().coerceIn(128, 4096) * 1024L * 1024
            StorageCacheIndex.get(root).access {
                check(measuredBytes() + entry.size < budget) { "Fixture cannot evict pre-existing cache" }
            }
            val snapshot = StorageDirectorySnapshot(StorageEntry("", true), listOf(entry), 258)
            runtime.records.put("directories", "", snapshot)
            check(runtime.records.get<StorageDirectorySnapshot>("directories", "") == snapshot)
            fun readArchive() {
                context.contentResolver.openFileDescriptor(runtime.uri("book.cbz"), "r")!!.use { descriptor ->
                    check(Os.readlink("/proc/self/fd/${descriptor.fd}").startsWith("/mnt/appfuse/"))
                    ArchiveReader(descriptor).use { archive ->
                        val bytes = checkNotNull(archive.getInputStream("001.png")).use { it.readBytes() }
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        check(hash == imageHash)
                        val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        try {
                            check(image.width == 64 && image.height == 96)
                        } finally {
                            image.recycle()
                        }
                    }
                }
            }
            readArchive()
            status.accept("registered;configuration-and-snapshot-roundtrip;proxy-fd;archive-and-png")
            NetworkStorageRuntime.invalidate(profile.id)
            val reopened = NetworkStorageRuntime.get(context, profile.id)
            check(reopened.records.get<StorageDirectorySnapshot>("directories", "") == snapshot)
            context.contentResolver.openFileDescriptor(reopened.uri("book.cbz"), "r")!!.use { descriptor ->
                ArchiveReader(descriptor).use { archive ->
                    val bytes = checkNotNull(archive.getInputStream("001.png")).use { it.readBytes() }
                    check(
                        MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) } == imageHash,
                    )
                }
            }
            status.accept("runtime-reopened;persisted-snapshot;cached-archive")
            status.accept("debuggable=${context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        } finally {
            withContext(NonCancellable) {
                try {
                    NetworkStorageRuntime.invalidate(profile.id)
                    Injekt.get<LibraryStorageRepository>().removeConnection(profile.id)
                    owned?.let { folder ->
                        check(folder.canonicalFile.parentFile == root)
                        StorageCacheIndex.get(root).access {
                            try {
                                check(!folder.exists() || folder.deleteRecursively())
                            } finally {
                                invalidate()
                            }
                        }
                    }
                    profiles.remove(profile.id).getOrThrow()
                    check(connections.getProfiles().none { it.id == profile.id })
                    status.accept("owned-profile-records-cache-cleaned")
                } finally {
                    if (hadPrevious) {
                        connections.activeConnectionId.set(
                            previous,
                        )
                    } else {
                        connections.activeConnectionId.delete()
                    }
                }
            }
        }
    }
}
