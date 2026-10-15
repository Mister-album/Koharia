package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import koharia.connection.LibraryConnectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Reads ownership from the backup itself; a live ID collision is never evidence of provider identity. */
internal object BackupConnectionInventory {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(backup: Backup): List<BackupConnection> {
        val current = backup.backupPreferences.filter { it.key == "connection_profiles" }.flatMap { preference ->
            (preference.value as StringSetPreferenceValue).value.map {
                json.decodeFromString<LibraryConnectionProfile>(it).let { profile ->
                    BackupConnection(profile.id, profile.providerId, profile.name)
                }
            }
        }
        val legacy = backup.backupPreferences.filter { it.key == "komga_server_profiles" }.flatMap { preference ->
            (preference.value as StringSetPreferenceValue).value.map {
                json.decodeFromString<LegacyProfile>(it).let { profile ->
                    BackupConnection(profile.id, "komga", profile.name)
                }
            }
        }
        val authoritative = if (backup.backupConnections.isNotEmpty()) {
            backup.backupConnections
        } else {
            current.ifEmpty {
                legacy.ifEmpty { inferLegacyProviders(backup) }
            }
        }
        require(authoritative.all { it.id > 0 && it.providerId.isNotBlank() && it.name.isNotBlank() }) {
            "Invalid backup connection inventory"
        }
        require(authoritative.map { it.id }.distinct().size == authoritative.size) {
            "Duplicate backup connection identity"
        }
        if (backup.backupConnections.isNotEmpty()) {
            val byId = authoritative.associateBy { it.id }
            require(
                current.all { entry ->
                    byId[entry.id]?.let { it.providerId == entry.providerId && it.name == entry.name } == true
                },
            ) { "Conflicting backup connection inventories" }
        }
        BackupKomgaReadStatusIntents.validate(backup.backupKomgaReadStatus, authoritative)
        BackupKomgaProgressJournal.validate(backup, authoritative)
        return authoritative
    }

    /** Legacy exports could omit inventory and settings; use their provider format, never live profiles. */
    private fun inferLegacyProviders(backup: Backup): List<BackupConnection> {
        val settings = backup.backupSourcePreferences.associateBy {
            it.sourceKey.removePrefix("source_").toLongOrNull()
        }
        val names = backup.backupSources.associate { it.sourceId to it.name }
        return (settings.keys.filterNotNull() + backup.backupManga.map { it.source }).distinct().mapNotNull { id ->
            val values = settings[id]?.prefs.orEmpty().associate { it.key to it.value }
            val candidates = buildSet {
                if ("local_library_config" in values || "network_storage_configuration" in values) add("local-folder")
                if ("kavita_account" in values || "kavita_version" in values) add("kavita")
                if ("account_id" in values) add("smanga")
                if ("auth_mode" in values) add("suwayomi")
                if ("archive_open_mode" in values || "group_collections" in values) add("lanraragi")
                if ("Address" in values || "Koharia server profile name" in values) add("komga")
                backup.backupManga.filter { it.source == id }.forEach { manga ->
                    if (isLegacyKomgaResource(manga.url)) add("komga")
                    listOf("lanraragi", "kavita", "smanga", "suwayomi").forEach { provider ->
                        if (manga.url.startsWith("/$provider/$id/")) add(provider)
                    }
                    if (manga.url.startsWith("koharia-local-v1://$id/") ||
                        manga.url.startsWith("koharia-local-v2://$id/")
                    ) {
                        add("local-folder")
                    }
                }
            }
            require(candidates.size <= 1) { "Conflicting legacy backup provider formats" }
            val provider = candidates.singleOrNull() ?: return@mapNotNull null
            val name = names[id]?.takeIf { it.isNotBlank() }
                ?: (values["Koharia server profile name"] as? StringPreferenceValue)?.value?.takeIf { it.isNotBlank() }
                ?: provider
            BackupConnection(id, provider, name, writesPaused = true)
        }
    }

    private fun isLegacyKomgaResource(value: String): Boolean {
        // Komga stored absolute resource URLs, optionally under a reverse-proxy base path.
        val path = value.toHttpUrlOrNull()?.pathSegments?.takeLast(4) ?: return false
        return path.size == 4 && path[0] == "api" && path[1] == "v1" &&
            path[2] in setOf("series", "books", "readlists") && path[3].isNotBlank() && '/' !in path[3]
    }

    @Serializable
    private data class LegacyProfile(val id: Long, val name: String)
}
