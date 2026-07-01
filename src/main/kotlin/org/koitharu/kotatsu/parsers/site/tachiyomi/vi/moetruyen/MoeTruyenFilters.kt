package org.koitharu.kotatsu.parsers.site.tachiyomi.vi.moetruyen

import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder

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
