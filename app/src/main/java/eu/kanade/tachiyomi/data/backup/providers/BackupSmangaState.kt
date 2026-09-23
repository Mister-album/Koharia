package eu.kanade.tachiyomi.data.backup.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupSmangaState(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val readState: BackupSmangaReadState? = null,
    @ProtoNumber(3) val pendingHistoryEvents: List<BackupSmangaHistoryEvent> = emptyList(),
    @ProtoNumber(4) val confirmationPayload: String? = null,
)

@Serializable
data class BackupSmangaReadState(
    @ProtoNumber(1) val pageIndex: Int,
    @ProtoNumber(2) val totalPages: Int,
    @ProtoNumber(3) val completed: Boolean,
    @ProtoNumber(4) val readAt: Long,
    @ProtoNumber(5) val revision: Long,
    @ProtoNumber(6) val pending: Boolean,
    @ProtoNumber(7) val explicitUnread: Boolean = false,
    @ProtoNumber(8) val initialPage: Boolean = false,
)

@Serializable
data class BackupSmangaHistoryEvent(
    @ProtoNumber(1) val eventId: String,
    @ProtoNumber(2) val mediaId: Long,
    @ProtoNumber(3) val readAt: Long,
)
