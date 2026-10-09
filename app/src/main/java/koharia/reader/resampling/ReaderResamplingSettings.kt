package koharia.reader.resampling

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

internal data class ReaderResamplingSettings(
    val enabled: Boolean,
    val kernel: ResamplingKernel,
    val quality: ResamplingQuality,
) {
    val options get() = ResamplingOptions(kernel = kernel, quality = quality)

    companion object {
        fun resolve(enabled: Boolean, kernel: ResamplingKernel, quality: ResamplingQuality) =
            ReaderResamplingSettings(
                enabled,
                if (enabled) kernel else ResamplingKernel.MITCHELL,
                if (enabled) quality else ResamplingQuality.BALANCED,
            )
    }
}

val ResamplingKernel.stringRes: StringResource
    get() = when (this) {
        ResamplingKernel.MITCHELL -> MR.strings.reader_resampling_mitchell
        ResamplingKernel.CATMULL_ROM -> MR.strings.reader_resampling_bicubic
        ResamplingKernel.BILINEAR -> MR.strings.reader_resampling_bilinear
        ResamplingKernel.LANCZOS3 -> MR.strings.reader_resampling_lanczos
    }

val ResamplingQuality.stringRes: StringResource
    get() = when (this) {
        ResamplingQuality.SPEED -> MR.strings.reader_resampling_speed
        ResamplingQuality.BALANCED -> MR.strings.reader_resampling_balanced
        ResamplingQuality.DETAIL -> MR.strings.reader_resampling_detail
    }
