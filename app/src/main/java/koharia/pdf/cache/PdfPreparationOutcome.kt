package koharia.pdf.cache

internal sealed interface PdfPreparationOutcome {
    data object Completed : PdfPreparationOutcome
    data class Failed(val error: Throwable) : PdfPreparationOutcome
}
