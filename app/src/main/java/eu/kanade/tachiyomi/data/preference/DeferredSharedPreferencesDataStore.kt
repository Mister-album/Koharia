package eu.kanade.tachiyomi.data.preference

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore

class DeferredSharedPreferencesDataStore(
    private val prefs: SharedPreferences,
) : PreferenceDataStore() {

    private val cache = mutableMapOf<String, Any?>()

    var hasUnsavedChanges = false
        private set

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return cache[key] as? Boolean ?: prefs.getBoolean(key, defValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        if (getBoolean(key, false) != value || !cache.containsKey(key)) {
            cache[key] = value
            hasUnsavedChanges = true
        }
    }

    override fun getInt(key: String, defValue: Int): Int {
        return cache[key] as? Int ?: prefs.getInt(key, defValue)
    }

    override fun putInt(key: String, value: Int) {
        if (getInt(key, 0) != value || !cache.containsKey(key)) {
            cache[key] = value
            hasUnsavedChanges = true
        }
    }

    override fun getLong(key: String, defValue: Long): Long {
        return cache[key] as? Long ?: prefs.getLong(key, defValue)
    }

    override fun putLong(key: String, value: Long) {
        if (getLong(key, 0L) != value || !cache.containsKey(key)) {
            cache[key] = value
            hasUnsavedChanges = true
        }
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return cache[key] as? Float ?: prefs.getFloat(key, defValue)
    }

    override fun putFloat(key: String, value: Float) {
        if (getFloat(key, 0f) != value || !cache.containsKey(key)) {
            cache[key] = value
            hasUnsavedChanges = true
        }
    }

    override fun getString(key: String, defValue: String?): String? {
        return if (cache.containsKey(key)) {
            cache[key] as? String
        } else {
            prefs.getString(key, defValue)
        }
    }

    override fun putString(key: String, value: String?) {
        if (getString(key, null) != value || !cache.containsKey(key)) {
            cache[key] = value
            hasUnsavedChanges = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        return if (cache.containsKey(key)) {
            cache[key] as? MutableSet<String>
        } else {
            prefs.getStringSet(key, defValues)
        }
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) {
        // Deep copy the set to prevent external modifications bypassing the data store
        val copy = values?.toMutableSet()
        if (getStringSet(key, null) != copy || !cache.containsKey(key)) {
            cache[key] = copy
            hasUnsavedChanges = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun applyChanges() {
        if (!hasUnsavedChanges) return
        prefs.edit {
            cache.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Float -> putFloat(k, v)
                    is Long -> putLong(k, v)
                    is String -> putString(k, v)
                    is Set<*> -> putStringSet(k, v as Set<String>)
                }
            }
        }
        cache.clear()
        hasUnsavedChanges = false
    }
}
