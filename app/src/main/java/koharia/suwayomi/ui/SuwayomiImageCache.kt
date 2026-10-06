package koharia.suwayomi.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import coil3.imageLoader
import eu.kanade.tachiyomi.network.await
import koharia.suwayomi.SuwayomiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Uses the existing evictable image disk cache with an authenticated, account-scoped key. */
internal object SuwayomiImageCache {
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val locks = Array(16) { Mutex() }

    fun key(api: SuwayomiApi, url: String, maxWidth: Int) = "$maxWidth:${api.resourceCacheKey(url)}"
    fun peek(key: String) = memory.get(key)

    suspend fun load(context: Context, client: OkHttpClient, api: SuwayomiApi, url: String, maxWidth: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = key(api, url, maxWidth)
            locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                memory.get(key)?.let { return@withLock it }
                try {
                    val disk = context.imageLoader.diskCache
                    val diskKey = "suwayomi:${api.resourceCacheKey(url)}"
                    val bytes = disk?.openSnapshot(diskKey)?.use { snapshot ->
                        disk.fileSystem.read(snapshot.data) { readByteArray() }
                    }
                    var bitmap = bytes?.let { decode(it, maxWidth) }
                    if (bitmap == null) {
                        val downloaded = client.newCall(api.resourceRequest(url)).await().use { response ->
                            if (!response.isSuccessful) return@withLock null
                            response.body.bytes()
                        }
                        bitmap = decode(downloaded, maxWidth) ?: return@withLock null
                        currentCoroutineContext().ensureActive()
                        disk?.openEditor(diskKey)?.let { editor ->
                            try {
                                disk.fileSystem.write(editor.data) { write(downloaded) }
                                editor.commit()
                            } catch (error: Exception) {
                                editor.abort()
                                if (error is CancellationException) throw error
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    memory.put(key, bitmap)
                    bitmap
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
            }
        }

    private fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxWidth * 2) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
            },
        )
    }
}
