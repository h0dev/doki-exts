package org.koitharu.kotatsu.parsers.site.tachiyomi.vi.daomeoden

import org.koitharu.kotatsu.parsers.model.SortOrder

internal object DaoMeoDenFilters {

	val GENRES: Map<String, String> = mapOf(
		"0" to "All",
		"9" to "Action",
		"11" to "Adventure",
		"294" to "Comedy",
		"19" to "Drama",
		"22" to "Fantasy",
		"310" to "Horror",
		"295" to "Romance",
		"285" to "Mystery",
		"286" to "Psychological",
		"298" to "Martial Arts",
		"309" to "Sci-fi",
		"288" to "Slice of life",
		"306" to "Thriller",
		"303" to "Tragedy",
		"296" to "School Life",
		"293" to "Sports",
		"301" to "Shounen Ai",
		"300" to "Seinen",
		"299" to "Shoujo",
		"305" to "Shoujo Ai",
		"291" to "Mature",
		"292" to "Smut",
		"304" to "Supernatural",
		"308" to "Gender Bender",
		"290" to "Historical",
		"307" to "Harem",
		"297" to "Josei",
		"302" to "Yuri",
		"311" to "Crossdressing",
		"289" to "Doujinshi",
		"317" to "Cooking",
		"314" to "Gyaru",
		"315" to "Isekai",
		"316" to "Mecha",
		"312" to "Music",
		"318" to "Reincarnation",
		"313" to "Survival",
		"319" to "Video Game",
		"320" to "Villainess",
		"321" to "Zombie",
	)

	const val DEFAULT_STATUS = "0"
	const val DEFAULT_CATEGORY = "all"
	const val DEFAULT_GENRE = "0"
	const val DEFAULT_EXPLICIT = "0"
	const val DEFAULT_ORDER = "updated_at"

	fun sortOrderToValue(order: SortOrder): String {
		return when (order) {
			SortOrder.POPULARITY -> "viewsAll"
			SortOrder.NEWEST -> "created_at"
			else -> "updated_at"
		}
	}
}
