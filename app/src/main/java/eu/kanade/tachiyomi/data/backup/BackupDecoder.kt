package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = Injekt.get(),
) {
    fun decode(uri: Uri): Backup {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        val envelope = try {
            parser.decodeFromByteArray(KohariaBackupEnvelope.serializer(), bytes)
        } catch (_: SerializationException) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
        if (envelope.magic != KohariaBackupEnvelope.MAGIC ||
            envelope.formatVersion != KohariaBackupEnvelope.FORMAT_VERSION
        ) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
        val actualHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(envelope.compressedPayload)
            .joinToString("") { "%02x".format(it) }
        if (actualHash != envelope.payloadSha256) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
        val payload = try {
            GZIPInputStream(ByteArrayInputStream(envelope.compressedPayload)).use { it.readBytes() }
        } catch (_: Exception) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
        return try {
            parser.decodeFromByteArray(Backup.serializer(), payload)
        } catch (_: SerializationException) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
    }
}
