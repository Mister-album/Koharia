package koharia.source.komga

import koharia.komga.api.dto.LibraryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

enum class KomgaLibraryKind {
    COMIC,
    BOOK,
}

enum class KomgaLibraryScope {
    ALL,
    COMIC,
    BOOK,
}

@Serializable
data class KomgaLibraryClassification(
    val serverId: Long,
    val libraryId: String,
    val kind: KomgaLibraryKind,
)

@Serializable
data class KomgaLibrarySnapshot(
    val serverId: Long,
    val libraryId: String,
    val name: String,
)

data class KomgaClassifiedLibrary(
    val id: String,
    val name: String,
    val kind: KomgaLibraryKind,
)

class KomgaLibraryClassificationManager(
    preferenceStore: PreferenceStore,
    private val serverPreferences: KomgaServerPreferences,
    private val localConfigManager: KomgaLocalConfigManager,
    private val json: Json,
) {

    val enabled: Preference<Boolean> = preferenceStore.getBoolean(PREF_ENABLED, false)

    private val serializedClassifications = preferenceStore.getStringSet(PREF_CLASSIFICATIONS, emptySet())
    private val serializedSnapshots = preferenceStore.getStringSet(PREF_SNAPSHOTS, emptySet())

    fun classificationsChanges(serverId: Long): Flow<List<KomgaClassifiedLibrary>> {
        return combine(
            serializedClassifications.changes(),
            serializedSnapshots.changes(),
        ) { classifications, snapshots ->
            buildClassifiedLibraries(
                serverId = serverId,
                classifications = decodeClassifications(classifications),
                snapshots = decodeSnapshots(snapshots),
            )
        }.distinctUntilChanged()
    }

    fun getLibraries(serverId: Long): List<KomgaClassifiedLibrary> {
        return buildClassifiedLibraries(
            serverId = serverId,
            classifications = decodeClassifications(serializedClassifications.get()),
            snapshots = decodeSnapshots(serializedSnapshots.get()),
        )
    }

    fun libraryIds(serverId: Long, kind: KomgaLibraryKind): Set<String> {
        return getLibraries(serverId)
            .filter { it.kind == kind }
            .mapTo(linkedSetOf(), KomgaClassifiedLibrary::id)
    }

    fun updateLibraries(serverId: Long, libraries: List<LibraryDto>) {
        val existingSnapshots = decodeSnapshots(serializedSnapshots.get())
            .filterNot { it.serverId == serverId }
        val refreshedSnapshots = libraries.map { library ->
            KomgaLibrarySnapshot(
                serverId = serverId,
                libraryId = library.id,
                name = library.name,
            )
        }
        val updatedSnapshots = (existingSnapshots + refreshedSnapshots).encodeSnapshots()
        if (updatedSnapshots != serializedSnapshots.get()) {
            serializedSnapshots.set(updatedSnapshots)
        }
    }

    fun setKind(serverId: Long, libraryId: String, kind: KomgaLibraryKind) {
        val updated = decodeClassifications(serializedClassifications.get())
            .filterNot { it.serverId == serverId && it.libraryId == libraryId }
            .plus(KomgaLibraryClassification(serverId, libraryId, kind))
        serializedClassifications.set(updated.encodeClassifications())
    }

    fun enableClassification() {
        if (serverPreferences.localConfigMode.get() != LocalConfigMode.Separate) {
            localConfigManager.setLocalConfigMode(LocalConfigMode.Separate)
        }
        enabled.set(true)
    }

    fun disableClassification() {
        enabled.set(false)
    }

    fun disableClassificationAndUseSharedConfig() {
        enabled.set(false)
        localConfigManager.setLocalConfigMode(LocalConfigMode.Shared)
    }

    fun clearServer(serverId: Long) {
        serializedClassifications.set(
            decodeClassifications(serializedClassifications.get())
                .filterNot { it.serverId == serverId }
                .encodeClassifications(),
        )
        serializedSnapshots.set(
            decodeSnapshots(serializedSnapshots.get())
                .filterNot { it.serverId == serverId }
                .encodeSnapshots(),
        )
    }

    private fun buildClassifiedLibraries(
        serverId: Long,
        classifications: List<KomgaLibraryClassification>,
        snapshots: List<KomgaLibrarySnapshot>,
    ): List<KomgaClassifiedLibrary> {
        val kindByLibraryId = classifications
            .asSequence()
            .filter { it.serverId == serverId }
            .associate { it.libraryId to it.kind }
        return snapshots
            .asSequence()
            .filter { it.serverId == serverId }
            .distinctBy { it.libraryId }
            .map { snapshot ->
                KomgaClassifiedLibrary(
                    id = snapshot.libraryId,
                    name = snapshot.name,
                    kind = kindByLibraryId[snapshot.libraryId] ?: KomgaLibraryKind.COMIC,
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun decodeClassifications(values: Set<String>): List<KomgaLibraryClassification> {
        return values.mapNotNull { value ->
            runCatching { json.decodeFromString<KomgaLibraryClassification>(value) }.getOrNull()
        }
    }

    private fun decodeSnapshots(values: Set<String>): List<KomgaLibrarySnapshot> {
        return values.mapNotNull { value ->
            runCatching { json.decodeFromString<KomgaLibrarySnapshot>(value) }.getOrNull()
        }
    }

    private fun List<KomgaLibraryClassification>.encodeClassifications(): Set<String> =
        mapTo(linkedSetOf()) { json.encodeToString(it) }

    private fun List<KomgaLibrarySnapshot>.encodeSnapshots(): Set<String> =
        mapTo(linkedSetOf()) { json.encodeToString(it) }

    companion object {
        private const val PREF_ENABLED = "komga_library_classification_enabled"
        private const val PREF_CLASSIFICATIONS = "komga_library_classifications"
        private const val PREF_SNAPSHOTS = "komga_library_snapshots"
    }
}
