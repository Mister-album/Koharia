package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class KohariaBackupEnvelope(
    @ProtoNumber(1) val magic: String,
    @ProtoNumber(2) val formatVersion: Int,
    @ProtoNumber(3) val schemaVersion: Int,
    @ProtoNumber(4) val createdAt: Long,
    @ProtoNumber(5) val applicationId: String,
    @ProtoNumber(6) val payloadSha256: String,
    @ProtoNumber(7) val compressedPayload: ByteArray,
) {
    companion object {
        const val MAGIC = "KOHARIA_BACKUP"
        const val FORMAT_VERSION = 1
        const val SCHEMA_VERSION = 1
    }
}
