package org.koitharu.kotatsu.parsers.site.madara.vi

import org.json.JSONArray
import org.json.JSONObject
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
	// The site uses Madara Anti-Scrape Reader (MASR) which loads images
	// dynamically via JS (REST API with nonce/session headers). Images
	// are NOT in the static HTML.
	//
	// Strategy 1: Parse MASR_READER config from HTML + POST to REST API.
	//   The MASR_READER JS variable is embedded server-side in a <script>
	//   tag and contains challengeUrl + imagesUrl. We extract it with
	//   Jsoup, then POST to /challenge (with mangaUrl+pageUrl) to get
	//   nonce+session, then POST to /images (with nonce+session+offset+
	//   limit) to retrieve image URLs with pagination.
	//   NOTE: evaluateJs() is NOT a WebView (it's a pure JS engine), so
	//   we must do the API calls natively via Kotlin HTTP client.
	// Strategy 2: HTML parsing — scan #manga-secure-reader for <img> tags.
	// Strategy 3: super.getPages() — MadaraParser default fallback.
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)

		// ── Strategy 1: Parse MASR_READER + REST API calls ────────────
		try {
			val doc = webClient.httpGet(fullUrl).parseHtml()

			// Extract MASR_READER config from <script> tag
			val masrScript = doc.selectFirst("script:containsData(MASR_READER)")
			if (masrScript != null) {
				val scriptData = masrScript.data()
				val jsonStr = MASR_READER_REGEX.find(scriptData)
					?.groupValues?.getOrNull(1)
					?.trim()
				if (jsonStr.isNullOrEmpty()) throw ParseException("MASR_READER not found", fullUrl)
				val masrReader = JSONObject(jsonStr)
				val challengeUrl = masrReader.optString("challengeUrl")
				val imagesUrl = masrReader.optString("imagesUrl")
				if (challengeUrl.isEmpty() || imagesUrl.isEmpty()) {
					throw ParseException("MASR_READER missing urls", fullUrl)
				}

				// Derive manga URL from chapter URL by removing last path segment
				// Chapter: /read/slug/chapter-1/ → Manga: /read/slug/
				val mangaUrl = fullUrl.substringBefore("?").trimEnd('/')
					.substringBeforeLast("/") + "/"

				// POST to challenge endpoint → get nonce + session
				val challengeResponse = webClient.httpPost(
					challengeUrl,
					mapOf(
						"mangaUrl" to mangaUrl,
						"pageUrl" to fullUrl,
					),
				).parseJson()
				val nonce = challengeResponse.getString("nonce")
				val session = challengeResponse.getString("session")

				// POST to images endpoint with pagination
				val allImages = mutableListOf<String>()
				var offset = 0
				val limit = 50
				while (true) {
					val json = webClient.httpPost(
						imagesUrl,
						mapOf(
							"mangaUrl" to mangaUrl,
							"pageUrl" to fullUrl,
							"nonce" to nonce,
							"session" to session,
							"offset" to offset.toString(),
							"limit" to limit.toString(),
						),
					).parseJson()
					val arr = json.getJSONArray("images")
					for (i in 0 until arr.length()) {
						allImages.add(arr.getString(i))
					}
					if (!json.has("next") || json.isNull("next") || arr.length() == 0) break
					val next = json.getInt("next")
					val count = json.optInt("count", allImages.size)
					if (next <= 0 || next >= count) break
					offset = next
				}

				if (allImages.isNotEmpty()) {
					return allImages.map { url ->
						MangaPage(
							id = generateUid(url),
							url = url,
							preview = null,
							source = source,
						)
					}
				}
			}
		} catch (_: Exception) {
			// MASR API call failed (Cloudflare, network, etc.) — fall through
		}

		// ── Strategy 2: HTML parsing ─────────────────────────────────
		try {
			val doc = webClient.httpGet(fullUrl).parseHtml()
			val containers = listOfNotNull(
				doc.body().selectFirst("#manga-secure-reader"),
				doc.body().selectFirst(".reading-content"),
			)
			for (container in containers) {
				val images = container.select("img").mapNotNull { img ->
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
		} catch (_: Exception) { /* fall through */ }

		// ── Strategy 3: MadaraParser default fallback ────────────────
		return super.getPages(chapter)
	}

	companion object {
		private val MASR_READER_REGEX =
			Regex("MASR_READER\\s*=\\s*(\\{.+?\\})", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
	}

	private suspend fun fetchTags(): Set<MangaTag> {
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
