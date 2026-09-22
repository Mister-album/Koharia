package koharia.source.smanga

import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionAddressRouter
import koharia.smanga.SmangaApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import tachiyomi.core.common.preference.Preference

class SmangaPreferences(connectionId: Long) {
    private val preferences = sourcePreferences("source_$connectionId")

    val address: String get() = preferences.getString("address", "").orEmpty()
    val internalAddress: String
        get() = preferences.getString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, "").orEmpty()
    val username: String get() = preferences.getString(Preference.privateKey("username"), "").orEmpty()
    val password: String get() = preferences.getString(Preference.privateKey("password"), "").orEmpty()
    val accountKey: String
        get() = Json.encodeToString(
            listOf(address, username, preferences.getLong("account_id", 0).toString()),
        ).encodeUtf8().sha256().hex()

    var mediaId: Long
        // Older builds saved the first library automatically; the new selection defaults to All (0).
        get() = preferences.getLong(scoped("media_filter_id"), 0)
        set(value) {
            require(value >= 0)
            preferences.edit().putLong(scoped("media_filter_id"), value).apply()
        }

    var order: String
        get() = preferences.getString(scoped("order"), "mangaName asc").orEmpty()
        set(value) {
            preferences.edit().putString(scoped("order"), value).apply()
        }

    var displayMode: Int
        get() = preferences.getInt(scoped("display_mode"), 0)
        set(value) {
            require(value in 0..3)
            preferences.edit().putInt(scoped("display_mode"), value).apply()
        }

    fun save(
        address: String,
        username: String,
        password: String,
        accountId: Long,
        internalAddress: String = this.internalAddress,
    ) {
        require(accountId > 0)
        check(
            preferences.edit()
                .putString("address", SmangaApi.normalizeBase(address).toString())
                .putString(
                    ConnectionAddressRouter.INTERNAL_ADDRESS_KEY,
                    internalAddress.takeIf {
                        it.isNotBlank()
                    }?.let { SmangaApi.normalizeBase(it).toString() }.orEmpty(),
                )
                .putString(Preference.privateKey("username"), username.trim())
                .putString(Preference.privateKey("password"), password)
                .putLong("account_id", accountId)
                .commit(),
        )
    }

    private fun scoped(key: String) = "account_${accountKey}_$key"
}
