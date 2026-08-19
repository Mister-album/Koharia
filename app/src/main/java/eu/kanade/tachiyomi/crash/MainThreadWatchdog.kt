package eu.kanade.tachiyomi.crash

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Detects a blocked main thread without keeping the process alive during normal shutdown. */
object MainThreadWatchdog {

    private const val HEARTBEAT_INTERVAL_MS = 500L
    private const val STALL_THRESHOLD_MS = 3_000L

    @Volatile
    private var executor: ScheduledExecutorService? = null

    fun start(context: Context) {
        if (executor != null) return

        synchronized(this) {
            if (executor != null) return

            val applicationContext = context.applicationContext
            val mainHandler = Handler(Looper.getMainLooper())
            val mainThread = Looper.getMainLooper().thread
            val lastHeartbeat = AtomicLong(SystemClock.uptimeMillis())
            val stallReported = AtomicBoolean(false)

            val heartbeat = object : Runnable {
                override fun run() {
                    lastHeartbeat.set(SystemClock.uptimeMillis())
                    stallReported.set(false)
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
            mainHandler.post(heartbeat)

            val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "Koharia-main-thread-watchdog").apply {
                    isDaemon = true
                }
            }
            executor = watchdog
            watchdog.scheduleAtFixedRate(
                {
                    try {
                        val stalledFor = SystemClock.uptimeMillis() - lastHeartbeat.get()
                        if (stalledFor >= STALL_THRESHOLD_MS && stallReported.compareAndSet(false, true)) {
                            CrashDiagnostics.recordMainThreadStall(
                                applicationContext,
                                stalledFor,
                                mainThread.stackTrace,
                            )
                        }
                    } catch (error: Throwable) {
                        CrashDiagnostics.recordNonFatal(
                            applicationContext,
                            "main.thread.watchdog",
                            error,
                        )
                    }
                },
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }
}
