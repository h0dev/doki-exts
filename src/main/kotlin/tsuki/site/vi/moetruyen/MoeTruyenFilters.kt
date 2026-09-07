package tsuki.site.vi.moetruyen

import tsuki.model.MangaState
import tsuki.model.SortOrder

internal object MoeTruyenFilters {

	fun sortOrderToValue(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "follow"
		SortOrder.UPDATED -> "update"
		SortOrder.NEWEST -> "new"
		SortOrder.ALPHABETICAL -> "az"
		else -> "update"
	}

	fun stateToValue(state: MangaState): String = when (state) {
		MangaState.ONGOING -> "1"
		MangaState.FINISHED -> "2"
		MangaState.PAUSED -> "3"
		else -> "0"
	}
}
