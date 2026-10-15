package koharia.storage

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class StorageEndpointServiceDeviceTest {
    @Test
    fun verifiedEndpointsFailOverReadsWithoutReplayingUncertainWrites(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val args = InstrumentationRegistry.getArguments()
        val mode = LibraryStorageMode.valueOf(checkNotNull(args.getString("endpointMode")))
        check(mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val primaryAddress = checkNotNull(args.getString("endpointPrimary"))
        val internalAddress = checkNotNull(args.getString("endpointInternal"))
        val username = args.getString("endpointUser") ?: "fixture"
        for (address in listOf(primaryAddress, internalAddress)) check(URI(address).host == "127.0.0.1")
        val expectedRoot = "e13f2ba1-8040-4bca-960a-5ac54ec78c34"
        for (case in listOf("same", "fallback", "different", "cloned", "auth", "ambiguous")) {
            val primaryUrl = primaryAddress.trimEnd('/') + "/$case/"
            val internalUrl = internalAddress.trimEnd('/') + "/$case/"
            val config = NetworkStorageConfiguration(mode = mode, address = primaryUrl, rootIdentity = expectedRoot)
            val primary = NetworkStorageRuntime.backend(config, primaryUrl, username, "owned-fixture")
            val internal = NetworkStorageRuntime.backend(
                config,
                internalUrl,
                username,
                if (case == "auth") "wrong-owned-fixture" else "owned-fixture",
            )
            var network: String? = "owned-wifi"
            val router = StorageEndpointRouter(primary, internal, { network }, {
                if (StorageIdentity.read(it) != expectedRoot) throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            })
            suspend fun expect(reason: StorageFailure.Reason, block: suspend () -> Unit) {
                val error = runCatching { block() }.exceptionOrNull()
                assertTrue("$case expected failure, got $error", error is StorageFailure)
                assertEquals(case, reason, (error as StorageFailure).reason)
            }
            try {
                when (case) {
                    "different" -> {
                        expect(StorageFailure.Reason.UNVERIFIED) {
                            StorageIdentity.verify(primary, internal, expectedRoot)
                        }
                        expect(StorageFailure.Reason.UNVERIFIED) { router.stat("book.bin") }
                    }
                    "cloned" -> expect(StorageFailure.Reason.NOT_FOUND) {
                        StorageIdentity.verify(primary, internal, expectedRoot)
                    }
                    "auth" -> expect(StorageFailure.Reason.AUTH) { router.stat("book.bin") }
                    else -> {
                        StorageIdentity.verify(primary, internal, expectedRoot)
                        if (case == "ambiguous") {
                            val bytes = "Owned uncertain write".encodeToByteArray()
                            val error = runCatching {
                                router.write("uncertain.bin", bytes.inputStream(), bytes.size.toLong())
                            }.exceptionOrNull()
                            assertTrue("Uncertain write must fail", error is java.io.IOException)
                        } else {
                            val expected = "Owned endpoint $case".encodeToByteArray()
                            val entry = router.stat("book.bin")
                            assertArrayEquals(expected, router.read(entry, 0, entry.size.toInt()))
                            if (case == "same") {
                                network = null
                                assertArrayEquals(expected, router.read(entry, 0, entry.size.toInt()))
                                network = "second-owned-wifi"
                                assertArrayEquals(expected, router.read(entry, 0, entry.size.toInt()))
                            }
                        }
                    }
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply { putString("endpoint_service_case", "$mode:$case") },
                )
            } finally {
                router.close()
            }
        }
    }
}
