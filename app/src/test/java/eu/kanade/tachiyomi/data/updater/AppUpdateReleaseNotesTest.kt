package eu.kanade.tachiyomi.data.updater

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppUpdateReleaseNotesTest {

    @Test
    fun `marked release selects Chinese and removes the legacy divider`() {
        val content = """
            <!-- koharia-release-notes:zh -->
            # Koharia v1.0.0
            中文说明
            ---
            <!-- koharia-release-notes:en -->
            # Koharia v1.0.0
            English notes
            <!-- koharia-release-notes:end -->
        """.trimIndent()

        AppUpdateReleaseNotes.select(content, "zh") shouldBe """
            # Koharia v1.0.0
            中文说明
        """.trimIndent()
    }

    @Test
    fun `marked release selects English for every non Chinese language`() {
        val content = """
            <!-- koharia-release-notes:zh -->
            中文说明
            <!-- koharia-release-notes:en -->
            English notes
            <!-- koharia-release-notes:end -->
        """.trimIndent()

        AppUpdateReleaseNotes.select(content, "en") shouldBe "English notes"
        AppUpdateReleaseNotes.select(content, "ja") shouldBe "English notes"
    }

    @Test
    fun `legacy bilingual release remains supported`() {
        val content = """
            # Koharia v1.0.0
            中文说明

            ---

            # Koharia v1.0.0
            English notes
        """.trimIndent()

        AppUpdateReleaseNotes.select(content, "zh") shouldBe """
            # Koharia v1.0.0
            中文说明
        """.trimIndent()
        AppUpdateReleaseNotes.select(content, "fr") shouldBe """
            # Koharia v1.0.0
            English notes
        """.trimIndent()
    }

    @Test
    fun `single language release is retained as a safe fallback`() {
        AppUpdateReleaseNotes.select("# 更新说明\n\n只有中文", "en") shouldBe "# 更新说明\n\n只有中文"
    }

    @Test
    fun `checksum content after the end marker is not displayed`() {
        val content = """
            <!-- koharia-release-notes:zh -->
            中文说明
            <!-- koharia-release-notes:en -->
            English notes
            <!-- koharia-release-notes:end -->

            ---

            ## Checksums
            abc123
        """.trimIndent()

        AppUpdateReleaseNotes.select(content, "en") shouldBe "English notes"
    }
}
