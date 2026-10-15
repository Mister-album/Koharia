package koharia.connection

import org.readium.r2.shared.publication.Locator
import java.util.Date

/** Direct transports acknowledge on return; durable queues must confirm the exact submitted value. */
suspend fun ConnectionEpubProgressAdapter.pushAndConfirmEpubProgress(
    resourceId: String,
    locator: Locator,
    positions: List<Locator>,
    modifiedAt: Date,
): Boolean {
    pushEpubProgress(resourceId, locator, positions, modifiedAt)
    return this !is ConnectionLocalEpubProgressAdapter || isEpubProgressConfirmed(resourceId, locator, modifiedAt)
}
