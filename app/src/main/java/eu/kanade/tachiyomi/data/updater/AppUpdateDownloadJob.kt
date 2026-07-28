package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = AppUpdateNotifier(context)
    private val network: NetworkHelper by injectLazy()
    private val downloadClient by lazy {
        network.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result {
        val spec = inputData.toDownloadSpec(context.stringResource(MR.strings.app_name)) ?: return Result.failure()

        setForegroundSafely()

        return try {
            downloadMutex.withLock {
                withIOContext {
                    downloadApk(spec)
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            notifier.cancel()
            throw error
        } catch (error: RetryableDownloadException) {
            logcat(LogPriority.WARN, error) { "App update download failed temporarily" }
            if (runAttemptCount < MAX_AUTO_RETRIES) {
                notifier.onDownloadRetrying(spec.title)
                Result.retry()
            } else {
                notifier.onDownloadError(
                    urls = spec.urls,
                    title = spec.title,
                    expectedSize = spec.expectedSize,
                    expectedSha256 = spec.expectedSha256,
                )
                Result.failure()
            }
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "App update download failed" }
            notifier.onDownloadError(
                urls = spec.urls,
                title = spec.title,
                expectedSize = spec.expectedSize,
                expectedSha256 = spec.expectedSha256,
            )
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_APP_UPDATER,
            notifier.onDownloadStarted().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun downloadApk(spec: DownloadSpec) {
        notifier.onDownloadStarted(spec.title)

        val cacheDirectory = context.externalCacheDir ?: context.cacheDir
        val partialKey = Hash.sha256(spec.expectedSha256 ?: spec.urls.first()).take(16)
        val partialFile = File(cacheDirectory, "$UPDATE_PART_FILE_PREFIX$partialKey$UPDATE_PART_FILE_SUFFIX")
        val apkFile = File(cacheDirectory, UPDATE_FILE_NAME)
        cacheDirectory.listFiles()
            .orEmpty()
            .filter { file ->
                file != partialFile &&
                    (
                        file.name == LEGACY_UPDATE_PART_FILE_NAME ||
                            (
                                file.name.startsWith(UPDATE_PART_FILE_PREFIX) &&
                                    file.name.endsWith(UPDATE_PART_FILE_SUFFIX)
                                )
                        )
            }
            .forEach { it.delete() }
        apkFile.delete()

        if (spec.expectedSize != null && partialFile.length() == spec.expectedSize) {
            try {
                validateApk(partialFile, spec)
                completeDownload(partialFile, apkFile)
                return
            } catch (error: DownloadIntegrityException) {
                partialFile.delete()
                logcat(LogPriority.WARN, error) { "Discarding an invalid completed update download" }
            } catch (error: TerminalDownloadException) {
                partialFile.delete()
                throw error
            }
        } else if (spec.expectedSize != null && partialFile.length() > spec.expectedSize) {
            partialFile.delete()
        }

        var lastFailure: Throwable? = null
        var hasRetryableFailure = false
        spec.urls.forEachIndexed { index, url ->
            try {
                downloadFromSource(
                    url = url,
                    sourceIndex = index,
                    partialFile = partialFile,
                    expectedSize = spec.expectedSize,
                )
                validateApk(partialFile, spec)
                completeDownload(partialFile, apkFile)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: SourceDownloadException) {
                lastFailure = error
                logcat(LogPriority.WARN, error) {
                    "App update source ${index + 1}/${spec.urls.size} rejected (${url.safeHost()})"
                }
            } catch (error: TerminalDownloadException) {
                partialFile.delete()
                throw error
            } catch (error: Exception) {
                if (isStopped || error.isCancellation()) {
                    throw CancellationException("App update download cancelled").apply { initCause(error) }
                }
                if (error is DownloadIntegrityException) {
                    partialFile.delete()
                }
                hasRetryableFailure = true
                lastFailure = error
                logcat(LogPriority.WARN, error) {
                    "App update source ${index + 1}/${spec.urls.size} failed (${url.safeHost()})"
                }
            }
        }

        if (hasRetryableFailure) {
            throw RetryableDownloadException("All app update download sources failed", lastFailure)
        }
        throw TerminalDownloadException("All app update download sources were rejected", lastFailure)
    }

    private suspend fun downloadFromSource(
        url: String,
        sourceIndex: Int,
        partialFile: File,
        expectedSize: Long?,
    ) {
        val requestedOffset = partialFile.length().coerceAtLeast(0L)
        val request = try {
            GET(url).newBuilder().apply {
                header("Accept-Encoding", "identity")
                if (requestedOffset > 0L) {
                    header("Range", "bytes=$requestedOffset-")
                }
            }.build()
        } catch (error: IllegalArgumentException) {
            throw SourceDownloadException("Invalid app update URL", error)
        }

        val progressListener = DownloadProgressListener()
        val response = downloadClient
            .newCachelessCallWithProgress(request, progressListener)
            .await()

        response.use {
            logResponse(it, sourceIndex, url)
            val contentType = it.body.contentType()
            if (it.code in 200..299 && contentType != null &&
                (contentType.type == "text" || contentType.subtype.contains("json", ignoreCase = true))
            ) {
                partialFile.delete()
                throw RetryableDownloadException("Update source returned non-APK content")
            }

            val append: Boolean
            val writeOffset: Long
            val totalSize: Long?
            when (it.code) {
                200 -> {
                    append = false
                    writeOffset = 0L
                    totalSize = expectedSize ?: it.body.contentLength().takeIf { length -> length > 0L }
                }
                206 -> {
                    val contentRange = AppUpdateDownloadPolicy.parseContentRange(it.header("Content-Range"))
                    if (contentRange == null || contentRange.start != requestedOffset) {
                        partialFile.delete()
                        throw RetryableDownloadException("Invalid Content-Range from update source")
                    }
                    if (expectedSize != null && contentRange.total != null && contentRange.total != expectedSize) {
                        partialFile.delete()
                        throw DownloadIntegrityException("Content-Range size does not match release metadata")
                    }
                    append = true
                    writeOffset = requestedOffset
                    totalSize = expectedSize ?: contentRange.total
                }
                416 -> {
                    if (expectedSize != null && requestedOffset == expectedSize) return
                    partialFile.delete()
                    throw RetryableDownloadException("Update source rejected the resume offset")
                }
                else -> if (AppUpdateDownloadPolicy.isRetryableHttpCode(it.code)) {
                    throw RetryableDownloadException("Temporary HTTP ${it.code} from update source")
                } else {
                    throw SourceDownloadException("HTTP ${it.code} from update source")
                }
            }

            progressListener.setRange(writeOffset, totalSize)
            partialFile.parentFile?.mkdirs()
            it.body.source().use { source ->
                FileOutputStream(partialFile, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0L) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        }

        expectedSize?.let { size ->
            when {
                partialFile.length() < size -> {
                    throw RetryableDownloadException("App update download ended before the expected size")
                }
                partialFile.length() > size -> {
                    partialFile.delete()
                    throw DownloadIntegrityException("App update download exceeded the expected size")
                }
            }
        }
    }

    private fun validateApk(file: File, spec: DownloadSpec) {
        spec.expectedSize?.let { expectedSize ->
            if (file.length() != expectedSize) {
                throw DownloadIntegrityException("App update size does not match release metadata")
            }
        }

        spec.expectedSha256?.let { expectedSha256 ->
            val actualSha256 = file.sha256()
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw DownloadIntegrityException("App update SHA-256 does not match release metadata")
            }
        }

        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?: throw DownloadIntegrityException("Downloaded file is not a valid APK")
        if (archive.packageName != context.packageName) {
            throw TerminalDownloadException("Downloaded APK package does not match the installed app")
        }

        val installed = packageManager.getPackageInfo(context.packageName, PACKAGE_FLAGS)
        val installedSignatures = installed.signaturesSha256()
        val archiveSignatures = archive.signaturesSha256()
        if (installedSignatures.isEmpty() || !archiveSignatures.containsAll(installedSignatures)) {
            throw TerminalDownloadException("Downloaded APK signature does not match the installed app")
        }
    }

    private fun completeDownload(partialFile: File, apkFile: File) {
        if (!partialFile.renameTo(apkFile)) {
            partialFile.copyTo(apkFile, overwrite = true)
            partialFile.delete()
        }
        notifier.cancel()
        notifier.promptInstall(apkFile.getUriCompat(context))
    }

    private fun logResponse(response: Response, sourceIndex: Int, url: String) {
        logcat(LogPriority.DEBUG) {
            buildString {
                append("App update response source=")
                append(sourceIndex + 1)
                append(" host=")
                append(url.safeHost())
                append(" code=")
                append(response.code)
                append(" cache=")
                append(response.header("X-Koharia-Cache"))
                append(" upstream=")
                append(response.header("X-Koharia-Source"))
                append(" rangePolicy=")
                append(response.header("X-Koharia-Range-Policy"))
                append(" contentLength=")
                append(response.header("Content-Length"))
                append(" contentRange=")
                append(response.header("Content-Range"))
                append(" etag=")
                append(response.header("ETag"))
            }
        }
    }

    private inner class DownloadProgressListener : ProgressListener {
        private var offset = 0L
        private var totalSize: Long? = null
        private var savedProgress = -1
        private var lastTick = 0L
        private var hasShownIndeterminateProgress = false

        fun setRange(offset: Long, totalSize: Long?) {
            this.offset = offset
            this.totalSize = totalSize
        }

        override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
            val currentTime = System.currentTimeMillis()
            val total = totalSize ?: contentLength.takeIf { it > 0L }?.let { offset + it }
            if (total == null || total <= 0L) {
                if (!hasShownIndeterminateProgress || done) {
                    hasShownIndeterminateProgress = true
                    lastTick = currentTime
                    notifier.onProgressChange(null)
                }
                return
            }

            val progress = ((100 * (offset + bytesRead)) / total)
                .toInt()
                .coerceIn(0, 100)
            if ((progress > savedProgress && currentTime - NOTIFICATION_UPDATE_INTERVAL > lastTick) || done) {
                savedProgress = progress
                lastTick = currentTime
                notifier.onProgressChange(progress)
            }
        }
    }

    private data class DownloadSpec(
        val urls: List<String>,
        val title: String,
        val expectedSize: Long?,
        val expectedSha256: String?,
    )

    private class RetryableDownloadException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private class DownloadIntegrityException(message: String) : IOException(message)

    private class SourceDownloadException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private class TerminalDownloadException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    companion object {
        private const val TAG = "AppUpdateDownload"
        private const val UPDATE_FILE_NAME = "update.apk"
        private const val LEGACY_UPDATE_PART_FILE_NAME = "update.apk.part"
        private const val UPDATE_PART_FILE_PREFIX = "update-"
        private const val UPDATE_PART_FILE_SUFFIX = ".apk.part"
        private const val MAX_AUTO_RETRIES = 2
        private const val NOTIFICATION_UPDATE_INTERVAL = 200L
        private val downloadMutex = Mutex()

        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

        const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val EXTRA_DOWNLOAD_URLS = "DOWNLOAD_URLS"
        const val EXTRA_DOWNLOAD_TITLE = "DOWNLOAD_TITLE"
        const val EXTRA_EXPECTED_SIZE = "EXPECTED_SIZE"
        const val EXTRA_EXPECTED_SHA256 = "EXPECTED_SHA256"

        fun start(context: Context, url: String, title: String? = null) {
            start(context, listOf(url), title)
        }

        fun start(
            context: Context,
            urls: List<String>,
            title: String? = null,
            expectedSize: Long? = null,
            expectedSha256: String? = null,
        ) {
            val normalizedUrls = urls.filter(String::isNotBlank).distinct()
            if (normalizedUrls.isEmpty()) return

            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )
            val inputData = Data.Builder()
                .putString(EXTRA_DOWNLOAD_URL, normalizedUrls.first())
                .putStringArray(EXTRA_DOWNLOAD_URLS, normalizedUrls.toTypedArray())
                .putString(EXTRA_DOWNLOAD_TITLE, title)
                .putLong(EXTRA_EXPECTED_SIZE, expectedSize ?: -1L)
                .putString(EXTRA_EXPECTED_SHA256, expectedSha256)
                .build()

            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .setInputData(inputData)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        private fun Data.toDownloadSpec(defaultTitle: String): DownloadSpec? {
            val urls = getStringArray(EXTRA_DOWNLOAD_URLS)
                ?.filter(String::isNotBlank)
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(getString(EXTRA_DOWNLOAD_URL))
            if (urls.isEmpty()) return null

            return DownloadSpec(
                urls = urls.distinct(),
                title = getString(EXTRA_DOWNLOAD_TITLE).orEmpty().ifBlank { defaultTitle },
                expectedSize = getLong(EXTRA_EXPECTED_SIZE, -1L).takeIf { it > 0L },
                expectedSha256 = getString(EXTRA_EXPECTED_SHA256)
                    ?.takeIf { SHA256_REGEX.matches(it) },
            )
        }

        private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")

        private fun String.safeHost(): String = toHttpUrlOrNull()?.host ?: "invalid"

        private fun Throwable.isCancellation(): Boolean {
            return this is StreamResetException && errorCode == ErrorCode.CANCEL
        }

        private fun File.sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun PackageInfo.signaturesSha256(): List<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = signingInfo ?: return emptyList()
                if (info.hasMultipleSigners()) {
                    info.apkContentsSigners
                } else {
                    info.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                signatures
            }
            return signatures.orEmpty().map { Hash.sha256(it.toByteArray()) }
        }
    }
}
