package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KohariaBackupEnvelopeTest {

    @Test
    fun `envelope retains Koharia format marker and metadata`() {
        val envelope = KohariaBackupEnvelope(
            magic = KohariaBackupEnvelope.MAGIC,
            formatVersion = KohariaBackupEnvelope.FORMAT_VERSION,
            schemaVersion = KohariaBackupEnvelope.SCHEMA_VERSION,
            createdAt = 123L,
            applicationId = "app.koharia",
            payloadSha256 = "hash",
            compressedPayload = byteArrayOf(1, 2, 3),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            KohariaBackupEnvelope.serializer(),
            ProtoBuf.encodeToByteArray(KohariaBackupEnvelope.serializer(), envelope),
        )

        assertEquals(KohariaBackupEnvelope.MAGIC, decoded.magic)
        assertEquals(KohariaBackupEnvelope.FORMAT_VERSION, decoded.formatVersion)
        assertEquals(envelope.compressedPayload.toList(), decoded.compressedPayload.toList())
    }
}
