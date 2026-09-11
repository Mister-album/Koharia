package koharia.lanraragi

import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Measure consumption, not just HTTP headers; never report URLs, titles or credentials. */
internal class LanraragiTimedBody(
    private val responseBody: ResponseBody,
    private val beforeRead: () -> Unit = {},
    private val onFinished: (bytes: Long, complete: Boolean) -> Unit,
) : ResponseBody() {
    private var bytes = 0L
    private var reported = false
    private val stream by lazy {
        object : ForwardingSource(responseBody.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                beforeRead()
                val count = super.read(sink, byteCount)
                beforeRead()
                if (count >= 0) bytes += count else report(true)
                return count
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    report(responseBody.contentLength() >= 0 && bytes == responseBody.contentLength())
                }
            }
        }.buffer()
    }

    private fun report(complete: Boolean) {
        if (reported) return
        reported = true
        onFinished(bytes, complete)
    }

    override fun contentType() = responseBody.contentType()
    override fun contentLength() = responseBody.contentLength()
    override fun source() = stream
}
