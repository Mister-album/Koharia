package koharia.diagnostics

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.BackupCrypto
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupPayloadLimits
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import koharia.connection.ConnectionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class BackupContainerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val parser = Injekt.get<ProtoBuf>()
    private val prefix = "backup-container-${System.nanoTime()}"
    private val store = Injekt.get<PreferenceStore>()
    private val marker = store.getString(prefix)
    private val secret = store.getString(Preference.privateKey("$prefix-private"))
    private val file = File(context.cacheDir, "$prefix.kohariabackup")
    private val password = "isolated container password".toCharArray()

    @Before fun requireFixture() {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        marker.set("preserved")
        secret.set("fixture-private-canary")
    }

    @After fun removeOnlyOwnedData() {
        marker.delete()
        secret.delete()
        file.delete()
        password.fill('\u0000')
    }

    @Test fun wrongPasswordAndAuthenticatedTamperingCannotStartRestore(): Unit = runBlocking(Dispatchers.IO) {
        val original = envelope(encrypted = true)
        write(original)
        assertEquals("imported", decodedMarker(BackupDecoder(context).decode(Uri.fromFile(file), password)))
        reject(original, "wrong password".toCharArray())
        reject(original, null)
        // Recompute the outer checksum so authenticated encryption must reject the altered bytes.
        val changed = original.encryptedPayload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        reject(original.copy(encryptedPayload = changed, payloadSha256 = changed.sha256()))
        reject(original.copy(encryptionSalt = original.encryptionSalt.copyOf().also { it[0]++ }))
        reject(original.copy(encryptionIv = original.encryptionIv.copyOf().also { it[0]++ }))
    }

    @Test fun invalidParametersAndAmbiguousPayloadsFailBeforeRestoring(): Unit = runBlocking(Dispatchers.IO) {
        val original = envelope(encrypted = true)
        for (iterations in listOf(Int.MIN_VALUE, 0, 599_999, 2_000_001, Int.MAX_VALUE)) {
            reject(original.copy(encryptionIterations = iterations))
        }
        reject(original.copy(encryptionSalt = ByteArray(15)))
        reject(original.copy(encryptionIv = ByteArray(11)))
        reject(original.copy(compressedPayload = byteArrayOf(1)))
        reject(original.copy(formatVersion = 1))
        reject(original.copy(schemaVersion = KohariaBackupEnvelope.SCHEMA_VERSION + 1))
        reject(original.copy(magic = "UNRECOGNIZED"))
        reject(original.copy(encryptedPayload = ByteArray(0)))
        val short = original.encryptedPayload.copyOf(15)
        reject(original.copy(encryptedPayload = short, payloadSha256 = short.sha256()))
    }

    @Test fun truncatedEnvelopeGzipAndCiphertextPreserveExistingState(): Unit = runBlocking(Dispatchers.IO) {
        val encrypted = envelope(encrypted = true)
        val encoded = parser.encodeToByteArray(KohariaBackupEnvelope.serializer(), encrypted)
        for (length in listOf(0, 1, encoded.size / 2, encoded.size - 1)) {
            file.writeBytes(encoded.copyOf(length))
            rejectCurrentFile(password)
        }
        val short = encrypted.encryptedPayload.dropLast(1).toByteArray()
        reject(encrypted.copy(encryptedPayload = short, payloadSha256 = short.sha256()))
        val plain = envelope(encrypted = false)
        val damaged = plain.compressedPayload.dropLast(4).toByteArray()
        reject(plain.copy(compressedPayload = damaged, payloadSha256 = damaged.sha256()), null)
        reject(plain.copy(payloadSha256 = "0".repeat(64)), null)
        write(plain.copy(formatVersion = 1, schemaVersion = 1))
        assertEquals("imported", decodedMarker(BackupDecoder(context).decode(Uri.fromFile(file))))
    }

    @Test fun creatorPrivateOptionSurvivesEncryptedContainerRoundTrip(): Unit = runBlocking(Dispatchers.IO) {
        for (includePrivate in listOf(false, true)) {
            file.createNewFile()
            BackupCreator(context, isAutoBackup = false).backup(
                Uri.fromFile(file),
                BackupOptions(
                    libraryEntries = false,
                    categories = false,
                    chapters = false,
                    history = false,
                    readEntries = false,
                    connectionSettings = false,
                    privateSettings = includePrivate,
                ),
                password,
            )
            val decoder = BackupDecoder(context)
            assertTrue(decoder.inspect(Uri.fromFile(file)).encryptedPayload.isNotEmpty())
            val backup = decoder.decode(Uri.fromFile(file), password)
            assertEquals("preserved", decodedMarker(backup))
            val exportedSecret = backup.backupPreferences.find { it.key == secret.key() }
            assertEquals(includePrivate, exportedSecret != null)
            if (includePrivate) assertEquals(StringPreferenceValue("fixture-private-canary"), exportedSecret!!.value)
        }
    }

    @Test fun oversizedContainerIsRejectedBeforeProtobufAndRestore(): Unit = runBlocking(Dispatchers.IO) {
        RandomAccessFile(file, "rw").use { it.setLength(BackupPayloadLimits.CONTAINER_BYTES.toLong() + 1) }
        rejectCurrentFile(null, sizeLimit = true)
    }

    @Test fun gzipExpansionIsRejectedBeforePayloadParsingAndRestore(): Unit = runBlocking(Dispatchers.IO) {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { gzip ->
                val block = ByteArray(8192)
                repeat(BackupPayloadLimits.PAYLOAD_BYTES / block.size) { gzip.write(block) }
                gzip.write(0)
            }
        }.toByteArray()
        write(envelope(false).copy(compressedPayload = compressed, payloadSha256 = compressed.sha256()))
        rejectCurrentFile(null, sizeLimit = true)
    }

    @Test fun oversizedExportLeavesExistingDestinationIntact(): Unit = runBlocking(Dispatchers.IO) {
        val value = "x".repeat(1024 * 1024)
        val temporaryStore = object : PreferenceStore by InMemoryPreferenceStore() {
            override fun getAll() = (0 until 65).associate { "large-$it" to value }
        }
        file.writeText("existing backup must survive rejection")
        val error = runCatching {
            BackupCreator(
                context,
                isAutoBackup = false,
                preferenceBackupCreator = PreferenceBackupCreator(preferenceStore = temporaryStore),
            ).backup(
                Uri.fromFile(file),
                BackupOptions(
                    libraryEntries = false,
                    categories = false,
                    readEntries = false,
                    connectionSettings = false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(context.stringResource(MR.strings.backup_size_limit_exceeded), error?.message)
        assertEquals("existing backup must survive rejection", file.readText())
    }

    @Test fun fiftyThousandChaptersAcrossEightLibraryModesRemainCompatible(): Unit = runBlocking(Dispatchers.IO) {
        val providers = listOf("komga", "lanraragi", "kavita", "smanga", "suwayomi") +
            List(3) { "local-folder" }
        val backup = Backup(
            backupManga = List(2500) { index ->
                BackupManga(
                    source = (index % providers.size + 1).toLong(),
                    url = "/fixture/series/$index",
                    title = "系列 $index / Series",
                    description = "Metadata and Unicode 阅读器 ".repeat(40),
                    chapters = List(20) { chapter ->
                        BackupChapter(
                            url = "/fixture/series/$index/chapter/$chapter",
                            name = "Chapter $chapter",
                            read = chapter % 2 == 0,
                            lastPageRead = chapter.toLong(),
                        )
                    },
                )
            },
            backupConnections = providers.mapIndexed { index, provider ->
                BackupConnection((index + 1).toLong(), provider, "Fixture $index")
            },
        )
        val payload = parser.encodeToByteArray(Backup.serializer(), backup)
        BackupPayloadLimits.checkSize(payload.size, BackupPayloadLimits.PAYLOAD_BYTES)
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(payload) }
        }.toByteArray()
        val encrypted = BackupCrypto.encrypt(compressed, password)
        write(
            envelope(true).copy(
                encryptedPayload = encrypted.ciphertext,
                payloadSha256 = encrypted.ciphertext.sha256(),
                encryptionSalt = encrypted.salt,
                encryptionIv = encrypted.iv,
                encryptionIterations = encrypted.iterations,
            ),
        )
        val decoded = BackupDecoder(context).decode(Uri.fromFile(file), password)
        assertEquals(2500, decoded.backupManga.size)
        assertEquals(50_000, decoded.backupManga.sumOf { it.chapters.size })
        assertEquals(backup.backupConnections, decoded.backupConnections)
        decoded.backupManga.forEachIndexed { index, manga ->
            assertEquals(backup.backupManga[index].description, manga.description)
            assertEquals(19L, manga.chapters.last().lastPageRead)
            assertTrue(manga.chapters.first().read)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putInt("backup_payload_bytes", payload.size)
                putLong("backup_container_bytes", file.length())
                putInt("backup_chapters", 50_000)
            },
        )
    }

    private fun decodedMarker(backup: Backup) =
        (backup.backupPreferences.single { it.key == marker.key() }.value as StringPreferenceValue).value

    private fun envelope(encrypted: Boolean): KohariaBackupEnvelope {
        val backup = Backup(
            backupManga = emptyList(),
            backupPreferences = listOf(BackupPreference(marker.key(), StringPreferenceValue("imported"))),
        )
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(parser.encodeToByteArray(Backup.serializer(), backup)) }
        }.toByteArray()
        val cipher = if (encrypted) BackupCrypto.encrypt(compressed, password) else null
        return KohariaBackupEnvelope(
            magic = KohariaBackupEnvelope.MAGIC,
            formatVersion = KohariaBackupEnvelope.FORMAT_VERSION,
            schemaVersion = KohariaBackupEnvelope.SCHEMA_VERSION,
            createdAt = 1,
            applicationId = context.packageName,
            payloadSha256 = (cipher?.ciphertext ?: compressed).sha256(),
            compressedPayload = if (encrypted) byteArrayOf() else compressed,
            encryptionSalt = cipher?.salt ?: byteArrayOf(),
            encryptionIv = cipher?.iv ?: byteArrayOf(),
            encryptionIterations = cipher?.iterations ?: 0,
            encryptedPayload = cipher?.ciphertext ?: byteArrayOf(),
        )
    }

    private fun write(envelope: KohariaBackupEnvelope) {
        file.writeBytes(parser.encodeToByteArray(KohariaBackupEnvelope.serializer(), envelope))
    }

    private suspend fun reject(envelope: KohariaBackupEnvelope, suppliedPassword: CharArray? = password) {
        write(envelope)
        rejectCurrentFile(suppliedPassword)
    }

    private suspend fun rejectCurrentFile(suppliedPassword: CharArray?, sizeLimit: Boolean = false) {
        val connections = Injekt.get<ConnectionPreferences>()
        val profiles = connections.getProfiles()
        val active = connections.activeConnectionId.get()
        val sources = Injekt.get<SourceManager>().getCatalogueSources().toList()
        val error = runCatching {
            BackupRestorer(context, BackupNotifier(context), isSync = false).restore(
                Uri.fromFile(file),
                RestoreOptions(),
                suppliedPassword,
            )
        }.exceptionOrNull()
        assertTrue(
            "Malformed container must fail at the decoding boundary",
            error is IOException || error is IllegalArgumentException || error is GeneralSecurityException,
        )
        if (sizeLimit) assertEquals(context.stringResource(MR.strings.backup_size_limit_exceeded), error?.message)
        assertEquals("preserved", marker.get())
        assertEquals("fixture-private-canary", secret.get())
        assertEquals(profiles, connections.getProfiles())
        assertEquals(active, connections.activeConnectionId.get())
        val after = Injekt.get<SourceManager>().getCatalogueSources()
        assertEquals(sources.size, after.size)
        assertFalse(sources.any { before -> after.none { it === before } })
    }

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }
}
