package koharia.epub.service

import koharia.pdf.cache.PdfProgressivePublication
import kotlinx.coroutines.CancellationException
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.resource.FailureResource
import org.readium.r2.shared.util.resource.LazyResource
import org.readium.r2.shared.util.resource.Resource

internal fun Publication.Builder.installProgressivePdf(preparation: PdfProgressivePublication) {
    container = PdfProgressiveContainer(container, preparation)
}

private class PdfProgressiveContainer(
    private val delegate: Container<Resource>,
    private val preparation: PdfProgressivePublication,
) : Container<Resource> {
    override val sourceUrl = delegate.sourceUrl
    override val entries get() = delegate.entries

    override fun get(url: Url): Resource? {
        val path = url.toString().substringBefore('#').trimStart('/')
        if (!preparation.serves(path)) return delegate[url]
        return LazyResource {
            try {
                FileResource(preparation.resource(path))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                FailureResource(ReadError.Decoding(error))
            }
        }
    }

    override fun close() = delegate.close()
}
