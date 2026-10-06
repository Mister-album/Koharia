package koharia.connection

import kotlinx.serialization.Serializable
import java.io.OutputStream

data class ConnectionFileTransfer(val extension: String, val size: Long, val version: String)

@Serializable
data class ConnectionFileTransferCheckpoint(val version: String, val bytes: Long, val sha256: String)

class ConnectionFileTransferRestartRequired : java.io.IOException("This transfer must restart from the beginning")

/** File protocols participate in the download queue without pretending to be HTTP sources. */
interface ConnectionFileTransferAdapter {
    val supportsFileTransfers: Boolean
    suspend fun describeFileTransfer(chapterUrl: String): ConnectionFileTransfer
    suspend fun transferFile(
        chapterUrl: String,
        expected: ConnectionFileTransfer,
        output: OutputStream,
        offset: Long = 0,
    )
    suspend fun fileTransferCheckpoint(chapterUrl: String): ConnectionFileTransferCheckpoint?
    suspend fun saveFileTransferCheckpoint(chapterUrl: String, checkpoint: ConnectionFileTransferCheckpoint)
    suspend fun clearFileTransferCheckpoint(chapterUrl: String)
}
