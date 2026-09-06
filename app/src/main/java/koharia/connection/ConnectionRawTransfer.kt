package koharia.connection

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val token = continuation.tryResumeWithException(e) ?: return
                    continuation.completeResume(token)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(block) }
                    result.fold(
                        onSuccess = { value ->
                            val token = continuation.tryResume(value) ?: return@fold
                            continuation.completeResume(token)
                        },
                        onFailure = { error ->
                            val token = continuation.tryResumeWithException(error) ?: return@fold
                            continuation.completeResume(token)
                        },
                    )
                }
            },
        )
    }
