package eu.kanade.tachiyomi.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class EncryptedBackupPayload(
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val iterations: Int,
)

internal object BackupCrypto {
    private const val ITERATIONS = 600_000
    private const val KEY_LENGTH = 256
    private const val TAG_LENGTH = 128
    private val associatedData = "KOHARIA_BACKUP_V2".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, password: CharArray): EncryptedBackupPayload {
        require(password.isNotEmpty())
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_LENGTH, iv))
        cipher.updateAAD(associatedData)
        return EncryptedBackupPayload(salt, iv, cipher.doFinal(plain), ITERATIONS)
    }

    fun decrypt(payload: EncryptedBackupPayload, password: CharArray): ByteArray {
        require(password.isNotEmpty())
        require(payload.salt.size == 16 && payload.iv.size == 12)
        require(payload.iterations in ITERATIONS..2_000_000)
        require(payload.ciphertext.size >= TAG_LENGTH / Byte.SIZE_BITS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(password, payload.salt, payload.iterations),
            GCMParameterSpec(TAG_LENGTH, payload.iv),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.ciphertext)
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
