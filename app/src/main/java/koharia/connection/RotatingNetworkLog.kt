package koharia.connection

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded, best-effort writes never make a network request wait for disk IO. */
internal class RotatingNetworkLog(
    private val directory: File,
    private val maxBytes: Long = 512 * 1024,
    queueCapacity: Int = 1024,
) : AutoCloseable {
    private val lock = Any()
    private val dropped = AtomicLong()
    private val failed = AtomicLong()
    private val current = File(directory, "network-current.log")
    private val previous = File(directory, "network-previous.log")
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        { runnable -> Thread(runnable, "Koharia-network-log").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun append(line: String) {
        // Only bounded single-line records are accepted, including in tests.
        val bytes = (line.replace('\n', ' ').replace('\r', ' ').take(2048) + "\n").toByteArray()
        try {
            executor.execute {
                synchronized(lock) {
                    try {
                        check(directory.isDirectory || directory.mkdirs())
                        if (current.length() + bytes.size > maxBytes) {
                            check(!previous.exists() || previous.delete())
                            if (current.exists()) check(current.renameTo(previous))
                        }
                        current.appendBytes(bytes)
                    } catch (_: Exception) {
                        failed.incrementAndGet()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            dropped.incrementAndGet()
        }
    }

    /** Called on IO during export; a barrier includes events queued before the export request. */
    fun read(): String {
        val flushed = runCatching { executor.submit {}.get(5, TimeUnit.SECONDS) }.isSuccess
        return synchronized(lock) {
            buildString {
                appendLine("writerFlushed=$flushed dropped=${dropped.get()} writeFailures=${failed.get()}")
                for (file in listOf(previous, current)) {
                    if (file.isFile) {
                        appendLine("--- ${file.name} ---")
                        appendLine(runCatching { file.readText() }.getOrDefault("read-failed"))
                    }
                }
            }
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
