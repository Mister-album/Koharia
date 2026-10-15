package koharia.kavita

import koharia.domain.kavita.KavitaAnnotationEntry
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Quarantine legacy rows too; older imports may have relied solely on a connection-wide pause. */
internal suspend fun KavitaRepository.quarantineForActivation(
    connectionId: Long,
    account: String,
    checkCurrent: () -> Unit,
) = quarantinePending(connectionId, account, ::holdKavitaOperation, ::holdKavitaAnnotation, checkCurrent)

internal fun holdKavitaOperation(entry: KavitaOperation): KavitaOperation {
    val flag = when {
        entry.key.startsWith("progress/") -> {
            val state = Json.decodeFromString<KavitaReadingState>(entry.payload)
            require(entry.key == "progress/${state.ref.chapterId}")
            "conflict"
        }
        entry.key.startsWith("bookmark/") -> {
            val state = Json.decodeFromString<KavitaBookmarkState>(entry.payload)
            require(entry.key == state.key)
            "needsConfirmation"
        }
        else -> error("Unrecognized pending Kavita operation")
    }
    val payload = holdPayload(entry.payload, flag)
    return if (payload == entry.payload) {
        entry
    } else {
        entry.copy(
            payload = payload,
            revision = Math.addExact(entry.revision, 1),
        )
    }
}

internal fun holdKavitaAnnotation(entry: KavitaAnnotationEntry): KavitaAnnotationEntry {
    val state = Json.decodeFromString<KavitaAnnotationState>(entry.payload)
    require(state.annotation.chapterId == entry.chapterId)
    val payload = holdPayload(holdPayload(entry.payload, "needsConfirmation"), "conflict")
    return if (payload == entry.payload) {
        entry
    } else {
        entry.copy(
            payload = payload,
            revision = Math.addExact(entry.revision, 1),
        )
    }
}

private fun holdPayload(payload: String, flag: String): String {
    val fields = Json.parseToJsonElement(payload).jsonObject
    if (fields[flag] == JsonPrimitive(true)) return payload
    return JsonObject(fields + (flag to JsonPrimitive(true))).toString()
}
