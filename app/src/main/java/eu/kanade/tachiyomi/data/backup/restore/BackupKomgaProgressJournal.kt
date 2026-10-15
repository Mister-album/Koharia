package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupKomgaPageEvent
import eu.kanade.tachiyomi.data.backup.models.BackupKomgaReadStatus
import koharia.connection.LibraryConnectionProfile
import koharia.domain.komga.KomgaPageAttempt
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaPageState
import koharia.domain.komga.KomgaReadStatusAttempt
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase

/** Account slots belong to the complete journal, not to each independently filtered table. */
internal object BackupKomgaProgressJournal {
    data class Export(
        val intents: List<BackupKomgaReadStatus> = emptyList(),
        val pages: List<BackupKomgaPageEvent> = emptyList(),
        val pageAttempts: List<BackupKomgaPageEvent> = emptyList(),
        val readAttempts: List<BackupKomgaReadStatus> = emptyList(),
    ) {
        val connections: Set<Long> get() =
            (
                intents.map { it.connectionId } + pages.map { it.connectionId } +
                    pageAttempts.map { it.connectionId } + readAttempts.map { it.connectionId }
                ).toSet()
    }

    data class Held(
        val pages: List<KomgaPageState> = emptyList(),
        val readAttempts: List<KomgaReadStatusAttempt> = emptyList(),
    )

    fun export(
        intents: List<KomgaReadStatusIntent>,
        states: List<KomgaPageState>,
        requests: List<KomgaReadStatusAttempt>,
    ): Export {
        val keys = intents.map { it.key } + states.flatMap {
            listOfNotNull(it.event?.key, it.attempt?.event?.key)
        } + requests.map { it.intent.key }
        val slots = keys.groupBy { it.connectionId }.flatMap { (connection, owned) ->
            owned.map { it.ownerKey }.distinct().sorted().mapIndexed { index, owner -> (connection to owner) to index }
        }.toMap()
        fun page(event: KomgaPageEvent) = BackupKomgaPageEvent(
            event.key.connectionId,
            slots.getValue(event.key.connectionId to event.key.ownerKey),
            event.key.bookId,
            event.generation,
            event.revision,
            event.page,
            event.total,
            event.readAt,
        )
        val order = compareBy<BackupKomgaPageEvent>({ it.connectionId }, { it.ownerSlot }, { it.bookId })
        return Export(
            BackupKomgaReadStatusIntents.export(intents, keys),
            states.mapNotNull { it.event?.let(::page) }.sortedWith(order),
            states.mapNotNull { it.attempt?.event?.let(::page) }.sortedWith(order),
            BackupKomgaReadStatusIntents.export(requests.map { it.intent }, keys),
        )
    }

    fun validate(backup: Backup, owners: List<BackupConnection>) {
        BackupKomgaReadStatusIntents.validate(backup.backupKomgaReadStatusAttempts, owners)
        val providers = owners.associate { it.id to it.providerId }
        fun key(page: BackupKomgaPageEvent) = Triple(page.connectionId, page.ownerSlot, page.bookId)
        for (entries in listOf(backup.backupKomgaPages, backup.backupKomgaPageAttempts)) {
            require(entries.map(::key).distinct().size == entries.size) { "Duplicate Komga page journal entry" }
            require(
                entries.all {
                    it.connectionId > 0 && providers[it.connectionId] == "komga" && it.ownerSlot >= 0 &&
                        it.bookId.isNotBlank() && it.bookId.length <= 256 && it.bookId.none(Char::isISOControl) &&
                        it.generation > 0 && it.revision >= 0 && it.total > 0 && it.page in 0 until it.total &&
                        it.readAt >= 0
                },
            ) { "Invalid Komga page journal payload or owner" }
        }
        val pages = backup.backupKomgaPages.associateBy(::key)
        backup.backupKomgaPageAttempts.forEach { attempt ->
            pages[key(attempt)]?.let { desired ->
                require(attempt.generation <= desired.generation && attempt.revision <= desired.revision)
                require(attempt.generation != desired.generation || attempt == desired)
            }
        }
        val intents = backup.backupKomgaReadStatus.associateBy { Triple(it.connectionId, it.ownerSlot, it.bookId) }
        backup.backupKomgaReadStatusAttempts.forEach { request ->
            intents[Triple(request.connectionId, request.ownerSlot, request.bookId)]?.let { desired ->
                require(request.revision <= desired.revision)
                require(request.revision != desired.revision || request == desired)
            }
        }
    }

    fun remap(backup: Backup, targets: Map<Long, LibraryConnectionProfile>, recoveryKey: String): Held {
        require(recoveryKey.matches(Regex("[a-f0-9]{64}")))
        fun page(value: BackupKomgaPageEvent, phase: KomgaReadStatusPhase): KomgaPageEvent {
            val target = targets.getValue(value.connectionId)
            require(target.id != value.connectionId && target.providerId == "komga")
            return KomgaPageEvent(
                KomgaReadStatusKey(target.id, "restored:$recoveryKey:${value.ownerSlot}", value.bookId),
                value.generation,
                value.revision,
                value.page,
                value.total,
                value.readAt,
                null,
                phase,
            )
        }
        val desired = backup.backupKomgaPages.map { page(it, KomgaReadStatusPhase.HELD) }.associateBy { it.key }
        val requests = backup.backupKomgaPageAttempts.map {
            KomgaPageAttempt(page(it, KomgaReadStatusPhase.PENDING), "restored:unknown")
        }.associateBy { it.event.key }
        return Held(
            (desired.keys + requests.keys).map { KomgaPageState(desired[it], requests[it]) },
            BackupKomgaReadStatusIntents.remap(backup.backupKomgaReadStatusAttempts, targets, recoveryKey).map {
                KomgaReadStatusAttempt(it.copy(phase = KomgaReadStatusPhase.PENDING), "restored:unknown")
            },
        )
    }
}
