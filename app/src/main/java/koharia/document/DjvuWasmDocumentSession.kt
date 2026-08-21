package koharia.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hippo.unifile.UniFile
import org.json.JSONTokener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.sqrt

/**
 * DJVU decoder backed by the MIT-licensed djvu-rs WASM build. The WASM is executed by the
 * platform WebView's JavaScript/WebAssembly runtime (V8 where provided).
 */
internal class DjvuWasmDocumentSession private constructor(
    private val runtime: DjvuWebViewRuntime,
    override val metadata: DocumentMetadata,
) : DocumentSession {

    override val pageCount: Int = runtime.pageCount

    override fun page(index: Int): DocumentPage {
        if (index !in 0 until pageCount) error("Invalid document page")
        return DjvuWasmPage(index)
    }

    override fun close() {
        runtime.close()
    }

    private inner class DjvuWasmPage(
        override val index: Int,
    ) : DocumentPage {
        override fun render(): Bitmap = runtime.renderPage(index)
    }

    companion object {
        fun open(context: android.content.Context, file: UniFile): DocumentSession {
            val runtime = try {
                DjvuWebViewRuntime.open(context.applicationContext, file)
            } catch (error: DocumentEngineException) {
                throw error
            } catch (error: Exception) {
                throw DocumentEngineException("Invalid or unsupported DJVU document", error)
            }
            return DjvuWasmDocumentSession(
                runtime = runtime,
                metadata = DocumentMetadata(title = file.name?.substringBeforeLast('.')),
            )
        }
    }
}

private class DjvuWebViewRuntime private constructor(
    private val webView: WebView,
    private val mainHandler: Handler,
    val pageCount: Int,
) {
    private val lock = Any()
    private var closed = false

    fun renderPage(index: Int): Bitmap {
        synchronized(lock) {
            check(!closed) { "DJVU session is closed" }
            val dpi = targetDpi(pageCount)
            val result = CompletableFuture<String>()
            val script = "(function(){try{return window.kohariaDjvu.render($index,$dpi)}" +
                "catch(e){return 'error:'+(e&&e.message?e.message:String(e))}})()"
            mainHandler.post {
                webView.evaluateJavascript(script) { rawResult ->
                    val value = decodeJavascriptString(rawResult)
                    if (value == null) {
                        result.completeExceptionally(DocumentEngineException("DJVU renderer returned no image"))
                    } else if (value.startsWith("error:")) {
                        result.completeExceptionally(DocumentEngineException(value.removePrefix("error:")))
                    } else {
                        result.complete(value)
                    }
                }
            }
            val dataUrl = await(result, RENDER_TIMEOUT_SECONDS, "DJVU page rendering timed out")
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) throw DocumentEngineException("DJVU renderer returned an invalid image")
            val encoded = dataUrl.substring(comma + 1)
            val bytes = try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (error: IllegalArgumentException) {
                throw DocumentEngineException("DJVU renderer returned invalid image data", error)
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw DocumentEngineException("DJVU renderer returned an unreadable image")
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            mainHandler.post {
                webView.evaluateJavascript("window.kohariaDjvu && window.kohariaDjvu.close()", null)
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    private fun targetDpi(pageCount: Int): Int {
        // The page dimensions are queried by the JS decoder. A conservative 180 DPI cap keeps
        // the JPEG callback below WebView's IPC limit while still matching a 1080px-wide screen.
        return min(MAX_DJVU_DPI, maxOf(MIN_DJVU_DPI, 180 - (pageCount / 1000)))
    }

    companion object {
        private const val BASE_URL = "https://koharia.local/"
        private const val WASM_URL = "https://koharia.local/djvu_rs_bg.wasm"
        private const val DOCUMENT_URL = "https://koharia.local/document.djvu"
        private const val HTML_ASSET = "djvu/djvu_renderer.html"
        private const val WASM_ASSET = "djvu/djvu_rs_bg.wasm"
        private const val RENDER_TIMEOUT_SECONDS = 30L
        private const val OPEN_TIMEOUT_SECONDS = 30L
        private const val MIN_DJVU_DPI = 72
        private const val MAX_DJVU_DPI = 180

        fun open(context: android.content.Context, file: UniFile): DjvuWebViewRuntime {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw DocumentEngineException("DJVU reader cannot initialize on the main thread")
            }

            val mainHandler = Handler(Looper.getMainLooper())
            val runtimeFuture = CompletableFuture<DjvuWebViewRuntime>()
            val webViewReference = AtomicReference<WebView?>()
            mainHandler.post {
                try {
                    val webView = WebView(context)
                    webViewReference.set(webView)
                    webView.layoutParams = ViewGroup.LayoutParams(1, 1)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = false
                    webView.settings.allowFileAccess = false
                    webView.settings.allowContentAccess = false
                    var started = false
                    val client = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            return intercept(context, file, request.url.toString())
                        }

                        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                            return intercept(context, file, url)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (started || url != BASE_URL) return
                            started = true
                            view.evaluateJavascript("window.kohariaDjvu.start()", null)
                            pollStatus(view, mainHandler, runtimeFuture)
                        }
                    }
                    webView.webViewClient = client
                    val html = context.assets.open(HTML_ASSET).bufferedReader().use { it.readText() }
                    webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
                } catch (error: Exception) {
                    runtimeFuture.completeExceptionally(error)
                }
            }

            return try {
                await(runtimeFuture, OPEN_TIMEOUT_SECONDS, "DJVU decoder initialization timed out")
            } catch (error: Exception) {
                webViewReference.get()?.let { webView ->
                    mainHandler.post { webView.destroy() }
                }
                throw error
            }
        }

        private fun intercept(
            context: android.content.Context,
            file: UniFile,
            url: String,
        ): WebResourceResponse? {
            return when (url.substringBefore('?')) {
                WASM_URL -> WebResourceResponse(
                    "application/wasm",
                    null,
                    context.assets.open(WASM_ASSET),
                )
                DOCUMENT_URL -> WebResourceResponse(
                    "image/vnd.djvu",
                    null,
                    file.openInputStream(),
                )
                else -> null
            }
        }

        private fun pollStatus(
            webView: WebView,
            mainHandler: Handler,
            result: CompletableFuture<DjvuWebViewRuntime>,
        ) {
            if (result.isDone) return
            webView.evaluateJavascript("window.kohariaDjvu.status()") { rawResult ->
                val status = decodeJavascriptString(rawResult)
                if (status == null) {
                    result.completeExceptionally(DocumentEngineException("DJVU renderer returned no status"))
                    return@evaluateJavascript
                }
                try {
                    val json = JSONTokener(status).nextValue() as? org.json.JSONObject
                        ?: throw DocumentEngineException("DJVU renderer returned invalid status")
                    when (json.optString("status")) {
                        "ready" -> result.complete(
                            DjvuWebViewRuntime(
                                webView = webView,
                                mainHandler = mainHandler,
                                pageCount = json.optInt("pages"),
                            ),
                        )
                        "error" -> result.completeExceptionally(
                            DocumentEngineException(json.optString("error", "DJVU decoder failed")),
                        )
                        else -> mainHandler.postDelayed(
                            { pollStatus(webView, mainHandler, result) },
                            STATUS_POLL_MILLIS,
                        )
                    }
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
        }

        private fun decodeJavascriptString(rawResult: String?): String? {
            if (rawResult.isNullOrBlank() || rawResult == "null") return null
            return runCatching { JSONTokener(rawResult).nextValue() as? String }.getOrNull()
        }

        private fun <T> await(
            future: CompletableFuture<T>,
            timeoutSeconds: Long,
            timeoutMessage: String,
        ): T {
            return try {
                future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (error: TimeoutException) {
                throw DocumentEngineException(timeoutMessage, error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DocumentEngineException(timeoutMessage, error)
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is DocumentEngineException) throw cause
                throw DocumentEngineException(timeoutMessage, cause)
            }
        }

        private const val STATUS_POLL_MILLIS = 50L
    }
}
