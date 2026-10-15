package koharia.komga

import io.mockk.coEvery
import io.mockk.mockk
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaProgressOrdering

internal fun testProgressOrdering(intents: KomgaReadStatusRepository): KomgaProgressOrdering {
    coEvery { intents.attempt(any()) } returns null
    coEvery { intents.attempts(any(), any()) } returns emptyList()
    val pages = mockk<KomgaPageEventRepository> {
        coEvery { attempt(any()) } returns null
    }
    return KomgaProgressOrdering(intents, pages)
}
