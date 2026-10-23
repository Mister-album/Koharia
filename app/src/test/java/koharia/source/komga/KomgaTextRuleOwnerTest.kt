package koharia.source.komga

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KomgaTextRuleOwnerTest {
    @Test
    fun `first offline owner is stable and isolated by account binding`() {
        val stored = mutableMapOf<String, String>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        val preferences = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }
        val owner = localKomgaTextRuleOwner(preferences, "account-a")
        assertEquals(owner, localKomgaTextRuleOwner(preferences, "account-a"))
        assertNotEquals(owner, localKomgaTextRuleOwner(preferences, "account-b"))
        stored["text_rule_owner_verified"] = "existing-verified-owner"
        assertEquals("existing-verified-owner", localKomgaTextRuleOwner(preferences, "verified"))
    }
}
