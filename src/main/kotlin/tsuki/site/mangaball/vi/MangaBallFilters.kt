package tsuki.site.mangaball.vi

import tsuki.model.MangaState
import tsuki.model.SortOrder

internal object MangaBallFilters {

	fun sortOrderToValue(order: SortOrder): String = when (order) {
		SortOrder.UPDATED -> "updated_at"
		SortOrder.NEWEST -> "created_at"
		SortOrder.RATING -> "rating"
		SortOrder.POPULARITY -> "follows"
		else -> "updated_at"
	}

	fun stateToValue(state: MangaState): String = when (state) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> ""
	}

	fun demographicValue(type: tsuki.model.ContentType?): String {
		return when (type) {
			null -> "all"
			else -> "all"
		}
	}
}
