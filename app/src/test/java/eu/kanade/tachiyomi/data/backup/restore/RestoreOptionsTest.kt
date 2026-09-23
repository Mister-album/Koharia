package eu.kanade.tachiyomi.data.backup.restore

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RestoreOptionsTest {

    @Test
    fun `current options round trip with connections disabled`() {
        val options = RestoreOptions(
            libraryEntries = false,
            categories = true,
            appSettings = true,
            connectionSettings = false,
        )

        assertArrayEquals(booleanArrayOf(false, true, true, false), options.asBooleanArray())
        assertEquals(options, RestoreOptions.fromBooleanArray(options.asBooleanArray()))
    }

    @Test
    fun `current options round trip with connections enabled`() {
        val options = RestoreOptions(
            libraryEntries = true,
            categories = false,
            appSettings = false,
            connectionSettings = true,
        )

        assertEquals(options, RestoreOptions.fromBooleanArray(options.asBooleanArray()))
    }

    @Test
    fun `five item extension layout reads source setting after extension setting`() {
        val oldOptions = booleanArrayOf(false, true, false, false, true)

        assertEquals(
            RestoreOptions(
                libraryEntries = false,
                categories = true,
                appSettings = false,
                connectionSettings = true,
            ),
            RestoreOptions.fromBooleanArray(oldOptions),
        )
        assertEquals(
            RestoreOptions.fromBooleanArray(oldOptions).copy(connectionSettings = false),
            RestoreOptions.fromBooleanArray(booleanArrayOf(false, true, false, true, false)),
        )
    }

    @Test
    fun `three item layout uses old library choice for categories`() {
        val oldOptions = booleanArrayOf(false, true, false)

        assertEquals(
            RestoreOptions(
                libraryEntries = false,
                categories = false,
                appSettings = true,
                connectionSettings = false,
            ),
            RestoreOptions.fromBooleanArray(oldOptions),
        )
    }
}
