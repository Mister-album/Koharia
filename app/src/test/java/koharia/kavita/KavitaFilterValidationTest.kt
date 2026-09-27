package koharia.kavita

import koharia.kavita.ui.kavitaFilterChoice
import koharia.kavita.ui.validKavitaFilterValue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KavitaFilterValidationTest {
    @Test fun languageChoicesUseIsoCodesAndEnumsKeepZeroValues() {
        assertEquals(
            "zh" to "Chinese",
            kavitaFilterChoice(Json.parseToJsonElement("""{"isoCode":"zh","title":"Chinese"}""")),
        )
        assertEquals(
            "0" to "Unknown",
            kavitaFilterChoice(Json.parseToJsonElement("""{"value":0,"title":"Unknown"}""")),
        )
    }

    @Test fun rejectsValuesThatTheServerCannotInterpret() {
        assertFalse(validKavitaFilterValue(5, "NaN"))
        assertFalse(validKavitaFilterValue(5, "8"))
        assertFalse(validKavitaFilterValue(20, "101"))
        assertFalse(validKavitaFilterValue(27, "2026-02-30"))
        assertFalse(validKavitaFilterValue(33, "-1 GB"))
        assertTrue(validKavitaFilterValue(27, "2026-09-26"))
        assertTrue(validKavitaFilterValue(33, "100 MB"))
        assertTrue(validKavitaFilterValue(5, "4.5"))
        assertTrue(validKavitaFilterValue(17, "2,3"))
    }
}
