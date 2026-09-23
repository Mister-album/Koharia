package eu.kanade.tachiyomi.data.backup

import android.content.Context
import koharia.tts.KeystoreTtsSecretCodec
import java.util.UUID

/** Passwords never enter WorkManager input data. Only Keystore-wrapped values are persisted. */
internal object BackupPasswordStore {
    private const val PREFS = "backup_passwords"
    private const val AUTO = "automatic"
    private val codec = KeystoreTtsSecretCodec("koharia_backup_password_key")

    fun saveManual(context: Context, password: String): String {
        require(password.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val encrypted = checkNotNull(codec.encrypt(password)) { "Could not protect backup password" }
        check(preferences(context).edit().putString(id, encrypted).commit())
        return id
    }

    fun loadManual(context: Context, id: String): CharArray =
        checkNotNull(preferences(context).getString(id, null)?.let(codec::decrypt)) {
            "Could not read backup password"
        }.toCharArray()

    fun removeManual(context: Context, id: String) {
        preferences(context).edit().remove(id).apply()
    }

    fun setAutomatic(context: Context, password: String?) {
        val editor = preferences(context).edit()
        if (password.isNullOrEmpty()) {
            editor.remove(AUTO)
        } else {
            editor.putString(AUTO, checkNotNull(codec.encrypt(password)) { "Could not protect backup password" })
        }
        check(editor.commit())
    }

    fun hasAutomatic(context: Context): Boolean = preferences(context).contains(AUTO)

    fun loadAutomatic(context: Context): CharArray? {
        val stored = preferences(context).getString(AUTO, null) ?: return null
        return checkNotNull(codec.decrypt(stored)) { "Could not read automatic backup password" }.toCharArray()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
