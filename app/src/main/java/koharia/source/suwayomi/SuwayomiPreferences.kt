package koharia.source.suwayomi

import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionAddressRouter
import koharia.connection.ConnectionShelfFilterPersistence
import koharia.suwayomi.SuwayomiApi
import koharia.suwayomi.SuwayomiAuthMode
import koharia.suwayomi.SuwayomiIdentity
import koharia.suwayomi.SuwayomiLibraryFilters
import koharia.suwayomi.SuwayomiLibraryFiltersCodec
import tachiyomi.core.common.preference.Preference

class SuwayomiPreferences(connectionId: Long) {
    private val preferences = sourcePreferences("source_$connectionId")
    val address get() = preferences.getString("address", "").orEmpty()
    val internalAddress get() = preferences.getString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, "").orEmpty()
    val mode get() = runCatching {
        SuwayomiAuthMode.valueOf(preferences.getString("auth_mode", "NONE").orEmpty())
    }.getOrDefault(SuwayomiAuthMode.NONE)
    val username get() = preferences.getString(Preference.privateKey("username"), "").orEmpty()
    val password get() = preferences.getString(Preference.privateKey("password"), "").orEmpty()
    val account get() = SuwayomiIdentity.account(address, mode, username)

    /** Null follows all current and future categories; an empty set explicitly hides all. */
    var visibleCategoryIds: Set<Int>?
        get() = visibleCategoryIds(account)
        set(value) {
            preferences.edit().putStringSet(
                "account_${account}_visible_categories",
                value?.map { it.toString() }?.toSet(),
            ).apply()
        }

    fun visibleCategoryIds(account: String): Set<Int>? =
        preferences.getStringSet("account_${account}_visible_categories", null)
            ?.mapNotNull { it.toIntOrNull() }?.toSet()

    var categoryId: Int
        get() = preferences.getInt("account_${account}_category", -1)
        set(value) {
            preferences.edit().putInt("account_${account}_category", value).apply()
        }
    var order: String
        get() = preferences.getString("account_${account}_order", "title asc").orEmpty()
        set(value) {
            preferences.edit().putString("account_${account}_order", value).apply()
        }

    /**
     * Whether the shelf filters and sort survive leaving the shelf. Off by default, matching the
     * library's own persistent-filtering option: an opt-in store, not an always-on one.
     */
    var persistentFilters: Boolean
        get() = preferences.getBoolean("account_${account}_persistent_filters", false)
        set(value) {
            preferences.edit().putBoolean("account_${account}_persistent_filters", value).apply()
        }

    /**
     * The shelf filters, stored per account as a compact string so a new condition does not need a
     * schema change. An unreadable value falls back to no filtering rather than failing the shelf.
     */
    var libraryFilters: SuwayomiLibraryFilters
        get() = preferences.getString("account_${account}_library_filters", "")
            .orEmpty()
            .takeIf(String::isNotEmpty)
            ?.let { stored -> runCatching { SuwayomiLibraryFiltersCodec.decode(stored) }.getOrNull() }
            ?: SuwayomiLibraryFilters.NONE
        set(value) {
            preferences.edit()
                .putString("account_${account}_library_filters", SuwayomiLibraryFiltersCodec.encode(value))
                .apply()
        }

    /** Drops the stored sort and filters, used when persistence is turned off. */
    fun clearPersistentFilters() {
        preferences.edit()
            .remove("account_${account}_order")
            .remove("account_${account}_library_filters")
            .apply()
    }

    /** The sort to start the shelf with; stored value only counts while persistence is on. */
    fun initialShelfOrder(default: String): String =
        ConnectionShelfFilterPersistence.initialOrder(persistentFilters, order, default)

    /** The filters to start the shelf with; stored value only counts while persistence is on. */
    fun initialShelfFilters(): SuwayomiLibraryFilters =
        if (ConnectionShelfFilterPersistence.shouldStore(persistentFilters)) {
            libraryFilters
        } else {
            SuwayomiLibraryFilters.NONE
        }

    fun save(
        address: String,
        internalAddress: String,
        mode: SuwayomiAuthMode,
        username: String,
        password: String,
    ) {
        val internal = internalAddress.takeIf(String::isNotBlank)
            ?.let { SuwayomiApi.normalizeBase(it).toString() }.orEmpty()
        check(
            preferences.edit()
                .putString("address", SuwayomiApi.normalizeBase(address).toString())
                .putString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, internal)
                .putString("auth_mode", mode.name)
                .putString(Preference.privateKey("username"), username.trim())
                .putString(Preference.privateKey("password"), password)
                .commit(),
        )
    }
}
