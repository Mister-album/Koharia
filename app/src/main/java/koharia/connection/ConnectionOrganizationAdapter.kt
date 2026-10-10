package koharia.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.flow.Flow

enum class ConnectionOrganizationPage {
    COLLECTIONS,
    READ_LISTS,
}

internal val LocalOrganizationScreenOwner = staticCompositionLocalOf<Screen?> { null }

data class ConnectionOrganizationEntry(
    val page: ConnectionOrganizationPage,
    val id: String,
    val name: String,
) {
    val key: String get() = "${page.name}:$id"
}

interface ConnectionOrganizationDirectory {
    val namespace: String
    val changes: Flow<Unit>

    fun checkActive()

    suspend fun entries(pages: List<ConnectionOrganizationPage>, refresh: Boolean): List<ConnectionOrganizationEntry>
}

/** Optional server organization destinations; protocol and ownership stay with the connection. */
interface ConnectionOrganizationAdapter {
    val organizationPages: Set<ConnectionOrganizationPage>

    fun organizationScreen(page: ConnectionOrganizationPage): Screen

    val organizationNamespace: String

    fun organizationDirectory(): ConnectionOrganizationDirectory

    /** Root content places pageTabs below its toolbar and retains page/account-specific state. */
    @Composable
    fun OrganizationContent(
        page: ConnectionOrganizationPage,
        entryId: String?,
        pageTabs: @Composable () -> Unit,
        onRefresh: () -> Unit,
    )

    fun organizationEntryDestination(resourceUrl: String): Screen? = null
}

interface ConnectionOrganizationActionsAdapter {
    fun entryOrganizationScreen(resourceUrls: List<String>): Screen

    fun chapterOrganizationScreen(chapterUrls: List<String>): Screen
}
