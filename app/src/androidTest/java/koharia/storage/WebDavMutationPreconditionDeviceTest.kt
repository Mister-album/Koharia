package koharia.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Requires the isolated WsgiDAV fixture, with weak/missing PROPFIND validator filters. */
@RunWith(AndroidJUnit4::class)
class WebDavMutationPreconditionDeviceTest {
    @Test fun weakValidatorsKeepTheResourceLockedDuringMove() = exercise("weak")

    @Test fun missingValidatorsKeepTheResourceLockedDuringMove() = exercise("missing")

    @Test fun strongValidatorsRejectAnExternalReplacementWithoutMovingIt() = exercise("strong")

    @Test fun bareWsgiDavValidatorsUseTheLockFallback() = exercise("bare")

    private fun exercise(mode: String): Unit = runBlocking(Dispatchers.IO) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val base = "http://127.0.0.1:18773/$mode/"
        val client = OkHttpClient()
        val parent = WebDavStorageBackend(client, base, "", "")
        val folder = "precondition-${UUID.randomUUID()}"
        parent.createDirectory(folder)
        val address = "$base$folder/"
        val concurrentStatus = AtomicInteger()
        val attempts = AtomicInteger()
        val replacement = "external edit".encodeToByteArray()
        val guardedClient = client.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.method == "MOVE" && request.url.encodedPath.endsWith("/book.bin")) {
                attempts.incrementAndGet()
                client.newCall(Request.Builder().url(request.url).put(replacement.toRequestBody()).build())
                    .execute().use { concurrentStatus.set(it.code) }
            }
            chain.proceed(request)
        }.build()
        val backend = WebDavStorageBackend(guardedClient, address, "", "")
        val repository = Injekt.get<LibraryStorageRepository>()
        val id = Long.MAX_VALUE - System.nanoTime()
        var primaryFailure: Throwable? = null
        try {
            val rootIdentity = StorageIdentity.ensure(backend)
            val records =
                StorageRecordStore(StorageSession(id, "conditional-fixture", rootIdentity) { true }, repository, Json)
            val identities = StorageResourceIdentities(backend, records)
            val content = ByteArray(4096) { (it % 251).toByte() }
            backend.write("book.bin", content.inputStream(), content.size.toLong())
            val original = backend.stat("book.bin")
            when (mode) {
                "strong" -> assertTrue(original.version.startsWith('"'))
                "weak" -> assertTrue(original.version.startsWith("W/\""))
                "missing" -> assertFalse(original.version.contains('"'))
                "bare" -> assertFalse(original.version.contains('"'))
            }
            val originalIdentity = identities.identity(original)
            val result = runCatching { StorageMutations(backend, records, identities).move(original, "target.bin") }
            assertEquals(1, attempts.get())
            if (mode == "strong") {
                assertTrue(concurrentStatus.get() in 200..299)
                assertEquals(StorageFailure.Reason.CONFLICT, (result.exceptionOrNull() as? StorageFailure)?.reason)
                val current = backend.stat("book.bin")
                assertArrayEquals(replacement, backend.read(current, 0, replacement.size))
                assertTrue(backend.list("").none { it.path == "target.bin" })
                assertEquals(1, records.list("mutations").size)
            } else {
                result.getOrThrow()
                assertEquals(423, concurrentStatus.get())
                assertTrue(backend.list("").none { it.path == "book.bin" })
                val target = backend.stat("target.bin")
                assertArrayEquals(content, backend.read(target, 0, content.size))
                assertEquals(originalIdentity, identities.identity(target))
                assertTrue(records.list("mutations").isEmpty())
            }

            backend.createDirectory("empty")
            val directory = backend.stat("empty")
            backend.write("empty/new.bin", content.inputStream(), content.size.toLong())
            val deletion = runCatching { backend.delete(directory) }
            assertEquals(StorageFailure.Reason.CONFLICT, (deletion.exceptionOrNull() as? StorageFailure)?.reason)
            assertEquals(content.size.toLong(), backend.stat("empty/new.bin").size)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            repository.removeConnection(id)
            suspend fun removeOwned(path: String) {
                backend.list(path).forEach { if (it.directory) removeOwned(it.path) else backend.delete(it) }
                if (path.isNotEmpty()) backend.delete(backend.stat(path))
            }
            try {
                removeOwned("")
                try {
                    parent.delete(parent.stat(folder))
                } catch (error: StorageFailure) {
                    // WsgiDAV 4.3.3 leaves source-path locks after MOVE and can fail when purging
                    // expired locks. Leave only this empty owned parent for host fixture cleanup.
                    if (error.reason != StorageFailure.Reason.CONFLICT || parent.list(folder).isNotEmpty()) throw error
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        0,
                        android.os.Bundle().apply { putString("webdav_fixture_empty_parent", "$mode/$folder") },
                    )
                }
            } catch (error: Throwable) {
                if (primaryFailure == null) throw error
                primaryFailure.addSuppressed(error)
            } finally {
                backend.close()
                parent.close()
            }
        }
    }
}
