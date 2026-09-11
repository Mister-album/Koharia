package koharia.lanraragi

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionProfileManager
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Measures real image bodies without clearing any existing page cache or recording reading progress. */
@RunWith(AndroidJUnit4::class)
class LanraragiStartupTimingDeviceTest {
    @Test
    fun measurePublicDemoFileListTransferAndDecodeSeparately() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLanraragiDemo") == "true")
        val manager = Injekt.get<ConnectionProfileManager>()
        val profile = manager.add(LanraragiConnectionProvider.ID, "Demo startup timings")
        LanraragiPreferences(profile.id).save("https://lrr.tvc-16.science/", "")
        val source = withTimeout(10_000) {
            while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(100)
            Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
        }
        source.refreshLibrary().getOrThrow()
        val archive = source.repository.entries(source.id).first {
            it.title.contains("Saturn", true) &&
                it.pageCount in 1..10
        }
        val chapter = eu.kanade.tachiyomi.source.model.SChapter.create().apply { url = source.resourceUrl(archive) }
        val filesStart = System.nanoTime()
        val pages = source.getConnectionPageList(chapter, false).pages
        val filesMs = (System.nanoTime() - filesStart) / 1_000_000
        for (page in listOf(pages.first(), pages[3])) {
            val start = System.nanoTime()
            val bytes = source.getImage(page).use { it.body.bytes() }
            val transferMs = (System.nanoTime() - start) / 1_000_000
            val decodeStart = System.nanoTime()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(bitmap)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
            println(
                "LANraragi timing: page=${page.index + 1} filesMs=$filesMs transferMs=$transferMs " +
                    "decodeMs=$decodeMs bytes=${bytes.size} dimensions=${bitmap.width}x${bitmap.height}",
            )
            bitmap.recycle()
        }
        assertTrue(source.repository.readStates(source.id).none { it.pending })
    }
}
