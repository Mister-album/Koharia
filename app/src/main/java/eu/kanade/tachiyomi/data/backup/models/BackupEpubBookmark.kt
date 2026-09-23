package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupEpubBookmark(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val locatorJson: String,
    @ProtoNumber(3) val sectionTitle: String? = null,
    @ProtoNumber(4) val progression: Double? = null,
    @ProtoNumber(5) val note: String? = null,
    @ProtoNumber(6) val createdAt: Long,
)
