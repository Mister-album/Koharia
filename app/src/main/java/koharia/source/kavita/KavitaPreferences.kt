package koharia.source.kavita

import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionShelfFilterPersistence
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaAccountIdentity
import koharia.kavita.KavitaCapabilities
import koharia.kavita.KavitaEndpoint
import koharia.kavita.KavitaVersion
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import tachiyomi.core.common.preference.Preference
import java.util.UUID

class KavitaPreferences(connectionId: Long) {
    private val store = sourcePreferences("source_$connectionId")
    val address get() = store.getString("address", "").orEmpty()
    var internalAddress: String
        get() = store.getString("internal_address", "").orEmpty()
        set(value) {
            store.edit().putString("internal_address", value).apply()
        }
    val key get() = store.getString(Preference.privateKey("kavita_key"), "").orEmpty()
    val principal get() = store.getString(Preference.privateKey("kavita_principal"), "").orEmpty()
    val accountKey get() = store.getString("kavita_account", "").orEmpty()
    val identity: KavitaAccountIdentity? get() = store.getString(
        Preference.privateKey("kavita_identity"),
        null,
    )?.let { runCatching { Json.decodeFromString<KavitaAccountIdentity>(it) }.getOrNull() }
    val capabilities get() = KavitaCapabilities(
        KavitaVersion.parse(store.getString("kavita_version", "").orEmpty()) ?: KavitaVersion(0, 8),
        store.getStringSet("kavita_roles", emptySet()).orEmpty(),
    )
    var mediaId: Long
        get() = store.getLong("$accountKey::library", 0)
        set(value) {
            store.edit().putLong("$accountKey::library", value).apply()
        }
    var chapterTitleTemplate: String
        get() = store.getString("$accountKey::chapter_title_template", "").orEmpty()
        set(value) {
            store.edit().putString("$accountKey::chapter_title_template", value).apply()
        }
    var order: String
        get() = store.getString("$accountKey::order", DEFAULT_ORDER).orEmpty()
        set(value) {
            store.edit().putString("$accountKey::order", value).apply()
        }

    /**
     * Whether the shelf filters and sort survive leaving the shelf. Off by default, matching the
     * library's own persistent-filtering option: an opt-in store, not an always-on one.
     */
    var persistentFilters: Boolean
        get() = store.getBoolean("$accountKey::persistent_filters", false)
        set(value) {
            store.edit().putBoolean("$accountKey::persistent_filters", value).apply()
        }

    /** The sort to open the shelf with; the stored value only counts while persistence is on. */
    fun initialOrder(): String = ConnectionShelfFilterPersistence.initialOrder(persistentFilters, order, DEFAULT_ORDER)

    /** The stored filter, including its sort, kept only while persistence is on. */
    var savedFilter: String?
        get() = store.getString("$accountKey::saved_filter", null)
            ?.takeIf { ConnectionShelfFilterPersistence.shouldStore(persistentFilters) }
        set(value) {
            store.edit().putString("$accountKey::saved_filter", value).apply()
        }

    /**
     * The filter to open the shelf with. A filter carried by the route wins, because it describes
     * what the reader just navigated to; otherwise the opt-in store supplies one.
     */
    fun initialFilter(routeFilterJson: String?): koharia.kavita.KavitaFilter? {
        val json = routeFilterJson ?: savedFilter ?: return null
        return runCatching { Json.decodeFromString<koharia.kavita.KavitaFilter>(json) }.getOrNull()
    }

    /** Writes the confirmed filter and its sort, or drops the stored ones when persistence is off. */
    fun commitFilter(filterJson: String?, order: String, persistent: Boolean) {
        if (ConnectionShelfFilterPersistence.shouldStore(persistent)) {
            this.order = order
            savedFilter = filterJson
        } else if (ConnectionShelfFilterPersistence.shouldClear(persistent)) {
            store.edit().remove("$accountKey::order").remove("$accountKey::saved_filter").apply()
        }
    }

    fun save(address: String, key: String, account: KavitaAccount, confirmSameServer: Boolean = false) {
        require(account.principal.isNotBlank() && key.isNotBlank())
        val previous = identity ?: principal.takeIf(String::isNotBlank)?.let { KavitaAccountIdentity(username = it) }
        val newAddress = KavitaEndpoint.parse(address).base.toString()
        val retain = previous != null && previous.matches(account.identity) &&
            (previous.sameServerVerified(account.identity) || this.address == newAddress || confirmSameServer)
        val binding = account.identity.retainingKnownFields(previous.takeIf { retain })
        val stableUser = binding.userId.takeIf { it > 0 }?.toString() ?: binding.username
        val identityKey = "identity_v2_" +
            "${binding.installId}/${binding.createdUtc}/$stableUser".encodeUtf8().sha256().hex()
        // Never reuse a weak identity at another address without an explicit user decision.
        val stable = binding.installId.isNotBlank() || binding.createdUtc.isNotBlank()
        val identity = if (retain && accountKey.isNotBlank()) {
            accountKey
        } else {
            (if (stable) store.getString(identityKey, null) else null) ?: UUID.randomUUID().toString()
        }
        store.edit()
            .putString("address", KavitaEndpoint.parse(address).base.toString())
            .putString(Preference.privateKey("kavita_key"), key)
            .putString(Preference.privateKey("kavita_principal"), account.principal)
            .putString(Preference.privateKey("kavita_identity"), Json.encodeToString(binding))
            .putString("kavita_account", identity)
            .putString(identityKey, identity)
            .putString("kavita_version", account.kavitaVersion)
            .putStringSet("kavita_roles", account.roles.toSet())
            .apply()
    }

    companion object {
        const val DEFAULT_ORDER = "1 asc"
    }
}
