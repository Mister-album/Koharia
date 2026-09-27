package koharia.kavita

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object KavitaChapterTitle {
    private val token = Regex("""\{([^{}]+)\}""")
    private val fields = mapOf(
        "volume" to "kavitaVolume",
        "chapter" to "kavitaRange",
        "title" to "kavitaTitle",
        "filename" to "kavitaFilename",
    )

    fun valid(template: String): Boolean = template.length <= 300 &&
        token.findAll(template).all { it.groupValues[1] in fields } &&
        token.replace(template, "").none { it == '{' || it == '}' }

    fun render(template: String, memo: JsonObject): String? {
        if (template.isBlank() || !valid(template) || "kavitaTitle" !in memo) return null
        return token.replace(template) { match ->
            memo[fields.getValue(match.groupValues[1])]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.trim().takeIf(String::isNotBlank)
    }
}
