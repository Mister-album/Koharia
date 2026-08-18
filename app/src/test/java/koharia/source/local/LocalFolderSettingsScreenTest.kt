package koharia.source.local

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

class LocalFolderSettingsScreenTest {

    @Test
    fun `screen can be serialized while document picker is open`() {
        val screen = LocalFolderSettingsScreen(
            sourceId = 42L,
            profileName = "Local library",
            titleOverride = "Edit local library",
        )

        assertDoesNotThrow {
            ObjectOutputStream(ByteArrayOutputStream()).use { output ->
                output.writeObject(screen)
            }
        }
    }
}
