package koharia.connection

data class ConnectionOrganizationNavigation(
    val pages: List<ConnectionOrganizationPage>,
    val combined: Boolean,
) {
    companion object {
        fun create(
            supportedPages: Set<ConnectionOrganizationPage>,
            showCollections: Boolean,
            showReadLists: Boolean,
            mergePages: Boolean,
        ): ConnectionOrganizationNavigation {
            val pages = ConnectionOrganizationPage.entries.filter { page ->
                page in supportedPages && when (page) {
                    ConnectionOrganizationPage.COLLECTIONS -> showCollections
                    ConnectionOrganizationPage.READ_LISTS -> showReadLists
                }
            }
            return ConnectionOrganizationNavigation(pages, mergePages && pages.size > 1)
        }
    }
}
