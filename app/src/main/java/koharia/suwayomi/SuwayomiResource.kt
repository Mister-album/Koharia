package koharia.suwayomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SuwayomiResourceState<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: Throwable? = null,
) {
    val loaded: Boolean get() = value != null
}

internal data class SuwayomiSnapshot<T>(val value: T, val valid: Boolean)

/** One session owns each resource; successful empty values are snapshots too. */
class SuwayomiResource<T> internal constructor(
    private val checkSession: () -> Unit,
    private val fetch: suspend () -> T,
    private val read: suspend () -> SuwayomiSnapshot<T>? = { null },
    private val write: suspend (T, Boolean) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SuwayomiResourceState<T>())
    val state = mutableState.asStateFlow()
    private var hydrated = false
    private var valid = false

    private suspend fun hydrate() {
        checkSession()
        if (hydrated) return
        val stored = read()
        checkSession()
        if (stored != null) {
            valid = stored.valid
            mutableState.update { it.copy(value = stored.value) }
        }
        hydrated = true
    }

    suspend fun load(refresh: Boolean = false, fetch: suspend () -> T = this.fetch): T = mutex.withLock {
        try {
            hydrate()
            if (!refresh && valid) return@withLock checkNotNull(state.value.value)
            mutableState.update { it.copy(loading = true, error = null) }
            val value = fetch()
            currentCoroutineContext().ensureActive()
            checkSession()
            write(value, true)
            checkSession()
            valid = true
            mutableState.value = SuwayomiResourceState(value)
            value
        } catch (error: Exception) {
            mutableState.update { it.copy(loading = false, error = error.takeUnless { it is CancellationException }) }
            throw error
        }
    }

    suspend fun invalidate() = mutex.withLock {
        hydrate()
        valid = false
        state.value.value?.let { write(it, false) }
        checkSession()
    }

    suspend fun cached(): T? = mutex.withLock {
        hydrate()
        state.value.value
    }

    /** Serializes a confirmed write with reads so an older response cannot undo it. */
    internal suspend fun mutate(fresh: Boolean = false, block: suspend (T?) -> T?) = mutex.withLock {
        hydrate()
        val value = block(state.value.value)
        currentCoroutineContext().ensureActive()
        checkSession()
        if (value != null) {
            write(value, fresh || valid)
            checkSession()
            if (fresh) valid = true
            mutableState.update { it.copy(value = value, error = null) }
        }
    }

    internal suspend fun accept(value: T) = mutex.withLock {
        checkSession()
        write(value, true)
        checkSession()
        hydrated = true
        valid = true
        mutableState.value = SuwayomiResourceState(value)
    }
}
