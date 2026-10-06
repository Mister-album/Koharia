package koharia.suwayomi

/**
 * Mutable state of one source filter. The server applies changes by the filter's index inside its
 * own list, so a change carries the position it was built from and groups nest their children.
 */
sealed interface SuwayomiFilterState {
    val filter: SuwayomiSourceFilter

    /** True when the current value differs from the filter's server-provided default. */
    val diverged: Boolean

    /** Empty when the current state sends nothing. */
    fun toChanges(position: Int): List<SuwayomiFilterChange>

    companion object {
        fun of(filter: SuwayomiSourceFilter): SuwayomiFilterState = when (filter) {
            is SuwayomiSelectFilter -> SelectState(filter)
            is SuwayomiTextFilter -> TextState(filter)
            is SuwayomiCheckBoxFilter -> CheckBoxState(filter)
            is SuwayomiTriStateFilter -> TriStateState(filter)
            is SuwayomiSortFilter -> SortState(filter)
            is SuwayomiGroupFilter -> GroupState(filter)
            else -> UnsupportedState(filter)
        }

        fun of(filters: List<SuwayomiSourceFilter>): List<SuwayomiFilterState> = filters.map(::of)

        /**
         * Builds the `filters` payload for `fetchSourceManga`. Only filters whose value differs
         * from the server's own default are sent: an untouched filter left out keeps the source's
         * real default, which is not always the value the server reports for it. A group is entered
         * once per changed nested filter, indexing each list by position as the server expects.
         */
        fun changes(states: List<SuwayomiFilterState>): List<SuwayomiFilterChange> =
            states.flatMapIndexed { index, state -> if (state.diverged) state.toChanges(index) else emptyList() }
    }
}

data class SelectState(override val filter: SuwayomiSelectFilter, val index: Int = filter.default) :
    SuwayomiFilterState {
    override val diverged: Boolean get() = index != filter.default

    override fun toChanges(position: Int) =
        listOf(SuwayomiFilterChange(position = position, selectState = index))
}

data class TextState(override val filter: SuwayomiTextFilter, val text: String = filter.default) : SuwayomiFilterState {
    override val diverged: Boolean get() = text != filter.default

    override fun toChanges(position: Int) =
        listOf(SuwayomiFilterChange(position = position, textState = text))
}

data class CheckBoxState(override val filter: SuwayomiCheckBoxFilter, val checked: Boolean = filter.default) :
    SuwayomiFilterState {
    override val diverged: Boolean get() = checked != filter.default

    override fun toChanges(position: Int) =
        listOf(SuwayomiFilterChange(position = position, checkBoxState = checked))
}

data class TriStateState(override val filter: SuwayomiTriStateFilter, val state: String = filter.default) :
    SuwayomiFilterState {
    override val diverged: Boolean get() = state != filter.default

    override fun toChanges(position: Int) =
        listOf(SuwayomiFilterChange(position = position, triState = state))
}

data class SortState(
    override val filter: SuwayomiSortFilter,
    val index: Int = filter.default?.index ?: 0,
    val ascending: Boolean = filter.default?.ascending ?: true,
) : SuwayomiFilterState {
    override val diverged: Boolean
        get() = index != (filter.default?.index ?: 0) || ascending != (filter.default?.ascending ?: true)

    override fun toChanges(position: Int) = listOf(
        SuwayomiFilterChange(
            position = position,
            sortState = SuwayomiSortSelection(index = index, ascending = ascending),
        ),
    )
}

data class GroupState(override val filter: SuwayomiGroupFilter, val children: List<SuwayomiFilterState>) :
    SuwayomiFilterState {
    constructor(filter: SuwayomiGroupFilter) : this(filter, SuwayomiFilterState.of(filter.filters))

    override val diverged: Boolean get() = children.any { it.diverged }

    override fun toChanges(position: Int): List<SuwayomiFilterChange> {
        val changed = children.mapIndexedNotNull { index, child ->
            if (child.diverged) index to child else null
        }
        return changed.flatMap { (index, child) ->
            child.toChanges(index).map { SuwayomiFilterChange(position = position, groupChange = it) }
        }
    }
}

/** Header, separator and unknown filters carry no state and send nothing. */
data class UnsupportedState(override val filter: SuwayomiSourceFilter) : SuwayomiFilterState {
    override val diverged: Boolean get() = false

    override fun toChanges(position: Int): List<SuwayomiFilterChange> = emptyList()
}
