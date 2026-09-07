package tsuki.site.mangaball.vi

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGABALL", "Manga Ball", "vi")
internal class MangaBall(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGABALL, 20) {

	override val configKeyDomain = ConfigKey.Domain("mangaball.net")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
	)

	// ============================== CSRF ==============================

	private var csrfToken: String? = null

	private suspend fun fetchCsrf(): String {
		if (csrfToken == null) {
			val doc = webClient.httpGet("https://$domain/").parseHtml()
			csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.nullIfEmpty()
		}
		return csrfToken ?: throw Exception("CSRF token not found")
	}

	private suspend fun apiPost(url: String, params: List<Pair<String, String>>): JSONObject {
		val token = try { fetchCsrf() } catch (_: Exception) { null }
		val payload = params.joinToString("&") { (k, v) ->
			"${k.urlEncoded()}=${v.urlEncoded()}"
		}
		val headers = Headers.Builder()
			.add("Content-Type", "application/x-www-form-urlencoded")
			.add("X-Requested-With", "XMLHttpRequest")
			.apply { token?.let { add("X-CSRF-TOKEN", it) } }
			.build()
		return try {
			webClient.httpPost(url.toHttpUrl(), payload = payload, extraHeaders = headers).parseJson()
		} catch (e: Exception) {
			csrfToken = null
			val newToken = fetchCsrf()
			val retryHeaders = Headers.Builder()
				.add("Content-Type", "application/x-www-form-urlencoded")
				.add("X-Requested-With", "XMLHttpRequest")
				.add("X-CSRF-TOKEN", newToken)
				.build()
			webClient.httpPost(url.toHttpUrl(), payload = payload, extraHeaders = retryHeaders).parseJson()
		}
	}

	private suspend fun updateCsrfFromDocument(doc: Document) {
		doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.nullIfEmpty()?.also {
			csrfToken = it
		}
	}

	// ============================== List / Search ==============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query

		// Smart search for simple queries (no complex filters)
		if (!query.isNullOrBlank() && filter.tags.isEmpty() && filter.states.isEmpty()) {
			return smartSearch(query)
		}

		val params = mutableListOf<Pair<String, String>>()
		params.add("search_input" to (query ?: ""))
		params.add("filters[sort]" to MangaBallFilters.sortOrderToValue(order))
		params.add("filters[page]" to page.toString())
		params.add("filters[contentRating]" to "any")
		params.add("filters[demographic]" to "all")
		params.add("filters[tag_included_mode]" to "OR")
		params.add("filters[tag_excluded_mode]" to "OR")
		params.add("filters[translatedLanguage][]" to "vi")

		if (filter.states.isNotEmpty()) {
			params.add(
				"filters[publicationStatus]" to MangaBallFilters.stateToValue(
					filter.states.oneOrThrowIfMany() ?: MangaState.ONGOING,
				),
			)
		} else {
			params.add("filters[publicationStatus]" to "")
		}

		filter.tags.forEach { tag ->
			params.add("filters[tag_included_ids][]" to tag.key)
		}

		val json = apiPost("https://$domain/api/v1/title/search-advanced/", params)
		val data = json.getJSONArray("data")
		val mangas = List(data.length()) { i ->
			val item = data.getJSONObject(i)
			val url = item.getString("url")
			val slug = url.toHttpUrl().pathSegments[1]
			Manga(
				id = generateUid(slug),
				title = item.getString("name"),
				altTitles = emptySet(),
				url = slug,
				publicUrl = "https://$domain/title-detail/$slug/",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = item.optString("cover", null)?.nullIfEmpty(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		return mangas
	}

	private suspend fun smartSearch(query: String): List<Manga> {
		val params = listOf("search_input" to query)
		val json = try {
			apiPost("https://$domain/api/v1/smart-search/search/", params)
		} catch (_: Exception) {
			return emptyList()
		}
		val data = json.optJSONObject("data") ?: return emptyList()
		val mangasArr = data.optJSONArray("manga") ?: return emptyList()
		return List(mangasArr.length()) { i ->
			val item = mangasArr.getJSONObject(i)
			val url = item.getString("url")
			val slug = url.toHttpUrl().pathSegments[1]
			Manga(
				id = generateUid(slug),
				title = item.getString("title"),
				altTitles = emptySet(),
				url = slug,
				publicUrl = "https://$domain/title-detail/$slug/",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = item.optString("img", null)?.nullIfEmpty(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	// ============================== Details ==============================

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet("https://$domain/title-detail/${manga.url}/").parseHtml()
		updateCsrfFromDocument(doc)

		val title = doc.selectFirst("#comicDetail h6")?.ownText() ?: manga.title
		val cover = doc.selectFirst("img.featured-cover")?.absUrl("src")?.nullIfEmpty()
		val author = doc.select("#comicDetail span[data-person-id]").eachText().joinToString().nullIfEmpty()
		val statusText = doc.selectFirst("span.badge-status")?.text()
		val state = parseStatus(statusText)

		val tags = buildSet {
			doc.select("#comicDetail span[data-tag-id]").forEach { span ->
				val name = span.ownText()
				if (name.isNotBlank()) {
					add(
						MangaTag(
							key = name.lowercase(Locale.ROOT).replace("\\s+".toRegex(), "-"),
							title = name.toTitleCase(sourceLocale),
							source = source,
						),
					)
				}
			}
		}

		val description = buildString {
			doc.selectFirst("#descriptionContent p")?.also { append(it.wholeText()) }
			doc.selectFirst("#comicDetail span.badge:contains(Published)")?.also {
				append("\n\n")
				append(it.text())
			}
			val altTitlesEl = doc.select("div.alternate-name-container").text()
			val altTitles = altTitlesEl.split("/").map { it.trim() }.filter { it.isNotBlank() }
			if (altTitles.isNotEmpty()) {
				append("\n\nAlternative Names: \n")
				altTitles.forEach { append("- ", it, "\n") }
			}
		}.trim().nullIfEmpty()

		// Fetch chapters
		val chapters = fetchChapters(manga.url)

		return manga.copy(
			title = title,
			altTitles = emptySet(), // included in description
			coverUrl = cover,
			tags = tags,
			authors = if (author != null) setOf(author) else emptySet(),
			description = description,
			state = state,
			chapters = chapters,
		)
	}

	private fun parseStatus(statusText: String?): MangaState? = when (statusText) {
		"Ongoing" -> MangaState.ONGOING
		"Completed" -> MangaState.FINISHED
		"Hiatus" -> MangaState.PAUSED
		"Cancelled" -> MangaState.ABANDONED
		else -> null
	}

	// ============================== Chapters ==============================

	private suspend fun fetchChapters(slug: String): List<MangaChapter> {
		val titleId = slug.substringAfterLast("-")
		val params = listOf("title_id" to titleId)
		val json = apiPost("https://$domain/api/v1/chapter/chapter-listing-by-title-id/", params)
		val chaptersArr = json.getJSONArray("chapters")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
		val result = mutableListOf<MangaChapter>()

		for (i in 0 until chaptersArr.length()) {
			val chapter = chaptersArr.getJSONObject(i)
			val number = chapter.getDouble("number").toFloat()
			val translations = chapter.optJSONArray("translations") ?: continue

			for (j in 0 until translations.length()) {
				val translation = translations.getJSONObject(j)
				val lang = translation.optString("language", "")
				if (lang != "vi") continue

				val translationId = translation.getString("id")
				val name = translation.optString("name", "")
				val volume = translation.optDouble("volume", 0.0).toFloat()
				val dateStr = translation.optString("date", "")
				val group = translation.optJSONObject("group")
				val scanlator = group?.optString("name", null)

				val chapterTitle = buildString {
					val volStr = volume.toString().removeSuffix(".0")
					if (volume > 0f) { append("Vol. ", volStr, " ") }
					val numStr = number.toString().removeSuffix(".0")
					if (name.contains(number.toString().removeSuffix(".0"), ignoreCase = true) ||
						name.contains(number.toString(), ignoreCase = true)
					) {
						append(name.trim())
					} else {
						append("Ch. ", numStr, " ", name.trim())
					}
				}

				val uploadDate = dateFormat.parseSafe(dateStr)

				result.add(
					MangaChapter(
						id = generateUid(translationId),
						title = chapterTitle,
						number = number,
						volume = volume.toInt(),
						url = translationId,
						scanlator = scanlator,
						uploadDate = uploadDate,
						branch = null,
						source = source,
					),
				)
			}
		}
		return result
	}

	// ============================== Pages =================================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet("https://$domain/chapter-detail/${chapter.url}/").parseHtml()
		updateCsrfFromDocument(doc)

		val script = doc.select("script:containsData(chapterImages)").joinToString(";") { it.data() }
		val imagesJson = CHAPTER_IMAGES_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
		val pagesArr = JSONArray(imagesJson)

		return List(pagesArr.length()) { i ->
			val imageUrl = pagesArr.getString(i)
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

	companion object {
		private val CHAPTER_IMAGES_REGEX = Regex("""const\s+chapterImages\s*=\s*JSON\.parse\(`([^`]+)`\)""")
	}
}
