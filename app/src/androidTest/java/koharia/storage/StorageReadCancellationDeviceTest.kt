package koharia.storage

import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.SharedAppPreferences
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StorageReadCancellationDeviceTest {
    @Test
    fun invalidateSessionWhileRemoteReadIsPendingRejectsResultAndAllowsReopen() = exercise(LibraryStorageMode.WEBDAV)

    @Test
    fun invalidateSessionWhileSmbReadIsPendingRejectsResultAndAllowsReopen() = exercise(LibraryStorageMode.SMB)

    private fun exercise(mode: LibraryStorageMode): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val address = checkNotNull(InstrumentationRegistry.getArguments().getString("cancellationServiceAddress"))
        check(URI(address).host == "127.0.0.1" && URI(address).path == "/")
        val storageAddress = if (mode == LibraryStorageMode.SMB) {
            checkNotNull(InstrumentationRegistry.getArguments().getString("smbCancellationAddress")).also {
                check(URI(it).scheme == "smb" && URI(it).host == "127.0.0.1" && URI(it).path == "/fixture/")
            }
        } else {
            address
        }
        val id = Long.MAX_VALUE - System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        val configuration = NetworkStorageConfiguration(
            mode = mode,
            address = storageAddress,
            rootIdentity = "e13f2ba1-8040-4bca-960a-5ac54ec78c34",
        )
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        fun control(path: String): String = client.newCall(Request.Builder().url(address + "control/" + path).build())
            .execute().use { response ->
                check(response.isSuccessful)
                response.body.string()
            }
        val executor = Executors.newFixedThreadPool(2)
        val protocolWaitSeconds = if (mode == LibraryStorageMode.SMB) 25L else 10L
        val root = File(context.filesDir, "library-storage-cache").canonicalFile
        var owned: File? = null
        try {
            NetworkStoragePreferences(id).save(
                configuration,
                if (mode == LibraryStorageMode.SMB) "root" else "",
                if (mode == LibraryStorageMode.SMB) "owned-fixture" else "",
            )
            val runtime = NetworkStorageRuntime.get(context, id)
            val folder = File(root, storageDigest("$id/${runtime.account}/${configuration.rootIdentity}")).canonicalFile
            check(folder.parentFile == root && !folder.exists())
            owned = folder
            val entry = runtime.backend.stat("book.bin")
            check(entry.size == 400000L)
            val budget = Injekt.get<SharedAppPreferences>().store().getInt("network_storage_cache_mb", 512)
                .get().coerceIn(128, 4096) * 1024L * 1024
            StorageCacheIndex.get(root).access { check(measuredBytes() + entry.size < budget) }
            runtime.records.put("directories", "", StorageDirectorySnapshot(StorageEntry("", true), listOf(entry), 260))
            context.contentResolver.openFileDescriptor(runtime.uri("book.bin"), "r")!!.use { descriptor ->
                check(Os.readlink("/proc/self/fd/${descriptor.fd}").startsWith("/mnt/appfuse/"))
                val result = executor.submit<Throwable?> {
                    runCatching { Os.pread(descriptor.fileDescriptor, ByteArray(23), 0, 23, 17) }.exceptionOrNull()
                }
                try {
                    withTimeout(10_000) { while (control("held") != "true") delay(25) }
                    val started = SystemClock.elapsedRealtime()
                    val invalidation = executor.submit { NetworkStorageRuntime.invalidate(id) }
                    withTimeout(10_000) {
                        while (runCatching { runtime.session.checkActive() }.isSuccess) delay(25)
                    }
                    control("release")
                    // SMB's configured operation timeout is 20 seconds, including pending close requests.
                    invalidation.get(protocolWaitSeconds, TimeUnit.SECONDS)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply { putLong("storage_invalidation_ms", SystemClock.elapsedRealtime() - started) },
                    )
                } finally {
                    control("release")
                }
                val failure = result.get(protocolWaitSeconds, TimeUnit.SECONDS)
                assertTrue("Obsolete in-flight read must not return successful data: $failure", failure != null)
                assertTrue("Session invalidation must be an IO failure", failure is android.system.ErrnoException)
                assertEquals(OsConstants.EIO, (failure as android.system.ErrnoException).errno)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("storage_cancel", "in-flight-read-rejected") })
            val reopened = NetworkStorageRuntime.get(context, id)
            assertEquals(entry, reopened.cached("book.bin"))
            context.contentResolver.openFileDescriptor(reopened.uri("book.bin"), "r")!!.use { descriptor ->
                val bytes = ByteArray(23)
                assertEquals(23, Os.pread(descriptor.fileDescriptor, bytes, 0, 23, 17))
                assertArrayEquals(ByteArray(23) { ((17 + it) % 251).toByte() }, bytes)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("storage_cancel", "new-descriptor-readable") })
        } finally {
            runCatching { control("release") }
            executor.shutdown()
            check(executor.awaitTermination(15, TimeUnit.SECONDS))
            NetworkStorageRuntime.invalidate(id)
            Injekt.get<LibraryStorageRepository>().removeConnection(id)
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
            check(preferences.edit().clear().commit())
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            instrumentation.sendStatus(0, Bundle().apply { putString("storage_cancel", "owned-state-cleaned") })
        }
    }
}
