package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from a directory given on [file].
 */
internal class DirectoryPageLoader(val file: UniFile, private val excludeAuxiliary: Boolean = false) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val files = file.listFiles().orEmpty().mapNotNull { child ->
            val name = child.name.orEmpty()
            child.takeIf {
                !it.isDirectory &&
                    (!excludeAuxiliary || !koharia.source.local.isLocalAuxiliaryFile(name)) &&
                    ImageUtil.isImage(it.name) { it.openInputStream() }
            }?.let { it to name }
        }
        return files
            .sortedWith { f1, f2 -> f1.second.compareToCaseInsensitiveNaturalOrder(f2.second) }
            .mapIndexed { i, file ->
                val streamFn = { file.first.openInputStream() }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
    }
}
