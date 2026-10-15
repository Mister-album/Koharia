package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupKomgaReadStatus
import koharia.connection.LibraryConnectionProfile
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase

internal object BackupKomgaReadStatusIntents {
    fun export(
        intents: List<KomgaReadStatusIntent>,
        additionalOwners: List<KomgaReadStatusKey> = emptyList(),
    ): List<BackupKomgaReadStatus> =
        intents.filter { it.phase != KomgaReadStatusPhase.SYNCED }.groupBy { it.key.connectionId }
            .toSortedMap().flatMap { (connectionId, owned) ->
                val slots = (owned.map { it.key } + additionalOwners).filter { it.connectionId == connectionId }
                    .map { it.ownerKey }.distinct().sorted().withIndex().associate {
                        it.value to
                            it.index
                    }
                owned.sortedWith(compareBy({ slots.getValue(it.key.ownerKey) }, { it.key.bookId })).map {
                    BackupKomgaReadStatus(
                        connectionId,
                        slots.getValue(it.key.ownerKey),
                        it.key.bookId,
                        it.revision,
                        it.read,
                        it.requestedAt,
                    )
                }
            }

    fun validate(intents: List<BackupKomgaReadStatus>, owners: List<BackupConnection>) {
        val providers = owners.associate { it.id to it.providerId }
        require(
            intents.all {
                it.connectionId > 0 && providers[it.connectionId] == "komga" && it.ownerSlot >= 0 &&
                    it.bookId.isNotBlank() && it.bookId.length <= 256 && it.bookId.none(Char::isISOControl) &&
                    it.revision > 0 && it.requestedAt >= 0
            },
        ) { "Invalid backup Komga read-status ownership or payload" }
        require(intents.map { Triple(it.connectionId, it.ownerSlot, it.bookId) }.distinct().size == intents.size) {
            "Duplicate backup Komga read-status intent"
        }
    }

    fun remap(
        intents: List<BackupKomgaReadStatus>,
        targets: Map<Long, LibraryConnectionProfile>,
        recoveryKey: String,
    ): List<KomgaReadStatusIntent> {
        require(recoveryKey.matches(Regex("[a-f0-9]{64}")))
        return intents.map {
            val target = targets.getValue(it.connectionId)
            require(target.id != it.connectionId && target.providerId == "komga")
            KomgaReadStatusIntent(
                KomgaReadStatusKey(target.id, "restored:$recoveryKey:${it.ownerSlot}", it.bookId),
                it.revision,
                it.read,
                it.requestedAt,
                null,
                KomgaReadStatusPhase.HELD,
            )
        }
    }
}
