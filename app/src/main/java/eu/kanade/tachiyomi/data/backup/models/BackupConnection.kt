package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Owner inventory is independent of optional app preferences and contains no credentials. */
@Serializable
data class BackupConnection(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val providerId: String,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val writesPaused: Boolean = false,
)
