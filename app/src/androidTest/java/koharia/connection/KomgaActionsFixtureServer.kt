package koharia.connection

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class KomgaActionsFixtureServer : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    val release = CountDownLatch(1)
    val waiting = AtomicBoolean()
    val accountReads = AtomicInteger()
    val otherReads = AtomicInteger()
    val writes = AtomicInteger()
    val unhandled = ConcurrentLinkedQueue<String>()

    @Volatile var rejection = ""
    val address get() = "http://127.0.0.1:${listener.localPort}"

    init {
        executor.execute {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        it.soTimeout = 5_000
                        val request = it.getInputStream().bufferedReader().readLine().split(' ')
                        val path = request[1].substringBefore('?')
                        if (request[0] != "GET") writes.incrementAndGet()
                        val (status, body) = when {
                            request[0] == "GET" && path == "/api/v1/users/me" -> {
                                accountReads.incrementAndGet()
                                val mode = rejection
                                if (mode.isNotEmpty()) {
                                    waiting.set(true)
                                    check(release.await(30, TimeUnit.SECONDS))
                                }
                                when (mode) {
                                    "unavailable" -> "503 Fixture" to ""
                                    "permission" ->
                                        "200 OK" to
                                            """{"id":"fixture","email":"fixture@example.test","roles":["FILE_DOWNLOAD"]}"""
                                    else ->
                                        "200 OK" to
                                            """{"id":"fixture","email":"fixture@example.test","roles":["ADMIN"]}"""
                                }
                            }
                            request[0] == "GET" && path == "/api/v1/series/owned/books" -> {
                                otherReads.incrementAndGet()
                                "503 Fixture" to ""
                            }
                            else -> {
                                unhandled.add("${request[0]} $path")
                                "503 Fixture" to ""
                            }
                        }
                        val bytes = body.toByteArray()
                        it.getOutputStream().write(
                            (
                                "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                ).toByteArray() + bytes,
                        )
                    }
                }.onFailure { unhandled.add("fixture-read-failure:${it.javaClass.simpleName}") }
            }
        }
    }

    override fun close() {
        release.countDown()
        listener.close()
        executor.shutdownNow()
    }
}
