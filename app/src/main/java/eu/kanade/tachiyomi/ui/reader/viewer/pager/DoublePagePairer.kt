package eu.kanade.tachiyomi.ui.reader.viewer.pager

internal object DoublePagePairer {

    data class Slot(
        val first: Int,
        val second: Int?,
    )

    fun pair(
        soloPages: List<Boolean>,
        shift: Boolean,
        preservePairBoundaries: Boolean = false,
        canPair: (first: Int, second: Int) -> Boolean = { _, _ -> true },
    ): List<Slot> {
        val slots = mutableListOf<Slot>()
        var shiftPending = shift
        var index = 0
        while (index < soloPages.size) {
            if (shiftPending && preservePairBoundaries) {
                slots += Slot(index, null)
                shiftPending = false
                index++
                continue
            }
            if (soloPages[index]) {
                slots += Slot(index, null)
                val boundaryPartner = index + 1
                if (preservePairBoundaries && !shiftPending && boundaryPartner < soloPages.size) {
                    slots += Slot(boundaryPartner, null)
                    index += 2
                } else {
                    index++
                }
                continue
            }
            if (shiftPending) {
                slots += Slot(index, null)
                shiftPending = false
                index++
                continue
            }

            val next = index + 1
            if (next < soloPages.size && soloPages[next] && preservePairBoundaries) {
                slots += Slot(index, null)
                slots += Slot(next, null)
                index += 2
            } else if (next < soloPages.size && !soloPages[next]) {
                if (canPair(index, next)) {
                    slots += Slot(index, next)
                    index += 2
                } else if (preservePairBoundaries) {
                    slots += Slot(index, null)
                    slots += Slot(next, null)
                    index += 2
                } else {
                    slots += Slot(index, null)
                    index++
                }
            } else {
                slots += Slot(index, null)
                index++
            }
        }
        return slots
    }
}
