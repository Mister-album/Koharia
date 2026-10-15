package koharia.epub.cache

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import java.nio.file.Path

class EpubTransferClientLifecycleTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `replacing a source client replaces the transfer session interceptor`() {
        mockkObject(LocalTempCacheDirectoryProvider)
        try {
            every { LocalTempCacheDirectoryProvider.epubCacheDir(any()) } returns directory.toFile()
            val manager = EpubCacheManager(mockk<Application>(), mockk<EpubCachePreferences>(relaxed = true))
            val previousSession = Interceptor { error("No network is needed to inspect session ownership") }
            val currentSession = Interceptor { error("No network is needed to inspect session ownership") }
            val previous = OkHttpClient.Builder().addInterceptor(previousSession).build()
            val current = OkHttpClient.Builder().addInterceptor(currentSession).build()
            val transferClient = EpubCacheManager::class.java.getDeclaredMethod(
                "completeBookTransferClient",
                Long::class.javaPrimitiveType,
                OkHttpClient::class.java,
            ).apply { isAccessible = true }
            val first = transferClient.invoke(manager, 41L, previous) as OkHttpClient
            assertSame(previousSession, first.interceptors.single())

            val next = transferClient.invoke(manager, 41L, current) as OkHttpClient
            assertSame(currentSession, next.interceptors.single(), "A reloaded source must use its current session")
            assertSame(first.dispatcher, next.dispatcher, "Transfers for the same connection retain their limit")
            assertEquals(1, next.dispatcher.maxRequests)
            assertEquals(1, next.dispatcher.maxRequestsPerHost)
            assertEquals(0, next.callTimeoutMillis)
            assertSame(current.connectionPool, next.connectionPool)
            val other = transferClient.invoke(manager, 42L, current) as OkHttpClient
            assertNotSame(next.dispatcher, other.dispatcher)
        } finally {
            unmockkObject(LocalTempCacheDirectoryProvider)
        }
    }
}
