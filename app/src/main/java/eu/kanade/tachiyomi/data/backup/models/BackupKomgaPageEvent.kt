package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupKomgaPageEvent(
    @ProtoNumber(1) val connectionId: Long,
    @ProtoNumber(2) val ownerSlot: Int,
    @ProtoNumber(3) val bookId: String,
    @ProtoNumber(4) val generation: Long,
    @ProtoNumber(5) val revision: Long,
    @ProtoNumber(6) val page: Long,
    @ProtoNumber(7) val total: Long,
    @ProtoNumber(8) val readAt: Long,
)
