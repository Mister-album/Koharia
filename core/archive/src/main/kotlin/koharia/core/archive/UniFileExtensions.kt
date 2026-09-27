package koharia.core.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.hippo.unifile.UniFile
import java.io.File
import java.io.IOException

internal fun UniFile.openFileDescriptor(context: Context, mode: String): ParcelFileDescriptor =
    context.contentResolver.openFileDescriptor(uri, mode) ?: error("Failed to open file descriptor: ${filePath ?: uri}")

fun UniFile.archiveReader(context: Context, checkCancelled: () -> Unit = {}): ArchiveReader =
    openFileDescriptor(context, "r").use { descriptor ->
        openArchiveReader(descriptor, File(context.cacheDir, "archive-input"), checkCancelled)
    }

/** Pipes cannot serve independent random readers. Spool only those inputs, never regular large files. */
fun openArchiveReader(
    descriptor: ParcelFileDescriptor,
    temporaryDirectory: File,
    checkCancelled: () -> Unit = {},
): ArchiveReader {
    checkCancelled()
    val seekable = if (descriptor.statSize < 0) {
        false
    } else {
        try {
            Os.pread(descriptor.fileDescriptor, ByteArray(1), 0, 1, 0L)
            true
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESPIPE) throw error
            false
        }
    }
    if (seekable) return ArchiveReader(descriptor)
    if (!temporaryDirectory.isDirectory && !temporaryDirectory.mkdirs() && !temporaryDirectory.isDirectory) {
        throw IOException("Cannot create archive temporary directory")
    }
    val file = File.createTempFile("archive-", ".tmp", temporaryDirectory)
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { temporary ->
            // The descriptor keeps the data alive; cancellation, process exit and cache cleaning cannot orphan it.
            if (!file.delete()) throw IOException("Cannot unlink archive temporary file")
            val inputDescriptor = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(
                    ParcelFileDescriptor.dup(temporary.fileDescriptor),
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    val poll = arrayOf(
                        StructPollfd().apply {
                            fd = inputDescriptor.fileDescriptor
                            events = OsConstants.POLLIN.toShort()
                        },
                    )
                    while (true) {
                        checkCancelled()
                        if (Os.poll(poll, 100) == 0) continue
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (temporaryDirectory.usableSpace < count + 1024L * 1024) {
                            throw ArchiveSpaceException()
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            checkCancelled()
            return ArchiveReader(temporary)
        }
    } finally {
        file.delete()
    }
}

fun UniFile.epubReader(context: Context) = EpubReader(archiveReader(context))
