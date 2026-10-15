package eu.kanade.tachiyomi.data.track.komga

import android.content.Context
import androidx.work.Data
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Keep unverifiable tasks independently of WorkManager's pruning; never rebind them automatically. */
internal object KomgaHeldProgress {
    private const val STORE = "komga_held_page_progress"

    fun store(context: Context, id: UUID, input: Data, reason: String): Boolean {
        val record = buildJsonObject {
            put("schema", 2)
            put("reason", reason)
            put("heldAt", System.currentTimeMillis())
            put(
                "input",
                buildJsonObject {
                    input.keyValueMap.forEach { (key, value) ->
                        put(key, encodeValue(value))
                    }
                },
            )
            put(
                "inputTypes",
                buildJsonObject {
                    input.keyValueMap.forEach { (key, value) -> put(key, value?.javaClass?.name ?: "null") }
                },
            )
        }
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(id.toString(), record.toString()).commit()
    }

    private fun encodeValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Number -> JsonPrimitive(value)
        is Array<*> -> JsonArray(value.map(::encodeValue))
        else -> error("Unsupported WorkData type")
    }

    fun read(context: Context, id: UUID): String? =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(id.toString(), null)

    fun remove(context: Context, id: UUID) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(id.toString()).commit()
    }
}
