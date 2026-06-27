package org.koitharu.kotatsu.parsers.site.madara.vi

import org.json.JSONArray
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
	// Strategy 1: evaluateJs — load chapter page in WebView, let page
	//   JS (masr-reader.js) execute fully (Cloudflare + API calls), then
	//   extract image URLs from the rendered DOM or via synchronous XHR.
	//   This is the most reliable path and mirrors keiyoushi's approach.
	// Strategy 2: REST API directly — if nonce/session are found embedded
	//   in the page HTML, bypass WebView and call the API directly.
	// Strategy 3: HTML parsing — simple DOM scan for any img[] elements.
	// Strategy 4: super.getPages() — MadaraParser default fallback.
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)

		// ── Strategy 1: evaluateJs (WebView / full JS execution) ──────
		try {
			val jsResult = context.evaluateJs(fullUrl, JS_FETCH_IMAGES)
			if (!jsResult.isNullOrEmpty()) {
				val pages = JSONArray(jsResult).let { arr ->
					(0 until arr.length()).map { i ->
						MangaPage(
							id = generateUid(arr.getString(i)),
							url = arr.getString(i),
							preview = null,
							source = source,
						)
					}
				}
				if (pages.isNotEmpty()) return pages
			}
		} catch (_: Exception) {
			// evaluateJs requires Android WebView; may fail in test env or
			// if the host app doesn't support it. Fall through gracefully.
		}

		// ── Strategy 2: Direct REST API call via kotlin HTTP client ──
		// Nonce+session are NOT in HTML — they come from /challenge endpoint.
		// If Cloudflare allows direct HTTP (e.g., Vietnamese IP), this works
		// without WebView overhead.
		try {
			val challengeUrl = "https://$domain/wp-json/manga-reader/v1/challenge"
			val challengeJson = webClient.httpGet(challengeUrl).parseJson()
			val nonce = challengeJson.optString("nonce", null)
			val session = challengeJson.optString("session", null)
			if (nonce != null && session != null) {
				val apiUrl = "https://$domain/wp-json/manga-reader/v1/images"
				val headers = okhttp3.Headers.Builder().apply {
					add("Accept", "application/json")
					add("Referer", fullUrl)
					add("x-masr-nonce", nonce)
					add("x-masr-session", session)
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
		} catch (_: Exception) { /* Cloudflare blocks direct HTTP — fall through */ }

		// ── Strategy 3: HTML parsing ─────────────────────────────────
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

		// ── Strategy 4: MadaraParser default fallback ────────────────
		return super.getPages(chapter)
	}

	companion object {
		/**
		 * JavaScript evaluated inside Android WebView after the chapter
		 * page finishes loading. The WebView has already solved Cloudflare,
		 * so same-origin synchronous XHR to `/challenge` + `/images` works.
		 *
		 * Flow mirrors the site's own `masr-reader.js`:
		 * 1. Check if `#manga-secure-reader` already has `<img>` children
		 *    (page's async JS completed early — fast path, no extra requests).
		 * 2. Otherwise read global `MASR_READER` (injected server-side) for
		 *    `challengeUrl` / `imagesUrl`, then:
		 *    a. Sync XHR → `challengeUrl` → get `{nonce, session}`
		 *    b. Paginated sync XHR → `imagesUrl?offset=N&limit=50` with
		 *       `x-masr-nonce` / `x-masr-session` headers
		 *    c. Collect all image URLs from the CDN
		 * 3. Return JSON array of absolute image URLs.
		 *
		 * Returns JSON array, empty string (no images), or null on error.
		 */
		private val JS_FETCH_IMAGES = """
			(function() {
				try {
					function syncXhr(method, url, headers) {
						var xhr = new XMLHttpRequest();
						xhr.open(method, url, false);
						for (var k in headers) {
							xhr.setRequestHeader(k, headers[k]);
						}
						xhr.send();
						return xhr;
					}

					// ── Step 1: Check rendered DOM ──
					var reader = document.getElementById('manga-secure-reader');
					if (reader) {
						var imgs = reader.querySelectorAll('img');
						var urls = [];
						for (var i = 0; i < imgs.length; i++) {
							var src = imgs[i].getAttribute('src') || '';
							if (src && src.indexOf('data:image') === -1 && src.indexOf('blank') === -1) {
								urls.push(imgs[i].src);
							}
						}
						if (urls.length > 0) return JSON.stringify(urls);
					}

					// ── Step 2: Use MASR_READER global (injected server-side) ──
					if (typeof MASR_READER === 'undefined') return '';
					var challengeUrl = MASR_READER.challengeUrl;
					var imagesUrl   = MASR_READER.imagesUrl;
					if (!challengeUrl || !imagesUrl) return '';

					// Step 2a: Get nonce + session from challenge endpoint
					var challengeResp = syncXhr('GET', challengeUrl, { 'Accept': 'application/json' });
					if (challengeResp.status !== 200) return '';
					var challenge;
					try { challenge = JSON.parse(challengeResp.responseText); } catch(e) { return ''; }
					var nonce   = challenge.nonce   || '';
					var session = challenge.session || '';
					if (!nonce || !session) return '';

					// Step 2b: Fetch images with pagination (limit=50)
					var allUrls = [];
					var offset = 0;
					var limit = 50;
					var maxIter = 20; // safety cap
					while (offset >= 0 && maxIter-- > 0) {
						var resp = syncXhr('GET', imagesUrl + '?offset=' + offset + '&limit=' + limit, {
							'Accept': 'application/json',
							'X-MASR-Nonce': nonce,
							'X-MASR-Session': session
						});
						if (resp.status !== 200) break;
						var data;
						try { data = JSON.parse(resp.responseText); } catch(e) { break; }
						if (!data.images || data.images.length === 0) break;
						for (var j = 0; j < data.images.length; j++) {
							allUrls.push(data.images[j]);
						}
						if (data.next === null || data.next === undefined || data.next >= data.count) break;
						offset = data.next;
					}
					if (allUrls.length > 0) return JSON.stringify(allUrls);
					return '';
				} catch(e) {
					return null;
				}
			})();
		""".trimIndent()
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
