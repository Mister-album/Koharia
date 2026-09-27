package koharia.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import koharia.core.archive.ArchiveEntry as MihonArchiveEntry

internal class ArchiveInputStream(
    private val descriptor: ParcelFileDescriptor,
    private val checkCancelled: () -> Unit,
    private val onClosed: (ArchiveInputStream) -> Unit,
) : InputStream() {
    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val inputBuffer = ByteBuffer.allocateDirect(64 * 1024)
    private var position = 0L
    private val size = descriptor.statSize
    private var callbackFailure: Throwable? = null
    private val archive = Archive.readNew()

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            require(size >= 0) { "Archive requires a seekable descriptor" }
            Archive.readSetCallbackData(archive, this)
            Archive.readSetReadCallback<ArchiveInputStream>(archive) { _, source ->
                source.callback {
                    source.checkCancelled()
                    source.inputBuffer.clear()
                    val count = Os.pread(source.descriptor.fileDescriptor, source.inputBuffer, source.position)
                    if (count <= 0) {
                        null
                    } else {
                        source.position += count
                        source.inputBuffer.flip()
                        source.inputBuffer
                    }
                }
            }
            Archive.readSetSeekCallback<ArchiveInputStream>(archive) { _, source, offset, whence ->
                source.callback {
                    source.checkCancelled()
                    val base = when (whence) {
                        OsConstants.SEEK_SET -> 0L
                        OsConstants.SEEK_CUR -> source.position
                        OsConstants.SEEK_END -> source.size
                        else -> throw IOException("Invalid archive seek origin")
                    }
                    val target = Math.addExact(base, offset)
                    if (target < 0) throw IOException("Negative archive seek position")
                    source.position = target
                    target
                }
            }
            Archive.readSetSkipCallback<ArchiveInputStream>(archive) { _, source, request ->
                source.callback {
                    source.checkCancelled()
                    val skipped = request.coerceIn(0L, (source.size - source.position).coerceAtLeast(0L))
                    source.position += skipped
                    skipped
                }
            }
            nativeCall { Archive.readOpen1(archive) }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    override fun read(): Int = synchronized(lock) {
        read(oneByteBuffer)
        if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        val buffer = ByteBuffer.wrap(b, off, len).slice()
        read(buffer)
        return if (buffer.hasRemaining()) buffer.remaining() else -1
    }

    private fun read(buffer: ByteBuffer) {
        synchronized(lock) {
            if (isClosed) throw IOException("Archive stream is closed")
            buffer.clear()
            nativeCall { Archive.readData(archive, buffer) }
            buffer.flip()
        }
    }

    override fun close() {
        try {
            synchronized(lock) {
                if (isClosed) return
                isClosed = true
                try {
                    Archive.readFree(archive)
                } finally {
                    descriptor.close()
                }
            }
        } finally {
            onClosed(this)
        }
    }

    fun getNextEntry(): MihonArchiveEntry? = synchronized(lock) {
        if (isClosed) throw IOException("Archive stream is closed")
        checkCancelled()
        return nativeCall { Archive.readNextHeader(archive) }.takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            MihonArchiveEntry(name, isFile)
        }
    }

    private inline fun <T> callback(action: () -> T): T = try {
        action()
    } catch (error: Throwable) {
        callbackFailure = error
        throw error
    }

    // JNI wraps callback exceptions; cancellation and I/O errors must retain their original identity.
    private inline fun <T> nativeCall(action: () -> T): T = try {
        action().also { callbackFailure?.let { error -> throw error } }
    } catch (error: Throwable) {
        throw callbackFailure ?: if (error is ArchiveException) ArchiveReadException(error) else error
    }
}
