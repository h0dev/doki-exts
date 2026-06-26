package org.koitharu.kotatsu.parsers.site.madara.vi

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat

@MangaSourceParser("HENTAICUBE", "CBHentai", "vi", ContentType.HENTAI)
internal class HentaiCube(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HENTAICUBE, "2tencb.pro") {

	override val datePattern = "dd/MM/yyyy"
	override val authorSearchSupported = true

	override val mangaSubString = "read"
	override val filterNonMangaItems = false

	// Do NOT use admin-ajax for chapters (postReq=false is the default).
	// The site serves chapters via XHR /ajax/chapters with pagination.
	override val postReq = false

	private val thumbnailOriginalUrlRegex = Regex("-\\d+x\\d+(\\.[a-zA-Z]+)$")

	override fun processThumbnail(url: String?, fromSearch: Boolean): String? {
		return url?.replace(thumbnailOriginalUrlRegex, "$1")
	}

	override val altNameSelector = ".post-content_item:contains(Tên khác) .summary-content"

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val q = filter.query
		val pages = page + 1

		val url = buildString {
			val author = filter.author
			if (!author.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)
				append("/tacgia/")
				append(author.lowercase().replace(" ", "-"))

				if (pages > 1) {
					append("/page/")
					append(pages.toString())
				}

				append("/?m_orderby=")
				when (order) {
					SortOrder.POPULARITY -> append("views")
					SortOrder.UPDATED -> append("latest")
					SortOrder.NEWEST -> append("new-manga")
					SortOrder.ALPHABETICAL -> {}
					SortOrder.RATING -> append("trending")
					SortOrder.RELEVANCE -> {}
					else -> append("latest") // default
				}
				return@buildString
			}

			append("https://")
			append(domain)

			if (pages > 1) {
				append("/page/")
				append(pages.toString())
			}

			append("/?s=")

			filter.query?.let {
				append(it.urlEncoded())
			}

			append("&post_type=wp-manga")

			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach {
					append("&genre[]=")
					append(it.key)
				}
			}

			filter.states.forEach {
				append("&status[]=")
				when (it) {
					MangaState.ONGOING -> append("on-going")
					MangaState.FINISHED -> append("end")
					MangaState.ABANDONED -> append("canceled")
					MangaState.PAUSED -> append("on-hold")
					MangaState.UPCOMING -> append("upcoming")
					else -> throw IllegalArgumentException("$it not supported")
				}
			}

			filter.contentRating.oneOrThrowIfMany()?.let {
				append("&adult=")
				append(
					when (it) {
						ContentRating.SAFE -> "0"
						ContentRating.ADULT -> "1"
						else -> ""
					},
				)
			}

			if (filter.year != 0) {
				append("&release=")
				append(filter.year.toString())
			}

			append("&m_orderby=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("latest")
				SortOrder.NEWEST -> append("new-manga")
				SortOrder.ALPHABETICAL -> append("alphabet")
				SortOrder.RATING -> append("rating")
				SortOrder.RELEVANCE -> {}
				else -> {}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun createMangaTag(a: Element): MangaTag? {
		val allTags = availableTags.getOrNull().orEmpty()
		val title = a.text().replace(Regex("\\(\\d+\\)"), "").trim() // force trim to remove space
		// compare to avoid duplicate tags with the same title
		return allTags.find {
			it.title.trim().equals(title, ignoreCase = true) // try to search with trim
		}
	}

	// ── Chapter Loading with XHR Pagination ──────────────────────────
	// The site loads chapters via POST to /ajax/chapters/ and may paginate
	// with ?t=PAGE. We collect all pages, combine elements, then parse once
	// to ensure correct ordering (oldest-first with sequential numbering).
	//
	// IMPORTANT: mangaUrl coming from getDetails is a relative URL (e.g.
	// /read/slug/). We must convert to absolute before passing to
	// webClient.httpPost, since its default implementation calls
	// HttpUrl.get(urlString) which requires a scheme.
	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val chaptersWrapper = document.select("div[id^=manga-chapters-holder]")

		// If chapters are embedded directly in the HTML, use them
		val htmlChapters = document.select(selectChapter)
		if (htmlChapters.isNotEmpty()) {
			return parseChapterElements(htmlChapters)
		}

		// Otherwise fetch via XHR endpoint with pagination
		if (chaptersWrapper.isNotEmpty()) {
			val absoluteMangaUrl = mangaUrl.toAbsoluteUrl(domain)
			val baseUrl = "${absoluteMangaUrl.removeSuffix("/")}/ajax/chapters/"
			val allElements = Elements()
			var page = 1

			while (true) {
				val xhrUrl = if (page <= 1) {
					baseUrl
				} else {
					"$baseUrl?t=$page"
				}
				val xhrDoc = webClient.httpPost(xhrUrl, emptyMap()).parseHtml()
				val pageChapters = xhrDoc.select(selectChapter)
				if (pageChapters.isEmpty()) break

				allElements.addAll(pageChapters)

				// Check if there's a next page
				val hasNext = xhrDoc.selectFirst("div.pagination a[data-page='${page + 1}']") != null
				if (!hasNext) break
				page++
			}

			if (allElements.isNotEmpty()) {
				return parseChapterElements(allElements)
			}
		}

		// Final fallback: try the parent's XHR approach
		// Note: super.loadChapters also passes relative mangaUrl, so it
		// may fail with the same scheme error. If we reach here and
		// have no chapters, return empty list.
		return emptyList()
	}

	// Parse chapter Elements into MangaChapter list.
	// XHR returns chapters newest-first; reversed=true reverses to oldest-first.
	private fun parseChapterElements(elements: Elements): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return elements.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val link = href + stylePage
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
				?: li.selectFirst(selectDate)?.text()
			val name = a.selectFirst("p")?.text() ?: a.ownText()
			MangaChapter(
				id = generateUid(href),
				title = name,
				number = i + 1f,
				volume = 0,
				url = link,
				uploadDate = parseChapterDate(dateFormat, dateText),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	// ── Page (Image) Loading ─────────────────────────────────────────
	// The site uses #manga-secure-reader with lazy-loaded images (data-src).
	// Fall back to standard selectors if the custom reader is not found.
		override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		// 1) Try REST API: /wp-json/manga-reader/v1/images
		// Extract masr nonce/session from script tags on the page
		val scriptTags = doc.select("script")
		val nonce = scriptTags.mapNotNull { s ->
			Regex("""["']?(?:masr|manga-reader)["']?\s*[:=]\s*["']([^"']+)["']""").find(s.data())?.groupValues?.get(1)
		}.firstOrNull()
		val session = scriptTags.mapNotNull { s ->
			Regex("""session\s*[:=]\s*["']([^"']+)["']""").find(s.data())?.groupValues?.get(1)
		}.firstOrNull()

		if (nonce != null || session != null) {
			val apiUrl = "https://$domain/wp-json/manga-reader/v1/images"
			val headers = okhttp3.Headers.Builder().apply {
				add("Accept", "application/json")
				add("Referer", fullUrl)
				nonce?.let { add("x-masr-nonce", it) }
				session?.let { add("x-masr-session", it) }
			}.build()

			val images = mutableListOf<String>()
			var offset = 0
			val limit = 50
			while (true) {
				val json = webClient.httpGet("$apiUrl?offset=$offset&limit=$limit", headers)
					.parseJson()
				val arr = json.getJSONArray("images")
				for (i in 0 until arr.length()) {
					images.add(arr.getString(i))
				}
				val count = json.optInt("count", 0)
				val next = json.optInt("next", 0)
				if (next >= count || arr.length() == 0) break
				offset = next
			}

			if (images.isNotEmpty()) {
				return images.map { url ->
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		}

		// 2) Fallback: HTML parsing with #manga-secure-reader and .reading-content
		val containers = listOfNotNull(
			doc.body().selectFirst("#manga-secure-reader"),
			doc.body().selectFirst(".reading-content"),
		)
		for (container in containers) {
			val images = container.select("img").mapNotNull { img ->
				// Prefer data-src (full-res lazy-load) then src
				val url = img.attr("data-src").takeIf { it.isNotEmpty() }
					?: img.attr("src").takeIf { it.isNotEmpty() && !it.contains("blank") }
					?: img.src()
				if (url != null) {
					val resolvedUrl = url.toAbsoluteUrl(domain)
					MangaPage(
						id = generateUid(resolvedUrl),
						url = resolvedUrl,
						preview = null,
						source = source,
					)
				} else null
			}
			if (images.isNotEmpty()) {
				return images.distinctBy { it.url }
			}
		}
		// Last resort
		return super.getPages(chapter)
	}private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/the-loai-genres").parseHtml()
		val elements = doc.select("ul.list-unstyled li a")
		return elements.mapToSet { element ->
			val href = element.attr("href")
			val key = href.substringAfter("/theloai/").removeSuffix("/")
			val title = element.text().replace(Regex("\\(\\d+\\)"), "").trim() // force trim
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}.toSet()
	}
}
