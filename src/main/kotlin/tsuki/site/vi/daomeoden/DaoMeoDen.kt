package tsuki.site.vi.daomeoden

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DAOMEODEN", "DaoMeoDen", "vi")
internal class DaoMeoDen(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DAOMEODEN, 24) {

	override val configKeyDomain = ConfigKey.Domain("daomeoden.net")
	private val baseUrl get() = "https://$domain"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "$baseUrl/")
		.build()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = DaoMeoDenFilters.GENRES.map { (key, title) ->
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	// ============================== List ===============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val q = filter.query
		val url = buildString {
			append("$baseUrl/danh-sach-truyen-tranh.html")
			append("?page=$page")
			if (!q.isNullOrBlank()) {
				append("&textSearch=")
				append(q.urlEncoded())
			}
			append("&order=")
			append(DaoMeoDenFilters.sortOrderToValue(order))
			if (filter.states.isNotEmpty()) {
				filter.states.oneOrThrowIfMany()?.let { state ->
					append("&status=")
					append(
						when (state) {
							MangaState.ONGOING -> "2"
							MangaState.FINISHED -> "1"
							else -> "0"
						},
					)
				}
			}
			if (filter.types.isNotEmpty()) {
				filter.types.oneOrThrowIfMany()?.let { type ->
					append("&category=")
					append(
						when (type) {
							ContentType.MANGA -> "manga"
							ContentType.MANHWA -> "manhwa"
							ContentType.MANHUA -> "manhua"
							else -> "all"
						},
					)
				}
			}
			if (filter.tags.isNotEmpty()) {
				append("&genre=")
				append(filter.tags.joinToString(",") { it.key })
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		val apiForm = buildBookListApiForm(doc, url)
			?: return emptyList()

		val apiHeaders = Headers.Builder()
			.add("Origin", baseUrl)
			.add("Referer", url)
			.add("X-Requested-With", "XMLHttpRequest")
			.build()
		val json = webClient.httpPost(
			url = "$baseUrl/apps/controllers/book/bookList.php".toHttpUrl(),
			form = apiForm,
			extraHeaders = apiHeaders,
		).parseJson()

		val status = json.optInt("status")
		val htmlBook = json.optString("htmlBook", null)
		if (status != 200 || htmlBook == null) return emptyList()

		val listDocument = Jsoup.parseBodyFragment(htmlBook, baseUrl)
		return listDocument.select("div.item-list").map { mangaFromElement(it) }
	}

	private fun buildBookListApiForm(document: Document, referer: String): Map<String, String>? {
		val token = extractScriptVariable(document, "_token") ?: return null
		val pageCurrent = extractScriptVariable(document, "pageCurrent") ?: return null
		val pageLast = extractScriptVariable(document, "pageLast") ?: return null
		val status = extractScriptVariable(document, "status") ?: DaoMeoDenFilters.DEFAULT_STATUS
		val ages = extractScriptVariable(document, "ages").orEmpty()
		val category = extractScriptVariable(document, "category") ?: DaoMeoDenFilters.DEFAULT_CATEGORY
		val genre = extractScriptVariable(document, "genre").orEmpty()
		val explicit = extractScriptVariable(document, "explicit").orEmpty()
		val magazine = extractScriptVariable(document, "magazine").orEmpty()
		val tags = extractScriptVariable(document, "tags").orEmpty()
		val order = extractScriptVariable(document, "order") ?: DaoMeoDenFilters.DEFAULT_ORDER
		val pagiParam = extractScriptVariable(document, "pagiParam") ?: return null
		val textSearch = extractScriptVariable(document, "textSearch").orEmpty()

		return mapOf(
			"token" to token,
			"pageCurrent" to pageCurrent,
			"pageLast" to pageLast,
			"status" to status,
			"ages" to ages,
			"category" to category,
			"genre" to genre,
			"explicit" to explicit,
			"magazine" to magazine,
			"tags" to tags,
			"order" to order,
			"pagiParam" to pagiParam,
			"textSearch" to textSearch,
		)
	}

	// ============================== Details ===============================

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("div.info-name")?.text() ?: manga.title
		val thumbnailUrl = doc.selectFirst("div.info-cover-img img")
			?.absUrl("src")?.normalizeImageUrl()
		val genre = doc.select(
			"div.info-tag.tag-category span, div.info-tag.tag-genre span, div.info-tag.tag-tag span",
		).joinToString { it.text() }.ifEmpty { null }
		val statusText = doc.selectFirst("div.info-tag.tag-status span")?.text()
		val description = doc.selectFirst("div.info-description div.content")?.text()

		return manga.copy(
			title = title,
			altTitles = setOfNotNull(genre),
			coverUrl = thumbnailUrl,
			state = parseStatus(statusText),
			description = description,
			chapters = parseChapters(doc),
		)
	}

	private fun parseStatus(statusText: String?): MangaState? = when {
		statusText == null -> null
		statusText.contains("ongoing", true) -> MangaState.ONGOING
		statusText.contains("hoàn", true) -> MangaState.FINISHED
		statusText.contains("completed", true) -> MangaState.FINISHED
		statusText.contains("full", true) -> MangaState.FINISHED
		else -> null
	}

	// ============================== Chapters ==============================

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val chapterDateFormat = SimpleDateFormat("dd.MM.yyyy - HH:mm", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
		}
		val chapterUrlRegex = Regex("""openUrl\('([^']+)'\)""")
		val chapterNumberRegex = Regex("""\d+(?:\.\d+)?""")

		return doc.select("div#TabChapterChapter div.chapter").mapNotNull { element ->
			val chapterUrl = chapterUrlRegex.find(element.attr("onclick"))
				?.groupValues?.get(1) ?: return@mapNotNull null
			val name = element.selectFirst("div.chapter-info div.name-sub")
				?.text() ?: element.selectFirst("div.chapter-info div.name")?.text()
				?: return@mapNotNull null
			val dateText = element.selectFirst("div.chapter-info div.time > div")?.text()
			val number = chapterNumberRegex.find(name)?.value?.toFloatOrNull() ?: 0f

			MangaChapter(
				id = generateUid(chapterUrl),
				title = name,
				number = number,
				volume = 0,
				url = chapterUrl.toRelativeUrl(domain),
				scanlator = null,
				uploadDate = chapterDateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}
	}

	// ============================== Pages =================================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val chapterId = extractScriptVariable(doc, "chapterId") ?: return emptyList()
		val token = extractScriptVariable(doc, "_token") ?: return emptyList()

		val apiHeaders = Headers.Builder()
			.add("Origin", baseUrl)
			.add("Referer", chapterUrl)
			.add("X-Requested-With", "XMLHttpRequest")
			.build()
		val json = webClient.httpPost(
			url = "$baseUrl/apps/controllers/book/bookChapterContent.php".toHttpUrl(),
			form = mapOf(
				"token" to token,
				"chapterId" to chapterId,
				"cookies" to "W10=",
			),
			extraHeaders = apiHeaders,
		).parseJson()

		val status = json.optInt("status")
		val data = json.optString("data", null)
		if (status != 200 || data == null) return emptyList()

		val chapterDocument = Jsoup.parseBodyFragment(data, baseUrl)
		return chapterDocument.select("img").mapIndexedNotNull { index, element ->
			val imageUrl = element.absUrl("data-src").ifEmpty { element.absUrl("src") }
				.normalizeImageUrl()
			if (imageUrl.isEmpty()) return@mapIndexedNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}.distinctBy { it.url }
	}

	// ============================== Helpers ===============================

	private fun mangaFromElement(element: Element): Manga {
		val titleElement = element.selectFirst("div.item-title a")!!
		val href = titleElement.attrAsRelativeUrl("href")
		val title = titleElement.text()
		val coverUrl = element.selectFirst("div.item-cover img")
			?.absUrl("src")?.normalizeImageUrl().orEmpty()

		return Manga(
			id = generateUid(href),
			title = title,
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun extractScriptVariable(document: Document, key: String): String? {
		return Regex("""var\s+$key\s*=\s*'([^']*)'""").find(document.html())?.groupValues?.get(1)
	}

	private fun String.normalizeImageUrl(): String {
		return if (startsWith("//")) "https:$this" else this
	}
}
