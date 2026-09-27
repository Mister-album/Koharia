package koharia.kavita.ui

import koharia.kavita.textValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun kavitaFilterChoice(element: JsonElement): Pair<String, String>? {
    if (element !is JsonObject) {
        val value = (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        return value to value
    }
    val id = listOf("isoCode", "id", "value").firstNotNullOfOrNull {
        (element[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    } ?: return null
    return id to element.textValue("title").ifBlank { element.textValue("name") }.ifBlank { id }
}

internal fun validKavitaFilterValue(field: Int, value: String): Boolean = when (field) {
    5 -> value.toFloatOrNull()?.let { it.isFinite() && it in 0f..5f } == true
    20 -> value.toFloatOrNull()?.let { it.isFinite() && it in 0f..100f } == true
    22, 23, 32 -> value.toIntOrNull()?.let { it >= 0 } == true
    28 -> value.toFloatOrNull()?.let { it.isFinite() && it in 0f..100f } == true
    27 -> runCatching { java.time.LocalDate.parse(value) }.isSuccess
    33 -> Regex("""(?i)\d+(\.\d+)?\s*(B|KB|MB|GB|TB)?""").matches(value.trim())
    26, 34 -> value in listOf("true", "false")
    else -> value.isNotBlank()
}
