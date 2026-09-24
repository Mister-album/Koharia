package koharia.source.local

import koharia.connection.ConnectionChapterMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.chapter.model.Chapter

internal fun localChapterMemo(
    existing: Chapter?,
    modifiedAt: Long,
    sizeBytes: Long,
    fingerprint: String?,
): JsonObject {
    val oldFingerprint = existing?.memo?.get("localFingerprint")?.jsonPrimitive?.contentOrNull
    val oldSize = existing?.memo?.let(ConnectionChapterMetadata::sizeBytes)
    val unchanged = existing != null &&
        (modifiedAt <= 0L || existing.dateUpload == modifiedAt) &&
        (oldSize == null || oldSize == sizeBytes) &&
        (oldFingerprint == null || oldFingerprint == fingerprint)
    return buildJsonObject {
        existing?.memo?.forEach { (key, value) ->
            if (key != "pagesCount" || unchanged) put(key, value)
        }
        put("sizeBytes", sizeBytes)
        put("fileLastModified", modifiedAt)
        if (fingerprint != null) put("localFingerprint", fingerprint)
    }
}
