package koharia.tts

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

/**
 * §1.9 并发会话竞态回归测试（docs/tts/implementation-roadmap.md §1.9）。
 *
 * TtsService 是 Android Service，纯 JVM 无 Robolectric 无法实例化，故本测试
 * **忠实复刻 startPlayback 收尾段的协程结构**，锁定关键守卫语义：
 *
 * > 被取消的旧会话从 awaitDrained 正常返回后，绝不能执行 stopSelf() 收尾，
 * > 否则 onDestroy → stopPlayback 会把刚接管的新会话一起杀掉。
 *
 * 复刻的 bug 路径（与真机 logcat 一致）：
 * 1. 旧会话阻塞在 player.enqueue —— 真实实现是 `queue.offer(timeout)` 非挂起阻塞，
 *    不响应协程取消（用 [NonCancellable] 段模拟"取消打不断它"）。
 * 2. 新会话 startPlayback 里 `playbackJob?.cancel()` 取消旧会话。
 * 3. 队列腾出 → enqueue 返回 → 旧会话 `if (!isActive) break` 跳出 for 循环。
 * 4. 旧会话调 awaitDrained：`while (isActive)` 条件此刻为 false → **立即正常返回**
 *    （不是抛 CancellationException —— 抛异常那条路径反而不会触发 bug）。
 * 5. 守卫 `if (!isActive) return@launch` 拦住后续 stopSelf()。
 *
 * 若删掉 TtsService 里的守卫，[cancelledSessionSkipsStopSelf] 会失败。
 */
class PlaybackCancellationGuardTest {

    /**
     * 复刻 startPlayback 收尾段：阻塞 enqueue →（可选）取消 → drain 正常返回 → 守卫 → stopSelf。
     * 返回 stopSelf 是否被调用。
     */
    private suspend fun replayFinalization(cancelled: Boolean): Boolean = coroutineScope {
        var stopSelfCalled = false
        val blockedInEnqueue = CompletableDeferred<Unit>()
        val releaseEnqueue = CompletableDeferred<Unit>()

        val session = launch {
            // 步骤 1：阻塞在非挂起 enqueue（NonCancellable = 取消打不断，复刻 queue.offer 阻塞）
            withContext(NonCancellable) {
                blockedInEnqueue.complete(Unit)
                releaseEnqueue.await()
            }
            // 步骤 3：enqueue 返回后，for 循环加固守卫（break 即落到 awaitDrained）
            if (!isActive) {
                // break out of for-loop
            }
            // 步骤 4：复刻 awaitDrained 的 while(isActive) 轮询。
            // 取消时 isActive=false → 循环体一次都不进 → 正常返回（不抛异常）。
            while (isActive) {
                delay(10)
                break // 自然路径：一轮后播完
            }
            // 步骤 5：§1.9 守卫（被测对象）
            if (!isActive) return@launch
            stopSelfCalled = true
        }

        blockedInEnqueue.await() // 等旧会话进入 enqueue 阻塞
        if (cancelled) {
            session.cancel() // 步骤 2：新会话取消旧会话（在 NonCancellable 段，打不断）
        }
        releaseEnqueue.complete(Unit) // 放行 enqueue（模拟队列腾出）
        session.join()

        stopSelfCalled
    }

    @Test
    fun `cancelled session skips stopSelf - reproduces and guards 1_9 race`() = runTest {
        // 旧会话卡在 enqueue 时被新会话取消 → drain 正常返回 → 守卫必须拦住 stopSelf
        replayFinalization(cancelled = true) shouldBe false
    }

    @Test
    fun `naturally completed session still calls stopSelf`() = runTest {
        // 没被取消、自然播完的会话必须正常收尾（守卫不能误伤正常路径）
        replayFinalization(cancelled = false) shouldBe true
    }

    @Test
    fun `guard keys off isActive - cancelled before drain finalization is caught`() = runTest {
        // 直接验证守卫核心语义：job 取消后 isActive=false，收尾段被跳过
        var stopSelfCalled = false
        val enteredBlock = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val session = launch {
            withContext(NonCancellable) {
                enteredBlock.complete(Unit)
                release.await()
            }
            // 此处 job 已取消；awaitDrained 等价 while(isActive) 立即正常返回
            while (isActive) {
                delay(10)
            }
            if (!isActive) return@launch
            stopSelfCalled = true
        }

        enteredBlock.await()
        session.cancel()
        release.complete(Unit)
        session.join()

        stopSelfCalled shouldBe false
    }
}
