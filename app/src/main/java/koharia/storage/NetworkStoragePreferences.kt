package koharia.storage

import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference

@Serializable
data class NetworkStorageConfiguration(
    val mode: LibraryStorageMode = LibraryStorageMode.LOCAL,
    val address: String = "",
    val internalAddress: String = "",
    val domain: String = "",
    val rootIdentity: String = "",
    val verifiedInternal: Boolean = false,
    val accountIdentity: String = "",
    val needsValidation: Boolean = false,
    val persistentIdentity: Boolean = true,
)

class NetworkStoragePreferences(val connectionId: Long) {
    private val preferences = sourcePreferences("source_$connectionId")
    private val json = Json { ignoreUnknownKeys = true }
    val configuration get() = preferences.getString("network_storage_configuration", null)
        ?.let { json.decodeFromString<NetworkStorageConfiguration>(it) } ?: NetworkStorageConfiguration()
    val username get() = preferences.getString(Preference.privateKey("storage_username"), "").orEmpty()
    val password get() = preferences.getString(Preference.privateKey("storage_password"), "").orEmpty()
    val account get() = configuration.accountIdentity.ifBlank { account(configuration, username) }
    fun save(configuration: NetworkStorageConfiguration, username: String, password: String) {
        check(
            preferences.edit().putString(
                "network_storage_configuration",
                json.encodeToString(
                    configuration.copy(
                        accountIdentity = configuration.accountIdentity.ifBlank {
                            account(configuration, username)
                        },
                    ),
                ),
            )
                .putString(Preference.privateKey("storage_username"), username)
                .putString(Preference.privateKey("storage_password"), password).commit(),
        )
    }
    companion object {
        fun account(configuration: NetworkStorageConfiguration, username: String): String =
            storageDigest("${configuration.mode}\n${configuration.rootIdentity}\n${configuration.domain}\n$username")
    }
}
