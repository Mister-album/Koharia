package koharia.kavita

import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KavitaIdentityTest {
    @Test fun legacyTokenSuppliesUserIdAndRolesAbsentFromUserDto() {
        val claims = """{"nameid":"12","role":["Login","Download"],"name":"reader"}""".encodeUtf8().base64Url()
        val account = withKavitaTokenClaims(
            KavitaAccount(username = "reader", token = "header.$claims.signature", kavitaVersion = "0.8.0"),
            Json,
        )
        assertEquals(12, account.id)
        val capabilities = KavitaCapabilities(KavitaVersion(0, 8), account.roles.toSet())
        assertTrue(capabilities.downloads)
        assertTrue(capabilities.writable)
        val readonly = """{"role":"Read Only"}""".encodeUtf8().base64Url()
        assertFalse(
            KavitaCapabilities(
                KavitaVersion(0, 8),
                withKavitaTokenClaims(KavitaAccount(token = "h.$readonly.s"), Json).roles.toSet(),
            ).writable,
        )
    }

    @Test fun changingServerOrRecreatingUserCannotReuseIdentity() {
        val original = KavitaAccountIdentity(12, "reader", "2025-01-01", "installation-a")
        assertTrue(original.matches(original.copy(username = "renamed")))
        assertTrue(original.sameServerVerified(original.copy(username = "renamed")))
        assertFalse(original.matches(original.copy(installId = "installation-b")))
        assertFalse(original.matches(original.copy(userId = 13)))
        assertFalse(original.matches(original.copy(createdUtc = "2026-01-01")))
        assertTrue(original.copy(installId = "").sameServerVerified(original))
        assertFalse(KavitaAccountIdentity(username = "reader").sameServerVerified(original))
    }
}
