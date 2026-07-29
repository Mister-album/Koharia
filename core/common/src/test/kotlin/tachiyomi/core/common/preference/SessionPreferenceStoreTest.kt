package tachiyomi.core.common.preference

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SessionPreferenceStoreTest {

    @Test
    fun `disabled persistence keeps changes in session until enabled`() {
        val backingPreference = MutablePreference("value", 1, 0)
        val backingStore = mockk<PreferenceStore> {
            every { getInt("value", 0) } returns backingPreference
        }
        val store = SessionPreferenceStore(backingStore, persistChanges = false)
        val preference = store.getInt("value", 0)

        preference.set(2)
        assertEquals(2, preference.get())
        assertEquals(1, backingPreference.get())

        store.setPersistChanges(true)
        assertEquals(2, backingPreference.get())

        preference.set(3)
        assertEquals(3, backingPreference.get())
    }

    @Test
    fun `disabling persistence stops subsequent backing writes`() {
        val backingPreference = MutablePreference("value", 1, 0)
        val backingStore = mockk<PreferenceStore> {
            every { getInt("value", 0) } returns backingPreference
        }
        val store = SessionPreferenceStore(backingStore, persistChanges = true)
        val preference = store.getInt("value", 0)

        preference.set(2)
        store.setPersistChanges(false)
        preference.set(3)

        assertEquals(3, preference.get())
        assertEquals(2, backingPreference.get())
    }

    @Test
    fun `deletion remains transient and is flushed when persistence is enabled`() {
        val backingPreference = MutablePreference("value", 1, 0)
        val backingStore = mockk<PreferenceStore> {
            every { getInt("value", 0) } returns backingPreference
        }
        val store = SessionPreferenceStore(backingStore, persistChanges = false)
        val preference = store.getInt("value", 0)

        preference.delete()
        assertFalse(preference.isSet())
        assertEquals(1, backingPreference.get())

        store.setPersistChanges(true)
        assertFalse(backingPreference.isSet())
        assertEquals(0, backingPreference.get())
    }

    private class MutablePreference<T>(
        private val key: String,
        initialValue: T?,
        private val defaultValue: T,
    ) : Preference<T> {

        private var value = initialValue
        private val state = MutableStateFlow(get())

        override fun key(): String = key

        override fun get(): T = value ?: defaultValue

        override fun set(value: T) {
            this.value = value
            state.value = value
        }

        override fun isSet(): Boolean = value != null

        override fun delete() {
            value = null
            state.value = defaultValue
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = state

        override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
    }
}
