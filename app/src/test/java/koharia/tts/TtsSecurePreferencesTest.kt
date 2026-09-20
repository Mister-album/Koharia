package koharia.tts

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TtsSecurePreferencesTest {

    /** 反转字符串的假 codec：足够验证"落盘的不是明文"与往返一致。 */
    private class ReverseCodec : TtsSecretCodec {
        override fun encrypt(plain: String): String? = plain.reversed()
        override fun decrypt(payload: String): String? = payload.reversed()
    }

    /** 永远失败的假 codec：模拟密钥失效 / 密文损坏。 */
    private class FailingCodec : TtsSecretCodec {
        override fun encrypt(plain: String): String? = null
        override fun decrypt(payload: String): String? = null
    }

    private class InMemoryStorage : TtsSecretStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `api key round-trips through codec and is not stored in plaintext`() {
        val storage = InMemoryStorage()
        val prefs = TtsSecurePreferences(ReverseCodec(), storage)

        prefs.setApiKey("mimo", "secret-key")

        prefs.getApiKey("mimo") shouldBe "secret-key"
        (storage.map["api_key_mimo"] == "secret-key") shouldBe false
    }

    @Test
    fun `blank api key clears the entry`() {
        val storage = InMemoryStorage()
        val prefs = TtsSecurePreferences(ReverseCodec(), storage)
        prefs.setApiKey("mimo", "secret-key")

        prefs.setApiKey("mimo", "")

        prefs.getApiKey("mimo").shouldBeNull()
        (storage.map.containsKey("api_key_mimo")) shouldBe false
    }

    @Test
    fun `clearApiKey removes the entry`() {
        val storage = InMemoryStorage()
        val prefs = TtsSecurePreferences(ReverseCodec(), storage)
        prefs.setApiKey("mimo", "secret-key")

        prefs.clearApiKey("mimo")

        prefs.getApiKey("mimo").shouldBeNull()
    }

    @Test
    fun `decrypt failure reads as unconfigured instead of crashing`() {
        val storage = InMemoryStorage()
        storage.map["api_key_mimo"] = "some-payload"
        val prefs = TtsSecurePreferences(FailingCodec(), storage)

        prefs.getApiKey("mimo").shouldBeNull()
    }

    @Test
    fun `encrypt failure does not persist and reads back as unconfigured`() {
        val storage = InMemoryStorage()
        val prefs = TtsSecurePreferences(FailingCodec(), storage)

        prefs.setApiKey("mimo", "secret-key")

        storage.map.containsKey("api_key_mimo") shouldBe false
        prefs.getApiKey("mimo").shouldBeNull()
    }

    @Test
    fun `base url round-trips and blank clears`() {
        val storage = InMemoryStorage()
        val prefs = TtsSecurePreferences(ReverseCodec(), storage)

        prefs.setBaseUrl("mimo", "https://example.test")
        prefs.getBaseUrl("mimo") shouldBe "https://example.test"

        prefs.setBaseUrl("mimo", "")
        prefs.getBaseUrl("mimo").shouldBeNull()
    }

    @Test
    fun `legacy key is reported for reentry only while no new value exists`() {
        // 旧版 EncryptedSharedPreferences 的值无法解密：只在"旧文件里有它、当前读不到值"
        // 时才提示重填；用户重填后提示必须立即消失（无需重启）。
        val storage = InMemoryStorage()
        val prefs = TtsSecurePreferences(ReverseCodec(), storage, legacyKeys = setOf("api_key_mimo"))

        prefs.needsLegacyKeyReentry("mimo") shouldBe true
        // 旧文件里没出现过的 vendor 不该被提示
        prefs.needsLegacyKeyReentry("edge") shouldBe false

        prefs.setApiKey("mimo", "fresh-key")
        prefs.needsLegacyKeyReentry("mimo") shouldBe false
    }

    @Test
    fun `no legacy keys means no reentry prompt`() {
        val prefs = TtsSecurePreferences(ReverseCodec(), InMemoryStorage())

        prefs.needsLegacyKeyReentry("mimo") shouldBe false
    }

    @Test
    fun `payload pack and unpack round-trips`() {
        val iv = ByteArray(TtsSecretPayload.IV_LENGTH_BYTES) { it.toByte() }
        val ciphertext = byteArrayOf(9, 8, 7, 6)

        val unpacked = TtsSecretPayload.unpack(TtsSecretPayload.pack(iv, ciphertext))

        unpacked?.first shouldBe iv
        unpacked?.second shouldBe ciphertext
    }

    @Test
    fun `unpack rejects payloads without room for both iv and ciphertext`() {
        TtsSecretPayload.unpack(ByteArray(0)).shouldBeNull()
        // 恰好只有 IV、没有密文：必须拒绝，否则会用空密文去解密
        TtsSecretPayload.unpack(ByteArray(TtsSecretPayload.IV_LENGTH_BYTES)).shouldBeNull()
    }
}
