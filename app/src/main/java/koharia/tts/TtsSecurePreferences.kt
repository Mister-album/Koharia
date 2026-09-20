package koharia.tts

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TTS 敏感配置（API key、可选 base URL 覆盖）的加密存储。
 *
 * 用**平台 Android Keystore** 做 AES-256-GCM：
 *  - 主密钥常驻 AndroidKeyStore（别名 [KeystoreTtsSecretCodec.DEFAULT_KEY_ALIAS]），不出进程；
 *  - 值加密后以 Base64 落进普通 [SharedPreferences]。
 *
 * 为什么不再用 `androidx.security:security-crypto`（review 修复）：
 *  - `EncryptedSharedPreferences` / `MasterKey` 已被官方标记废弃，本次编译产生多条告警；
 *  - 依赖版本只到 `1.1.0-alpha07`，没有可用的稳定升级路径。
 *  直连平台 Keystore API 可整条去掉该依赖，且不改变本类的公开 API。
 *
 * **迁移**：旧值由 EncryptedSharedPreferences 用**另一把主密钥**加密，移除依赖后无法解密，
 * 因此改用新文件名 [PREFS_FILE_NAME_V2]；旧文件不再读取，用户需重新填写 API key。
 * 只打一行日志等于"静默丢配置"（用户看不到 logcat），故同时记下旧 key 名，
 * 由设置页经 [needsLegacyKeyReentry] 显示常驻提示。root 设备同样读不到明文。
 *
 * AGENTS.md 把 `local.properties`/API keys 列为 **secrets** —— 本类把 API key 收进运行时
 * 加密存储，构建产物内不含任何 key；key 一律由用户在「设置 → TTS 引擎」配置。
 *
 * **生命周期**：Application 单例（Injekt 注册）。这也是读取 API key 的唯一来源。
 */
class TtsSecurePreferences internal constructor(
    private val codec: TtsSecretCodec,
    private val storage: TtsSecretStorage,
    /**
     * 旧版（`EncryptedSharedPreferences`）文件里出现过的 secret key 名，如 `api_key_mimo`。
     *
     * 旧值用另一把主密钥加密，移除依赖后**无法解密**，只能让用户重填。保留 key 名是为了
     * 让设置页能精确提示"哪个厂商的 key 需要重填"，而不是只往 logcat 打一行 —— 用户看不到
     * 日志，那就是静默丢配置。默认空集：单测可只注入 codec/storage。
     */
    private val legacyKeys: Set<String> = emptySet(),
) {

    constructor(context: Context) : this(
        codec = KeystoreTtsSecretCodec(),
        storage = SharedPreferencesTtsSecretStorage(
            context.getSharedPreferences(PREFS_FILE_NAME_V2, Context.MODE_PRIVATE),
        ),
        legacyKeys = Companion.readLegacySecretKeys(context),
    )

    // ===== API key =====

    /**
     * 读取指定 vendor 的 API key。
     *
     * 返回 `null` 表示未配置（未输入 / 被清除 / 解密失败）。`null` / blank 都视为未配置。
     * TtsService 把返回值喂给引擎的 `apiKeyProvider`，后者据此判断 `isConfigured`。
     */
    fun getApiKey(vendorId: String): String? = readSecret(apiKeyPrefKey(vendorId))

    /**
     * 该 vendor 的 key **只存在于旧版加密存储里**、当前实现读不到 ⟹ 设置页需要提示重填。
     *
     * 纯读取、无副作用：用户重填后 [getApiKey] 立刻有值，提示自动消失（无需重启）。
     */
    fun needsLegacyKeyReentry(vendorId: String): Boolean =
        apiKeyPrefKey(vendorId) in legacyKeys && getApiKey(vendorId) == null

    /** 写入指定 vendor 的 API key（空白字符串会清除）。 */
    fun setApiKey(vendorId: String, key: String) {
        writeSecret(apiKeyPrefKey(vendorId), key)
    }

    /** 清除指定 vendor 的 API key。 */
    fun clearApiKey(vendorId: String) {
        storage.remove(apiKeyPrefKey(vendorId))
    }

    // ===== Base URL 覆盖 =====

    /** 自定义 base URL（给 MiMo 等允许换节点的服务）。null = 用引擎默认值。 */
    fun getBaseUrl(vendorId: String): String? = readSecret(baseUrlPrefKey(vendorId))

    fun setBaseUrl(vendorId: String, url: String) {
        writeSecret(baseUrlPrefKey(vendorId), url)
    }

    private fun readSecret(key: String): String? {
        val payload = storage.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        return codec.decrypt(payload)?.takeIf { it.isNotBlank() }
    }

    private fun writeSecret(key: String, value: String) {
        if (value.isBlank()) {
            storage.remove(key)
            return
        }
        val encrypted = codec.encrypt(value)
        if (encrypted == null) {
            logcat(LogPriority.WARN) { "[TtsSecurePreferences] encrypt failed; value not persisted" }
            return
        }
        storage.putString(key, encrypted)
    }

    companion object {
        private const val PREFS_FILE_NAME_V2 = "tts_secure_prefs_v2"

        /** 旧 EncryptedSharedPreferences 文件名；只用于检测并提示用户重填。 */
        private const val PREFS_FILE_NAME_LEGACY = "tts_secure_prefs"

        /**
         * 读取旧版 prefs 里出现过的 key 名，只用于向用户提示"需要重填"。
         *
         * 必须是独立函数，不能写成构造函数委托参数里的 `run { ... }`：那时 `run` 会把
         * `this` 解析为**尚未初始化**的实例而编译失败
         * （`Cannot access '<this>' before the instance has been initialized`）。
         */
        private fun readLegacySecretKeys(context: Context): Set<String> {
            val legacy = context.getSharedPreferences(PREFS_FILE_NAME_LEGACY, Context.MODE_PRIVATE)
            val keys = legacy.all.keys.toSet()
            if (keys.isNotEmpty()) {
                logcat(LogPriority.INFO) {
                    "[TtsSecurePreferences] legacy encrypted prefs detected " +
                        "(${keys.size} entries); API key must be re-entered"
                }
            }
            return keys
        }

        private fun apiKeyPrefKey(vendorId: String) = "api_key_$vendorId"
        private fun baseUrlPrefKey(vendorId: String) = "base_url_$vendorId"
    }
}

/**
 * 明文 ↔ 可落盘密文的编解码抽象。
 *
 * 抽出接口是为了让 [TtsSecurePreferences] 能在 JVM 单测里注入假实现
 * （Android Keystore 在普通单测环境不可用）。
 */
internal interface TtsSecretCodec {
    /** 加密明文，返回可落盘的 Base64 payload；失败返回 null。 */
    fun encrypt(plain: String): String?

    /** 解密 payload；失败 / 密钥失效 / 格式损坏返回 null，绝不抛异常。 */
    fun decrypt(payload: String): String?
}

/** 键值存储抽象，便于单测注入内存实现。 */
internal interface TtsSecretStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** [SharedPreferences] 支撑的 [TtsSecretStorage]。 */
internal class SharedPreferencesTtsSecretStorage(
    private val prefs: SharedPreferences,
) : TtsSecretStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * `iv ++ ciphertext` 打包 / 拆包（纯函数，便于 JVM 单测覆盖边界）。
 *
 * GCM 的 16 字节认证 tag 由 [Cipher.doFinal] 追加在密文尾部，无需单独处理。
 */
internal object TtsSecretPayload {

    /** AES-GCM 标准 IV 长度（字节）。 */
    const val IV_LENGTH_BYTES = 12

    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        return out
    }

    /** 拆包；长度不足（没有 IV 或没有密文）返回 null。 */
    fun unpack(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        if (payload.size <= IV_LENGTH_BYTES) return null
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        return iv to ciphertext
    }
}

/**
 * Android Keystore 支撑的 AES-256-GCM 编解码。
 *
 * 落盘格式：`Base64(iv(12B) ++ ciphertext ++ tag(16B))`。
 * 主密钥首次使用时在 AndroidKeyStore 里生成，之后复用；设备锁屏/密钥失效导致解密失败时
 * 返回 `null`（上层视为未配置），不崩溃。
 */
internal class KeystoreTtsSecretCodec(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : TtsSecretCodec {

    override fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(TtsSecretPayload.pack(cipher.iv, ciphertext), Base64.NO_WRAP)
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "[TtsSecurePreferences] encrypt failed" }
            null
        }
    }

    override fun decrypt(payload: String): String? {
        return try {
            val unpacked = TtsSecretPayload.unpack(Base64.decode(payload, Base64.NO_WRAP))
                ?: return null
            val (iv, ciphertext) = unpacked
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) {
                "[TtsSecurePreferences] decrypt failed (key rotated or malformed value)"
            }
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "tts_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
