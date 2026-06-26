package org.koitharu.kotatsu.parsers.site.matodex.vi

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("MATODEX", "MatoDex", "vi", type = ContentType.MANGA)
internal class MatoDex(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.MATODEX) {

	override val configKeyDomain = ConfigKey.Domain("mato.suicaodex.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet(),
		availableStates = emptySet(),
	)

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		if (query != null && query.isNotBlank()) {
			val q = query.trim().lowercase()
			val match = SEARCH_ALIASES.any { q.contains(it) }
			if (!match) return emptyList()
		}
		val json = webClient.httpGet("https://$domain/api/v1/mato/info.json").parseJson()
		return listOf(parseManga(json))
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val infoJson = webClient.httpGet("https://$domain/api/v1/mato/info.json").parseJson()
		val chaptersJson = webClient.httpGet("https://$domain/api/v1/mato/chapters.json").parseJson()
		val chapters = chaptersJson.getJSONArray("chapters").let { arr ->
			(0 until arr.length()).map { i ->
				val jo = arr.getJSONObject(i)
				val id = jo.getString("id")
				val title = jo.optString("title", "")
				val number = jo.getDouble("number").toFloat()
				val publishedAt = jo.optString("publishedAt", "")
				val timestamp = if (publishedAt.isNotEmpty()) {
					dateFormat.parseSafe(publishedAt)
				} else {
					0L
				}
				MangaChapter(
					id = generateUid(id),
					title = title,
					number = number,
					volume = 0,
					url = id,
					scanlator = null,
					uploadDate = timestamp,
					branch = null,
					source = source,
				)
			}
		}
		return parseManga(infoJson).copy(chapters = chapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet("https://$domain/api/v1/mato/chapters/${chapter.url}.json").parseJson()
		val pages = json.getJSONArray("pages")
		return (0 until pages.length()).map { i ->
			val imageUrl = pages.getString(i)
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getFavicons(): Favicons {
		return Favicons.single("https://$domain/favicon.ico")
	}

	private fun parseManga(json: JSONObject): Manga {
		val title = json.getString("title")
		val altTitle = json.optString("altTitles", "")
		val coverUrl = json.optString("cover", null)
		val status = json.optString("status", "")
		val description = json.optString("description", null)?.takeIf { it.isNotEmpty() }

		return Manga(
			id = generateUid(title),
			title = title,
			altTitles = if (altTitle.isNotEmpty()) setOf(altTitle) else emptySet(),
			url = "/",
			publicUrl = "https://$domain",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = parseStatus(status),
			authors = setOf("Takahiro", "Takemura Youhei"),
			description = description,
			source = source,
		)
	}

	private fun parseStatus(status: String): MangaState? {
		return when (status.lowercase()) {
			"ongoing" -> MangaState.ONGOING
			"finished" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"cancelled" -> MangaState.ABANDONED
			else -> null
		}
	}

	companion object {
		private val SEARCH_ALIASES = setOf(
			"mato seihei no slave",
			"mato seihei",
			"mato",
			"slave",
			"chained soldier",
			"nô lệ",
			"ma to",
			"魔都精兵のスレイブ",
		)
	}
}
