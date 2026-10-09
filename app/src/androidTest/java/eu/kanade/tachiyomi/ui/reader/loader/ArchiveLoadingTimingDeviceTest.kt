package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import koharia.core.archive.archiveReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in user fixtures: the same archive reader and enumeration path used by LocalPageLoader. */
class ArchiveLoadingTimingDeviceTest {
    @Test
    fun measureArchiveOpenEnumerationAndPageRead() = runBlocking {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val directory = args.getString("moireFixtureDir")
        val filename = args.getString("moireArchiveName")
        assumeNotNull(directory, filename)
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val inputDirectory = File(root, checkNotNull(directory)).canonicalFile
        require(inputDirectory.toPath().startsWith(root.toPath()) && inputDirectory != root)
        val input = File(inputDirectory, checkNotNull(filename)).canonicalFile
        require(input.parentFile == inputDirectory && input.isFile)
        val index = checkNotNull(args.getString("moirePage")).toInt() - 1
        val metrics = StringBuilder("round,pages,page,bytes,open_ms,enumerate_ms,stream_open_ms,read_ms,sha256\n")
        val output = File(inputDirectory, "archive-${System.currentTimeMillis()}.csv")
        try {
            repeat(3) { round ->
                val start = System.nanoTime()
                val loader = ArchivePageLoader(checkNotNull(UniFile.fromFile(input)).archiveReader(context))
                val opened = System.nanoTime()
                try {
                    val pages = loader.getPages()
                    val enumerated = System.nanoTime()
                    assertTrue(index in pages.indices)
                    val stream = checkNotNull(pages[index].stream).invoke()
                    val streamOpened = System.nanoTime()
                    val bytes = stream.use { checkNotNull(it).readBytes() }
                    val read = System.nanoTime()
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }
                    args.getString("moireExpectedSha256")?.let { assertEquals(it, digest) }
                    metrics.append(
                        "$round,${pages.size},${index + 1},${bytes.size}," +
                            "${(opened - start) / 1e6},${(enumerated - opened) / 1e6}," +
                            "${(streamOpened - enumerated) / 1e6},${(read - streamOpened) / 1e6},$digest\n",
                    )
                } finally {
                    loader.recycle()
                }
            }
        } finally {
            output.writeText(metrics.toString())
        }
    }
}
