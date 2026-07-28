package eu.kanade.tachiyomi.data.updater

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppUpdateDownloadPolicyTest {

    @Test
    fun `temporary HTTP failures are retryable`() {
        listOf(408, 429, 500, 502, 503, 504).forEach { code ->
            AppUpdateDownloadPolicy.isRetryableHttpCode(code) shouldBe true
        }
    }

    @Test
    fun `configuration HTTP failures are terminal`() {
        listOf(400, 403, 404, 413, 421).forEach { code ->
            AppUpdateDownloadPolicy.isRetryableHttpCode(code) shouldBe false
        }
    }

    @Test
    fun `content range exposes resume offset and total size`() {
        AppUpdateDownloadPolicy.parseContentRange("bytes 1024-2047/4096") shouldBe
            AppUpdateDownloadPolicy.ContentRange(
                start = 1024,
                endInclusive = 2047,
                total = 4096,
            )
    }

    @Test
    fun `malformed content range is rejected`() {
        AppUpdateDownloadPolicy.parseContentRange("bytes 2048-1024/4096") shouldBe null
        AppUpdateDownloadPolicy.parseContentRange("bytes 0-4096/4096") shouldBe null
        AppUpdateDownloadPolicy.parseContentRange(null) shouldBe null
    }
}
