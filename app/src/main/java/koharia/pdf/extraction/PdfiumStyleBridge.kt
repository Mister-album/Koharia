package koharia.pdf.extraction

import androidx.annotation.Keep

/** Only accepts handles owned by the pinned PDFium core while its global lock is held. */
@Keep
internal object PdfiumStyleBridge {
    init {
        System.loadLibrary("koharia_pdf_styles")
    }

    external fun glyphs(textPage: Long, count: Int): DoubleArray
    external fun fontNameBytes(textPage: Long, index: Int): ByteArray?
    external fun graphics(page: Long): FloatArray
    external fun annotationBoxes(page: Long): FloatArray
    external fun annotationText(page: Long, index: Int): ByteArray?
}
