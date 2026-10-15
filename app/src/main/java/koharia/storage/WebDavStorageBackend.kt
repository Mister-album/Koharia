package koharia.storage

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

class WebDavStorageBackend(
    client: OkHttpClient,
    address: String,
    username: String,
    password: String,
) : LibraryStorageBackend {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val root = address.toHttpUrl().let {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
        if (it.encodedPath.endsWith('/')) it else it.newBuilder().addPathSegment("").build()
    }
    private val authorization = username.takeIf(String::isNotEmpty)?.let { Credentials.basic(it, password) }
    override val capabilities = StorageCapabilities(true, true, true, true)
    private fun url(path: String): HttpUrl = root.newBuilder().apply {
        StoragePath.normalize(path).split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
    }.build()
    private fun request(path: String) = Request.Builder().url(url(path)).apply {
        authorization?.let { header("Authorization", it) }
        header("Accept-Encoding", "identity")
    }
    private suspend fun execute(request: Request): Response {
        val response = client.newCall(request).await()
        if (response.code !in 200..299) {
            val reason = when (response.code) {
                401 -> StorageFailure.Reason.AUTH
                403 -> StorageFailure.Reason.PERMISSION
                404 -> StorageFailure.Reason.NOT_FOUND
                409, 412, 423 -> StorageFailure.Reason.CONFLICT
                405, 501 -> StorageFailure.Reason.UNSUPPORTED
                507 -> StorageFailure.Reason.SPACE
                else -> StorageFailure.Reason.NETWORK
            }
            response.close()
            throw StorageFailure(reason)
        }
        return response
    }

    /**
     * Confirms that these credentials reach the server. A reachable root that is not itself a DAV
     * collection (redirect, 404, HTML index, or a folder the account cannot read) is not reported as
     * an authentication failure: the DAV collection is selected in the next connection setup step.
     */
    suspend fun checkEndpointReachable() = withContext(Dispatchers.IO) {
        val query = request("").header("Depth", "0").method("PROPFIND", PROPERTIES.toRequestBody(XML)).build()
        client.newCall(query).await().use { response ->
            if (response.code == 401) throw StorageFailure(StorageFailure.Reason.AUTH)
        }
    }
    private suspend fun properties(path: String, depth: String): List<StorageEntry> = withContext(Dispatchers.IO) {
        execute(request(path).header("Depth", depth).method("PROPFIND", PROPERTIES.toRequestBody(XML)).build()).use {
            if (it.code != 207) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            val bytes = it.body.byteStream().readBounded(16 * 1024 * 1024)
            parseProperties(root, bytes)
        }
    }
    override suspend fun stat(path: String): StorageEntry =
        properties(path, "0").firstOrNull { it.path == StoragePath.normalize(path) }
            ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
    override suspend fun list(path: String): List<StorageEntry> {
        val normalized = StoragePath.normalize(path)
        val result = properties(normalized, "1")
        if (result.none { it.path == normalized && it.directory }) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
        return result.filter {
            it.path != normalized && StoragePath.parent(it.path) == normalized
        }.distinctBy { it.path }
    }
    override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        require(offset >= 0 && length >= 0 && offset <= entry.size && length.toLong() <= entry.size - offset)
        if (length == 0) return@withContext byteArrayOf()
        val query = request(entry.path).header("Range", "bytes=$offset-${offset + length - 1}").apply {
            entry.strongEtag()?.let { header("If-Match", it) }
        }.build()
        execute(query).use { response ->
            if (response.code != 206 && !(response.code == 200 && offset == 0L && length.toLong() == entry.size)) {
                throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            }
            if (response.code == 206 &&
                response.header("Content-Range") != "bytes $offset-${offset + length - 1}/${entry.size}"
            ) {
                throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            }
            val bytes = response.body.byteStream().readBounded(length)
            if (bytes.size != length) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            if (entry.strongEtag() == null && stat(entry.path).version != entry.version) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
            bytes
        }
    }
    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        execute(
            request(path).header("If-None-Match", "*").method("MKCOL", byteArrayOf().toRequestBody()).build(),
        ).close()
    }
    override suspend fun copyTo(entry: StorageEntry, output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        execute(
            request(entry.path).apply {
                entry.strongEtag()?.let { header("If-Match", it) }
            }.build(),
        ).use { response ->
            if (response.body.byteStream().copyTo(output) !=
                entry.size
            ) {
                throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            }
            if (entry.strongEtag() == null &&
                stat(entry.path).version != entry.version
            ) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
        }
    }
    override suspend fun write(
        path: String,
        data: InputStream,
        length: Long,
        expectedVersion: String?,
    ) = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                check(sink.writeAll(data.source()) == length)
            }
        }
        val query = request(path).apply {
            if (expectedVersion == null) {
                header("If-None-Match", "*")
            } else {
                if (!isStrongEtag(expectedVersion)) throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
                header("If-Match", expectedVersion)
            }
        }.put(body).build()
        execute(query).close()
    }
    override suspend fun move(entry: StorageEntry, destination: String) = withContext(Dispatchers.IO) {
        require(entry.path.isNotEmpty() && StoragePath.normalize(destination).isNotEmpty())
        withMutationPrecondition(entry) { query ->
            execute(
                query.header("Destination", url(destination).toString()).header("Overwrite", "F")
                    .method("MOVE", null).build(),
            ).close()
        }
    }
    override suspend fun delete(entry: StorageEntry) = withContext(Dispatchers.IO) {
        require(entry.path.isNotEmpty())
        withMutationPrecondition(entry) { query ->
            // A collection ETag need not change when a descendant is added. Check under the lock.
            if (entry.directory && list(entry.path).isNotEmpty()) throw StorageFailure(StorageFailure.Reason.CONFLICT)
            execute(query.delete().build()).close()
        }
    }

    private suspend fun withMutationPrecondition(entry: StorageEntry, mutation: suspend (Request.Builder) -> Unit) {
        val etag = entry.strongEtag()
        if (etag != null && !entry.directory) {
            checkCurrent(entry)
            mutation(request(entry.path).header("If-Match", etag))
            return
        }

        // Weak/missing validators cannot protect a mutation with If-Match. A DAV write lock
        // spans the fresh check and mutation; its If token also rejects an expired/lost lease.
        val response = try {
            execute(
                request(entry.path).header("If-Match", "*")
                    .header("Depth", if (entry.directory) "infinity" else "0")
                    .header("Timeout", "Second-60").method("LOCK", LOCK_BODY.toRequestBody(XML)).build(),
            )
        } catch (error: StorageFailure) {
            // Lack of a concurrency guard is not evidence that MOVE supports copy/delete fallback.
            if (error.reason ==
                StorageFailure.Reason.UNSUPPORTED
            ) {
                throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            }
            throw error
        }
        // Some DAV servers return the URI without the RFC's enclosing angle brackets.
        val token = response.header("Lock-Token")?.removeSurrounding("<", ">")?.takeIf { uri ->
            uri.isNotEmpty() && uri.none { it <= ' ' || it in "<>\"" } &&
                runCatching { URI(uri).isAbsolute }.getOrDefault(false)
        }?.let { "<$it>" } ?: run {
            response.close()
            throw StorageFailure(StorageFailure.Reason.PROTOCOL)
        }
        try {
            response.use {
                if (it.code != 200) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                val document = parseXml(it.body.byteStream().readBounded(64 * 1024))
                val locks = document.getElementsByTagNameNS("DAV:", "activelock")
                val lock = (0 until locks.length).map { index -> locks.item(index) as Element }.singleOrNull { lock ->
                    lock.getElementsByTagNameNS("DAV:", "locktoken").item(0)?.let { node ->
                        (node as Element).text("href") == token.substring(1, token.lastIndex)
                    } == true
                } ?: throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                val timeout = lock.text("timeout")?.takeIf { it.startsWith("Second-", ignoreCase = true) }
                    ?.substringAfter('-')?.toLongOrNull()
                if (lock.getElementsByTagNameNS("DAV:", "exclusive").length != 1 ||
                    lock.getElementsByTagNameNS("DAV:", "write").length != 1 ||
                    (entry.directory && lock.text("depth") != "infinity") || timeout == null || timeout !in 1..60
                ) {
                    throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
                }
            }
            checkCurrent(entry)
            mutation(
                request(entry.path).header("If", "($token)")
                    .header("Cache-Control", "no-cache").header("Pragma", "no-cache"),
            )
        } finally {
            releaseLock(entry.path, token)
        }
    }

    private suspend fun checkCurrent(entry: StorageEntry) {
        val current = stat(entry.path)
        if (current.version != entry.version || current.directory != entry.directory ||
            (entry.identity != null && current.identity != entry.identity)
        ) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
    }

    private suspend fun releaseLock(path: String, token: String) = withContext(NonCancellable) {
        try {
            withTimeout(5_000) {
                execute(request(path).header("Lock-Token", token).method("UNLOCK", null).build()).close()
            }
        } catch (error: Exception) {
            // Successful MOVE/DELETE can already remove the lock. Accepted leases are bounded.
            if (error !is StorageFailure ||
                error.reason !in setOf(StorageFailure.Reason.NOT_FOUND, StorageFailure.Reason.CONFLICT)
            ) {
                logcat(LogPriority.WARN) { "Could not release temporary WebDAV write lock" }
            }
        }
    }
    override fun close() = Unit

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val LOCK_BODY = """<?xml version="1.0"?>
            <d:lockinfo xmlns:d="DAV:"><d:lockscope><d:exclusive/></d:lockscope>
            <d:locktype><d:write/></d:locktype></d:lockinfo>"""
        private const val PROPERTIES = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/>
            <d:getlastmodified/><d:getetag/><d:resource-id/></d:prop></d:propfind>"""
        internal fun parseProperties(root: HttpUrl, bytes: ByteArray): List<StorageEntry> {
            val document = parseXml(bytes)
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            return (0 until responses.length).mapNotNull { index ->
                parsePropertyResponse(root, responses.item(index) as Element)
            }
        }

        private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
            // Android's DOM implementation lacks Xerces feature flags. Reject DTDs before parsing,
            // including UTF-16/32 markup, and block entity resolution independently of those flags.
            val markup = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
            if (markup.contains("<!DOCTYPE", ignoreCase = true) || markup.contains("<!ENTITY", ignoreCase = true)) {
                throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are disabled") }
            return builder.parse(bytes.inputStream())
        }

        private fun parsePropertyResponse(root: HttpUrl, response: Element): StorageEntry? {
            val target = root.resolve(response.text("href") ?: return null)
                ?: throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            if (target.scheme != root.scheme || target.host != root.host || target.port != root.port ||
                !target.encodedPath.trimEnd('/').let {
                    it == root.encodedPath.trimEnd('/') ||
                        it.startsWith(root.encodedPath)
                }
            ) {
                throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            }
            val rootSegments = root.pathSegments.filter(String::isNotEmpty)
            val path = target.pathSegments.filter(String::isNotEmpty).drop(rootSegments.size).also { segments ->
                if (segments.any { '/' in it || '\\' in it }) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            }.joinToString("/").let(StoragePath::normalize)
            val groups = response.getElementsByTagNameNS("DAV:", "propstat")
            val properties = (0 until groups.length).map { groups.item(it) as Element }
                .filter { it.text("status")?.substringAfter(' ')?.startsWith("200 ") == true }
            val directory = properties.any { it.getElementsByTagNameNS("DAV:", "collection").length > 0 }
            if (properties.isEmpty()) throw StorageFailure(StorageFailure.Reason.PERMISSION)
            fun property(name: String) = properties.firstNotNullOfOrNull { it.text(name) }
            val size = if (directory) {
                0L
            } else {
                property("getcontentlength")?.toLongOrNull()
                    ?: throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            }
            val modified = property("getlastmodified")?.let {
                runCatching {
                    ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                }.getOrNull()
            } ?: 0L
            return StorageEntry(
                path,
                directory,
                size,
                modified,
                property("getetag") ?: "$size:$modified",
                property("resource-id"),
            )
        }
        private fun Element.text(
            name: String,
        ): String? = getElementsByTagNameNS("DAV:", name).item(0)?.textContent?.trim()
        private fun isStrongEtag(value: String) = value.length >= 2 && value.first() == '"' && value.last() == '"' &&
            value.substring(1, value.lastIndex).all { it == '!' || it in '#'..'~' || it.code in 0x80..0xff }
        private fun StorageEntry.strongEtag() = version.takeIf(::isStrongEtag)
    }
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0)
    val output = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var remaining = limit
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    if (read() != -1) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
    return output.toByteArray()
}
