package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Owner slots preserve account separation without exporting a credential-derived owner fingerprint. */
@Serializable
data class BackupKomgaReadStatus(
    @ProtoNumber(1) val connectionId: Long,
    @ProtoNumber(2) val ownerSlot: Int,
    @ProtoNumber(3) val bookId: String,
    @ProtoNumber(4) val revision: Long,
    @ProtoNumber(5) val read: Boolean,
    @ProtoNumber(6) val requestedAt: Long,
)
