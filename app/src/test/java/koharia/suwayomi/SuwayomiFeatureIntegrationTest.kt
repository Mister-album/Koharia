package koharia.suwayomi

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Read-only smoke checks for the browse feature against a disposable server. */
class SuwayomiFeatureIntegrationTest {
    @Test
    fun `browse queries expose extensions sources categories and server downloads`() = runTest {
        val address = System.getenv("SUWAYOMI_TEST_URL")
        assumeTrue(!address.isNullOrBlank(), "No isolated test server configured")
        val mode = SuwayomiAuthMode.valueOf(System.getenv("SUWAYOMI_TEST_MODE") ?: "NONE")
        SuwayomiApi(
            OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
            Json { ignoreUnknownKeys = true },
            address,
            mode,
            System.getenv("SUWAYOMI_TEST_USERNAME").orEmpty(),
            System.getenv("SUWAYOMI_TEST_PASSWORD").orEmpty(),
        ).use { api ->
            val extensions = api.extensions()
            val sources = api.sources()
            assertNotNull(api.downloadStatus())
            assertNotNull(api.categories())
            assertTrue(extensions.all { it.pkgName.isNotBlank() })
            sources.firstOrNull()?.let { source ->
                assertTrue(api.sourceMangaPage(source.id, null, null).nodes.all { it.id > 0 })
            }
        }
    }
}
