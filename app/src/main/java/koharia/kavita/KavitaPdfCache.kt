package koharia.kavita

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Disposable originals, never the user's manual download directory. */
class KavitaPdfCache(private val context: Context, connectionId: Long, accountKey: String) {
    private val directory = File(context.cacheDir, "kavita-pdf/$connectionId/$accountKey")
    init {
        require(connectionId > 0 && accountKey.matches(Regex("[a-zA-Z0-9-]+")))
    }
    private fun file(key: String): File {
        require(key.matches(Regex("[a-zA-Z0-9-]+")))
        return File(directory, "$key.pdf")
    }

    fun find(key: String): UniFile? = file(key).takeIf { it.isFile && it.length() > 0 }?.let(UniFile::fromFile)

    suspend fun invalidate(key: String) = locks.getOrPut(file(key).absolutePath) { Mutex() }.withLock {
        file(key).delete()
    }

    suspend fun prepare(
        key: String,
        chapterId: Long,
        api: KavitaApiClient,
        checkSession: () -> Unit,
    ): UniFile = withContext(
        Dispatchers.IO,
    ) {
        locks.getOrPut(file(key).absolutePath) { Mutex() }.withLock {
            checkSession()
            find(key)?.let { return@withLock it }
            check(directory.mkdirs() || directory.isDirectory)
            val temporary = File(directory, "$key.part")
            activeFiles += temporary.absolutePath
            activeFiles += file(key).absolutePath
            try {
                val call = api.client.newCall(api.request("Reader/pdf", "chapterId" to chapterId))
                coroutineScope {
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    try {
                        call.await().use { response ->
                            if (response.code != 200) {
                                throw KavitaException(
                                    if (response.code ==
                                        401
                                    ) {
                                        KavitaException.Reason.AUTHENTICATION
                                    } else {
                                        KavitaException.Reason.NETWORK
                                    },
                                    response.code,
                                )
                            }
                            val body = response.body
                            val expected = body.contentLength()
                            if (expected > MAX_BYTES ||
                                expected > directory.usableSpace
                            ) {
                                throw IOException("Insufficient PDF cache space")
                            }
                            var copied = 0L
                            body.byteStream().use { input ->
                                temporary.outputStream().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        checkSession()
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        copied += count
                                        if (copied > MAX_BYTES) throw IOException("PDF exceeds cache limit")
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            if (expected >= 0 && copied != expected) throw IOException("Incomplete PDF")
                        }
                    } finally {
                        cancellation.cancel()
                    }
                }
                validate(requireNotNull(UniFile.fromFile(temporary)), context)
                currentCoroutineContext().ensureActive()
                checkSession()
                check(temporary.renameTo(file(key))) { "Unable to publish PDF cache" }
                requireNotNull(UniFile.fromFile(file(key)))
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                temporary.delete()
                activeFiles -= temporary.absolutePath
                activeFiles -= file(key).absolutePath
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 1024L * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val activeFiles = ConcurrentHashMap.newKeySet<String>()

        fun size(context: Context): Long = File(context.cacheDir, "kavita-pdf").walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }

        fun clear(context: Context): Int = File(context.cacheDir, "kavita-pdf").walkBottomUp()
            .filter { it.isFile && it.absolutePath !in activeFiles }.count { it.delete() }

        suspend fun validate(
            file: UniFile,
            context: Context = uy.kohesive.injekt.Injekt.get<android.app.Application>(),
        ) = withContext(Dispatchers.IO) {
            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { descriptor ->
                ParcelFileDescriptor.dup(descriptor.fileDescriptor).use { duplicate ->
                    PdfRenderer(duplicate).use { renderer ->
                        if (renderer.pageCount <= 0) throw IOException("Empty PDF")
                    }
                }
            } ?: throw IOException("Unreadable PDF")
        }
    }
}
