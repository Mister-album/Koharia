package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Retry the same payload and restore choices into its reserved owners; never persist credentials. */
internal object BackupRestoreKey {
    fun create(backup: Backup, options: RestoreOptions, directoryBindings: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(bytes: ByteArray) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add("koharia-restore-v1".toByteArray())
        add(options.asBooleanArray().map { if (it) 1.toByte() else 0.toByte() }.toByteArray())
        directoryBindings.toSortedMap().forEach { (source, target) ->
            add(source.toByteArray())
            add(target.toByteArray())
        }
        add(ProtoBuf.encodeToByteArray(Backup.serializer(), backup.copy(backupManga = emptyList())))
        // Bound temporary encoding to one reading entry, not a second copy of the whole library.
        backup.backupManga.forEach { add(ProtoBuf.encodeToByteArray(BackupManga.serializer(), it)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
