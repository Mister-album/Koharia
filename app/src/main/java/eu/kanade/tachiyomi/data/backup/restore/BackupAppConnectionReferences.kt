package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import koharia.connection.LibraryConnectionProfile
import koharia.connection.SharedConfigMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Remap dormant legacy settings too, without restoring another installation's clock into a live owner. */
internal object BackupAppConnectionReferences {
    private val scope = Regex("connection_(\\d+)::(.*)")
    private val clock = Regex("epub_reader_komga_server_timestamp_offset_minutes_(\\d+)")

    fun remap(
        preferences: List<BackupPreference>,
        targets: Map<Long, LibraryConnectionProfile>,
    ): List<BackupPreference> = preferences.mapNotNull { preference ->
        var key = SharedConfigMigration.normalizeKey(preference.key)
        val scoped = scope.matchEntire(key)
        val unscoped = key.substringAfterLast("::")
        // Learned clock evidence belongs to the original installation's configuration binding.
        if (unscoped.startsWith("epub_reader_komga_owner_timestamp_offset_minutes_")) return@mapNotNull null
        val clockOwner = clock.matchEntire(unscoped)?.groupValues?.get(1)?.toLong()
        if (clockOwner != null) {
            val target = targets[clockOwner] ?: return@mapNotNull null
            // A legacy cross-owner clock value must not be promoted to the shared clock store.
            if (scoped != null && scoped.groupValues[1].toLong() != clockOwner) return@mapNotNull null
            key = key.removeSuffix(unscoped) + "epub_reader_komga_server_timestamp_offset_minutes_${target.id}"
        }
        if (scoped != null) {
            val old = scoped.groupValues[1].toLong()
            targets[old]?.let { key = key.replaceFirst("connection_$old::", "connection_${it.id}::") }
        }
        val value = when (key) {
            "connection_profiles" -> {
                val serialized = preference.value as StringSetPreferenceValue
                StringSetPreferenceValue(
                    serialized.value.mapTo(linkedSetOf()) { entry ->
                        val fields = Json.parseToJsonElement(entry).jsonObject
                        val target = targets[fields.getValue("id").jsonPrimitive.long]
                        if (target == null) {
                            entry
                        } else {
                            JsonObject(
                                fields + mapOf(
                                    "id" to JsonPrimitive(target.id),
                                    "name" to JsonPrimitive(target.name),
                                    "providerId" to JsonPrimitive(target.providerId),
                                ),
                            ).toString()
                        }
                    },
                )
            }
            "connection_active_id" -> {
                val old = (preference.value as LongPreferenceValue).value
                LongPreferenceValue(targets[old]?.id ?: old)
            }
            else -> preference.value
        }
        preference.copy(key = key, value = value)
    }
}
