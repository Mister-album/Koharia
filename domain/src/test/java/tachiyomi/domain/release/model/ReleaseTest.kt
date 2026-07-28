package tachiyomi.domain.release.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReleaseTest {

    @Test
    fun `download links keep primary source first and remove duplicates`() {
        val release = Release(
            version = "v1.0.0",
            info = "info",
            releaseLink = "https://example.com/release",
            downloadLink = "https://download.example.com/app.apk",
            fallbackDownloadLinks = listOf(
                "https://github.com/example/app.apk",
                "https://download.example.com/app.apk",
            ),
        )

        release.downloadLinks shouldBe listOf(
            "https://download.example.com/app.apk",
            "https://github.com/example/app.apk",
        )
    }
}
