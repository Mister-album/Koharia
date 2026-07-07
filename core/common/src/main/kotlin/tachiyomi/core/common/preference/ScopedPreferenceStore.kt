package tachiyomi.core.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

data class PreferenceScope(
    val prefix: String,
    val allowLegacyFallback: Boolean,
)

interface PreferenceScopeProvider {
    fun currentScope(): PreferenceScope

    fun scopeChanges(): Flow<PreferenceScope>
}

class ScopedPreferenceStore(
    private val preferenceStore: PreferenceStore,
    private val scopeProvider: PreferenceScopeProvider,
) : PreferenceStore {

    override fun getString(key: String, defaultValue: String): Preference<String> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getString(scopedKey, defaultValue)
        }
    }

    override fun getLong(key: String, defaultValue: Long): Preference<Long> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getLong(scopedKey, defaultValue)
        }
    }

    override fun getInt(key: String, defaultValue: Int): Preference<Int> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getInt(scopedKey, defaultValue)
        }
    }

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getFloat(scopedKey, defaultValue)
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getBoolean(scopedKey, defaultValue)
        }
    }

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getStringSet(scopedKey, defaultValue)
        }
    }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getObjectFromString(scopedKey, defaultValue, serializer, deserializer)
        }
    }

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> {
        return ScopedPreference(key, defaultValue, scopeProvider) { scopedKey ->
            preferenceStore.getObjectFromInt(scopedKey, defaultValue, serializer, deserializer)
        }
    }

    override fun getAll(): Map<String, *> {
        val prefix = scopeProvider.currentScope().prefix
        return preferenceStore.getAll()
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
    }

    private class ScopedPreference<T>(
        private val key: String,
        private val defaultValue: T,
        private val scopeProvider: PreferenceScopeProvider,
        private val factory: (String) -> Preference<T>,
    ) : Preference<T> {

        override fun key(): String = key

        override fun get(): T {
            val scope = scopeProvider.currentScope()
            val scopedPreference = scopedPreference(scope)
            return when {
                scopedPreference.isSet() -> scopedPreference.get()
                scope.allowLegacyFallback -> legacyPreference().get()
                else -> scopedPreference.get()
            }
        }

        override fun set(value: T) {
            val scope = scopeProvider.currentScope()
            logcat(LogPriority.DEBUG) {
                "Scoped preference write: key=$key, scope=${scope.prefix}, value=$value"
            }
            scopedPreference(scope).set(value)
        }

        override fun isSet(): Boolean {
            val scope = scopeProvider.currentScope()
            return scopedPreference(scope).isSet() || (scope.allowLegacyFallback && legacyPreference().isSet())
        }

        override fun delete() {
            val scope = scopeProvider.currentScope()
            logcat(LogPriority.DEBUG) {
                "Scoped preference delete: key=$key, scope=${scope.prefix}, allowLegacyFallback=${scope.allowLegacyFallback}"
            }
            scopedPreference(scope).delete()
            if (scope.allowLegacyFallback) {
                legacyPreference().delete()
            }
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> {
            return scopeProvider.scopeChanges()
                .flatMapLatest { scope ->
                    val scopedChanges = scopedPreference(scope).changes()
                    if (scope.allowLegacyFallback) {
                        merge(scopedChanges, legacyPreference().changes())
                    } else {
                        scopedChanges
                    }
                }
                .onStart { emit(get()) }
                .map { get() }
                .distinctUntilChanged()
                .conflate()
        }

        override fun stateIn(scope: CoroutineScope): StateFlow<T> {
            return changes().stateIn(scope, SharingStarted.Eagerly, get())
        }

        private fun scopedPreference(scope: PreferenceScope): Preference<T> {
            return factory(scope.prefix + key)
        }

        private fun legacyPreference(): Preference<T> {
            return factory(key)
        }
    }
}
