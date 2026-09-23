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
    fun inspect(uri: Uri): KohariaBackupEnvelope {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        return try {
            parser.decodeFromByteArray(KohariaBackupEnvelope.serializer(), bytes)
        } catch (_: SerializationException) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
    }

    fun decode(uri: Uri, password: CharArray? = null): Backup {
        val envelope = inspect(uri)
        if (envelope.magic != KohariaBackupEnvelope.MAGIC ||
            envelope.formatVersion !in 1..KohariaBackupEnvelope.FORMAT_VERSION ||
            envelope.schemaVersion !in 1..KohariaBackupEnvelope.SCHEMA_VERSION
        ) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
        val encrypted = envelope.encryptedPayload.isNotEmpty()
        if ((encrypted && envelope.formatVersion < 2) ||
            (encrypted && envelope.compressedPayload.isNotEmpty()) ||
            (!encrypted && envelope.compressedPayload.isEmpty())
        ) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
        val storedPayload = if (encrypted) envelope.encryptedPayload else envelope.compressedPayload
        val actualHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(storedPayload)
            .joinToString("") { "%02x".format(it) }
        if (actualHash != envelope.payloadSha256) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
        }
        val compressedPayload = if (encrypted) {
            val requiredPassword = password?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("A password is required to restore this backup")
            BackupCrypto.decrypt(
                EncryptedBackupPayload(
                    envelope.encryptionSalt,
                    envelope.encryptionIv,
                    envelope.encryptedPayload,
                    envelope.encryptionIterations,
                ),
                requiredPassword,
            )
        } else {
            envelope.compressedPayload
        }
        val payload = try {
            GZIPInputStream(ByteArrayInputStream(compressedPayload)).use { it.readBytes() }
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
