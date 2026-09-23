package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupTtsProgress(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val sentenceIndex: Long,
    @ProtoNumber(3) val updatedAt: Long,
)
