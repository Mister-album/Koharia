package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupEpubProgress(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val bookUrl: String? = null,
    @ProtoNumber(3) val locatorJson: String,
    @ProtoNumber(4) val progression: Double? = null,
    @ProtoNumber(5) val positionIndex: Long? = null,
    @ProtoNumber(6) val updatedAt: Long,
    @ProtoNumber(7) val lastSyncedAt: Long? = null,
)
