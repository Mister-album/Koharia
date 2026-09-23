package eu.kanade.tachiyomi.data.backup.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupLanraragiState(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val pageIndex: Int,
    @ProtoNumber(3) val totalPages: Int,
    @ProtoNumber(4) val readAt: Long,
    @ProtoNumber(5) val localUnread: Boolean = false,
    @ProtoNumber(6) val pending: Boolean = true,
    @ProtoNumber(7) val initialPage: Boolean = false,
)
