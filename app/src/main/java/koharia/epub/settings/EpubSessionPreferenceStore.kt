package koharia.epub.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Preference overlay used for reader-only setting changes that must not be persisted. */
internal class EpubSessionPreferenceStore(
    private val backingStore: PreferenceStore,
    persistChanges: Boolean,
) : PreferenceStore {

    private val preferences = mutableMapOf<String, SessionPreference<*>>()

    @Volatile
    private var persistChanges = persistChanges

    fun setPersistChanges(enabled: Boolean) {
        synchronized(preferences) {
            persistChanges = enabled
            if (enabled) {
                preferences.values.forEach { it.persist() }
            }
        }
    }

    override fun getString(key: String, defaultValue: String): Preference<String> =
        getOrCreate(key, defaultValue) { backingStore.getString(key, defaultValue) }

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        getOrCreate(key, defaultValue) { backingStore.getLong(key, defaultValue) }

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        getOrCreate(key, defaultValue) { backingStore.getInt(key, defaultValue) }

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        getOrCreate(key, defaultValue) { backingStore.getFloat(key, defaultValue) }

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        getOrCreate(key, defaultValue) { backingStore.getBoolean(key, defaultValue) }

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        getOrCreate(key, defaultValue) { backingStore.getStringSet(key, defaultValue) }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = getOrCreate(key, defaultValue) {
        backingStore.getObjectFromString(key, defaultValue, serializer, deserializer)
    }

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = getOrCreate(key, defaultValue) {
        backingStore.getObjectFromInt(key, defaultValue, serializer, deserializer)
    }

    override fun getAll(): Map<String, *> = backingStore.getAll() + preferences.mapValues { it.value.get() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getOrCreate(
        key: String,
        defaultValue: T,
        backingPreference: () -> Preference<T>,
    ): Preference<T> = synchronized(preferences) {
        preferences.getOrPut(key) {
            val backing = backingPreference()
            SessionPreference(
                key = key,
                initialValue = backing.get(),
                defaultValue = defaultValue,
                initiallySet = backing.isSet(),
                backingPreference = backing,
                shouldPersist = { persistChanges },
            )
        } as Preference<T>
    }

    private class SessionPreference<T>(
        private val key: String,
        initialValue: T,
        private val defaultValue: T,
        initiallySet: Boolean,
        private val backingPreference: Preference<T>,
        private val shouldPersist: () -> Boolean,
    ) : Preference<T> {

        private val state = MutableStateFlow(initialValue)

        @Volatile
        private var isSet = initiallySet

        override fun key(): String = key

        override fun get(): T = state.value

        override fun set(value: T) {
            isSet = true
            state.value = value
            if (shouldPersist()) backingPreference.set(value)
        }

        override fun isSet(): Boolean = isSet

        override fun delete() {
            isSet = false
            state.value = defaultValue
            if (shouldPersist()) backingPreference.delete()
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = state.asStateFlow()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> = state

        fun persist() {
            if (isSet) {
                backingPreference.set(state.value)
            } else {
                backingPreference.delete()
            }
        }
    }
}
