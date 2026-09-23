package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Keeps SAF URI mappings out of WorkManager's size-limited input data. */
internal object RestoreDirectoryBindings {
    fun save(context: Context, bindings: Map<String, String>): String {
        val id = UUID.randomUUID().toString()
        file(context, id).writeText(Json.encodeToString(bindings))
        return id
    }

    fun load(context: Context, id: String): Map<String, String> =
        Json.decodeFromString(file(context, id).readText())

    fun remove(context: Context, id: String) {
        file(context, id).delete()
    }

    private fun file(context: Context, id: String): File {
        UUID.fromString(id)
        return File(context.noBackupFilesDir, "restore-directories-$id.json")
    }
}
