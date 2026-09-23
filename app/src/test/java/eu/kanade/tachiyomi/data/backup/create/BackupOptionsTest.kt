package eu.kanade.tachiyomi.data.backup.create

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupOptionsTest {

    @Test
    fun `current options round trip with connections enabled and private settings disabled`() {
        val options = BackupOptions(
            libraryEntries = false,
            categories = true,
            chapters = false,
            tracking = true,
            history = false,
            readEntries = true,
            appSettings = false,
            connectionSettings = true,
            privateSettings = false,
        )

        assertArrayEquals(
            booleanArrayOf(false, true, false, true, false, true, false, true, false),
            options.asBooleanArray(),
        )
        assertEquals(options, BackupOptions.fromBooleanArray(options.asBooleanArray()))
    }

    @Test
    fun `current options round trip with connections disabled and private settings enabled`() {
        val options = BackupOptions(
            libraryEntries = true,
            categories = false,
            chapters = true,
            tracking = false,
            history = true,
            readEntries = false,
            appSettings = true,
            connectionSettings = false,
            privateSettings = true,
        )

        assertEquals(options, BackupOptions.fromBooleanArray(options.asBooleanArray()))
    }

    @Test
    fun `ten item extension layout reads source and private settings after extension setting`() {
        val oldOptions = booleanArrayOf(true, false, true, false, true, false, true, false, true, false)

        assertEquals(
            BackupOptions(
                libraryEntries = true,
                categories = false,
                chapters = true,
                tracking = false,
                history = true,
                readEntries = false,
                appSettings = true,
                connectionSettings = true,
                privateSettings = false,
            ),
            BackupOptions.fromBooleanArray(oldOptions),
        )
        assertEquals(
            BackupOptions.fromBooleanArray(oldOptions).copy(connectionSettings = false, privateSettings = true),
            BackupOptions.fromBooleanArray(
                booleanArrayOf(true, false, true, false, true, false, true, true, false, true),
            ),
        )
    }

    @Test
    fun `eight item layout predating read entries retains source and private choices`() {
        val oldOptions = booleanArrayOf(false, true, false, true, false, true, false, true)

        assertEquals(
            BackupOptions(
                libraryEntries = false,
                categories = true,
                chapters = false,
                tracking = true,
                history = false,
                readEntries = true,
                appSettings = true,
                connectionSettings = false,
                privateSettings = true,
            ),
            BackupOptions.fromBooleanArray(oldOptions),
        )
    }
}
