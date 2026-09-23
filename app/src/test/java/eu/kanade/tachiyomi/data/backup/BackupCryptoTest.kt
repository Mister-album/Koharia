package eu.kanade.tachiyomi.data.backup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException

class BackupCryptoTest {
    @Test
    fun `encrypted payload requires the original password`() {
        val plain = "all provider settings and reading history".toByteArray()
        val encrypted = BackupCrypto.encrypt(plain, "correct password".toCharArray())

        assertArrayEquals(plain, BackupCrypto.decrypt(encrypted, "correct password".toCharArray()))
        assertThrows(AEADBadTagException::class.java) {
            BackupCrypto.decrypt(encrypted, "wrong password".toCharArray())
        }
    }
}
