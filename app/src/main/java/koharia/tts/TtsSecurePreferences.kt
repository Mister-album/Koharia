package koharia.tts

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * TTS 敏感配置(API key、可选 base URL 覆盖)的加密存储。
 *
 * 用 [EncryptedSharedPreferences](https://developer.android.com/topic/security/data) 把值落盘:
 *  - key 用 Android Keystore 派生(主密钥 [MasterKey] AES256_GCM)
 *  - value 用 AES256_GCM 加密后写入
 *
 * root 设备也难以直接读出明文。AGENTS.md 把 `local.properties`/API keys 列为
 * **secrets** —— 本类把 API key 完全收进运行时加密存储,构建产物内不含任何 key;
 * key 一律由用户在「设置 → TTS 引擎」配置。
 *
 * **生命周期**:Application 单例(Injekt 注册)。这也是读取 API key 的唯一来源。
 */
class TtsSecurePreferences(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ===== API key =====

    /**
     * 读取指定 vendor 的 API key。
     *
     * 返回 `null` 表示未配置(用户未输入或被清除)。`null` / blank 都视为未配置。
     * TtsService 把返回值喂给 [MimoEngine.apiKeyProvider],后者据此判断
     * [TtsEngine.isConfigured]。
     */
    fun getApiKey(vendorId: String): String? =
        prefs.getString(apiKeyPrefKey(vendorId), null)?.takeIf { it.isNotBlank() }

    /** 写入指定 vendor 的 API key(空白字符串会清除)。 */
    fun setApiKey(vendorId: String, key: String) {
        val edit = prefs.edit()
        if (key.isBlank()) {
            edit.remove(apiKeyPrefKey(vendorId))
        } else {
            edit.putString(apiKeyPrefKey(vendorId), key)
        }
        edit.apply()
    }

    /** 清除指定 vendor 的 API key。 */
    fun clearApiKey(vendorId: String) {
        prefs.edit().remove(apiKeyPrefKey(vendorId)).apply()
    }

    // ===== Base URL 覆盖 =====

    /** 自定义 base URL(给 MiMo 等允许换节点的服务)。null = 用引擎默认值。 */
    fun getBaseUrl(vendorId: String): String? =
        prefs.getString(baseUrlPrefKey(vendorId), null)?.takeIf { it.isNotBlank() }

    fun setBaseUrl(vendorId: String, url: String) {
        val edit = prefs.edit()
        if (url.isBlank()) {
            edit.remove(baseUrlPrefKey(vendorId))
        } else {
            edit.putString(baseUrlPrefKey(vendorId), url)
        }
        edit.apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "tts_secure_prefs"
        private const val MASTER_KEY_ALIAS = "tts_master_key"

        private fun apiKeyPrefKey(vendorId: String) = "api_key_$vendorId"
        private fun baseUrlPrefKey(vendorId: String) = "base_url_$vendorId"
    }
}
