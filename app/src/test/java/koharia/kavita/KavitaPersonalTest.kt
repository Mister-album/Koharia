package koharia.kavita

import koharia.epub.settings.EpubLayoutPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KavitaPersonalTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test fun highlightColorsKeepServerSlotsAndRejectInvalidComponents() {
        val colors = kavitaHighlightColors(
            json.parseToJsonElement(
                """{"bookReaderHighlightSlots":[{"slotNumber":2,"color":{"r":12,"g":34,"b":56,"a":0.7}},
            {"slotNumber":0,"color":{"r":-1,"g":0,"b":0,"a":1}},
            {"slotNumber":8,"color":{"r":0,"g":0,"b":0,"a":1}}]}""",
            ).jsonObject,
        )
        assertEquals(mapOf(2 to "rgba(12,34,56,0.7)"), colors)
    }

    @Test fun thirdPartyCredentialsNeverEnterThePersistedStatusDto() {
        val status = json.decodeFromString<KavitaScrobbleStatus>(
            """{"provider":1,"userName":"reader","authenticationToken":"test-secret-a","refreshToken":"test-secret-b",
                "settings":{"progressScrobbling":true}}""",
        )
        val persisted = json.encodeToString(status)
        assertFalse(persisted.contains("test-secret"))
        assertFalse(persisted.contains("Token"))
        assertEquals(true, status.settings.progressScrobbling)
    }

    @Test fun importsOnlyCompatibleUnitsAndPreservesUnsupportedSettings() {
        val profile = KavitaProfileImport.from(
            json.parseToJsonElement(
                """{"bookReaderFontSize":125,"bookReaderLineSpacing":180,"bookReaderReadingDirection":1,
                "bookReaderLayoutMode":2,"bookReaderMargin":10,"bookReaderThemeName":"custom"}""",
            ).jsonObject,
        )
        assertEquals(1.25f, profile.fontScale)
        assertEquals(1.8f, profile.lineHeight)
        assertEquals(EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT, profile.direction)
        assertNull(profile.readingMode)
        val unsupported = KavitaProfileImport.from(
            json.parseToJsonElement(
                """{"bookReaderFontSize":500,"bookReaderLineSpacing":0,"bookReaderReadingDirection":9}""",
            ).jsonObject,
        )
        assertEquals(true, unsupported.isEmpty)
    }
}
