package koharia.kavita

import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.util.UUID

internal class KavitaPublicationDownload(
    private val session: KavitaSource.Session,
    private val directory: File,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath != session.api.url("Koharia/epub").encodedPath ||
            request.url.host != session.api.virtualBase.host
        ) {
            return chain.proceed(request)
        }
        val chapterId = request.url.queryParameter("chapterId")?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Invalid publication")
        fun check() {
            session.checkActive()
            if (chain.call().isCanceled()) throw IOException("Publication transfer cancelled")
        }
        check()
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "${UUID.randomUUID()}.epub")
        try {
            runBlocking { KavitaOfflineEpub(session.api, session.catalog).write(chapterId, file, ::check) }
            check()
            val source = object : ForwardingSource(file.source()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        file.delete()
                    }
                }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = "application/epub+zip".toMediaType()
                override fun contentLength() = file.length()
                override fun source(): BufferedSource = source
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Disposition", "attachment; filename=\"$chapterId.epub\"")
                .header("Content-Type", "application/epub+zip").body(body).build()
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
