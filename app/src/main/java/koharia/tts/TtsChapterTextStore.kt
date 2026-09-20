package koharia.tts

/**
 * 进程内章节正文暂存。
 *
 * 背景：旧实现把整章正文经 `Intent.putExtra` 传进 [TtsService]，正文会被 Binder
 * 序列化进事务缓冲；Binder 事务上限约 1MB，较大的单文件 EPUB 章节会直接抛
 * [android.os.TransactionTooLargeException]。
 *
 * 现在调用方只把 [put] 的短 token 放进 Intent，Service 用 [take] 一次性取回正文
 * （取出即移除）。Service 与阅读器同进程，经本单例直接共享内存，无需任何 IPC。
 *
 * 线程安全：阅读器（主线程）与 Service（IO 线程）会并发访问。[entries] 与
 * [insertionOrder] 必须**一起**保持一致（`insertionOrder.size` 就是容量判据本身），
 * 故所有操作都在同一把 [lock] 内完成 —— 改用两个并发容器会让二者在并发 put/take 下
 * 失去同步（淘汰顺序与计数漂移，可能提前淘汰或漏淘汰）。
 *
 * 容量：最多保留最近 [MAX_ENTRIES] 条，超出时淘汰最旧的，避免 Service 未消费时泄漏。
 */
class TtsChapterTextStore {

    private val lock = Any()
    private val entries = HashMap<String, String>()
    private val insertionOrder = ArrayDeque<String>()

    /** 暂存正文。同一 token 重复 put 时覆盖正文，且不重复计入淘汰顺序。 */
    fun put(token: String, text: String) {
        synchronized(lock) {
            if (entries.put(token, text) == null) {
                insertionOrder.addLast(token)
                evictOverflow()
            }
        }
    }

    /** 取出并移除正文；token 不存在时返回 null。 */
    fun take(token: String): String? = synchronized(lock) {
        val value = entries.remove(token) ?: return@synchronized null
        insertionOrder.remove(token)
        value
    }

    /** 清空全部暂存。 */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            insertionOrder.clear()
        }
    }

    private fun evictOverflow() {
        while (insertionOrder.size > MAX_ENTRIES) {
            val oldest = insertionOrder.removeFirstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 4
    }
}
