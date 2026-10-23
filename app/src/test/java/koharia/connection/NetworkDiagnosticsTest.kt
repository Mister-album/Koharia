package koharia.connection

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class NetworkDiagnosticsTest {
    @Test
    fun `address classification distinguishes configuration failures without retaining values`() {
        val cases = mapOf(
            "" to "empty",
            "  " to "empty",
            "private-host:3000" to "missing-or-unsupported-scheme",
            "ftp://private-host" to "missing-or-unsupported-scheme",
            "http://" to "invalid-url",
            "http://private-user:private-password@private-host" to "embedded-credentials",
            "https://private-host/?key=private-token" to "query-present",
            "https://private-host/#private-section" to "fragment-present",
            "http://192.0.2.5:3000/lrr/" to "valid",
            "https://private-host/private-prefix/" to "valid",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, NetworkDiagnostics.addressState(input))
        }
    }

    @Test
    fun `endpoint labels omit addresses prefixes archive ids and queries`() {
        val cases = mapOf(
            "api/info" to "info",
            "api/archives" to "archives",
            "api/archives/untagged" to "untagged",
            "api/archives/private-archive/metadata" to "archive-metadata",
            "api/archives/private-archive/progress/23" to "archive-progress",
            "api/archives/private-archive/thumbnail" to "archive-thumbnail",
            "api/tankoubons/private-collection/full" to "tank-detail",
            "api/search?filter=private-title&token=private-key" to "search",
            "api/page?path=private-file.jpg" to "other",
            "private-path" to "other",
        )
        cases.forEach { (path, expected) ->
            val request = Request.Builder().url("https://private-host.test/private-prefix/$path")
                .header("Authorization", "Bearer private-key").build()
            val actual = NetworkDiagnostics.endpoint(request)
            assertEquals(expected, actual)
            assertFalse("private" in actual)
        }
        assertEquals("IllegalStateException", NetworkDiagnostics.errorType(IllegalStateException("private-token")))
    }
}
