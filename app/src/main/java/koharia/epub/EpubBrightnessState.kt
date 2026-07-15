package koharia.epub

internal data class EpubBrightnessState(
    val windowBrightness: Float?,
    val overlayValue: Int,
)

internal fun calculateEpubBrightness(
    enabled: Boolean,
    value: Int,
): EpubBrightnessState {
    if (!enabled || value == 0) {
        return EpubBrightnessState(
            windowBrightness = null,
            overlayValue = 0,
        )
    }

    val boundedValue = value.coerceIn(-75, 100)
    return if (boundedValue < 0) {
        EpubBrightnessState(
            windowBrightness = 0.01f,
            overlayValue = boundedValue,
        )
    } else {
        EpubBrightnessState(
            windowBrightness = boundedValue / 100f,
            overlayValue = 0,
        )
    }
}
