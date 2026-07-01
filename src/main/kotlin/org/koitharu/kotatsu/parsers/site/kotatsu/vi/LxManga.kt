package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("LXMANGA", "LxManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.LXMANGA, 24) {

	override val configKeyDomain = ConfigKey.Domain("lxmanga.space")

	override fun getRequestHeaders() = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.build()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	// ======================== List ========================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val q = filter.query
		val sortParam = order.toSortParam()

		val url = buildString {
			append("https://$domain/tim-kiem")
			append("?sort=$sortParam")
			append("&page=$page")

			if (!q.isNullOrEmpty()) {
				append("&filter[name]=${q.urlEncoded()}")
			}

			if (filter.states.isNotEmpty()) {
				val statuses = filter.states.mapNotNull { state ->
					when (state) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "paused"
						else -> null
					}
				}.joinToString(",")
				if (statuses.isNotEmpty()) {
					append("&filter[status]=$statuses")
				}
			}

			if (filter.tags.isNotEmpty()) {
				append("&filter[accept_genres]=")
				append(filter.tags.joinToString(",") { it.key })
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&filter[reject_genres]=")
				append(filter.tagsExclude.joinToString(",") { it.key })
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.manga-vertical").mapNotNull { element ->
			val titleElement = element.selectFirst("a.text-ellipsis[href^=/truyen/]")
				?: return@mapNotNull null
			val coverElement = element.selectFirst("div.cover")
			val href = titleElement.absUrl("href").toRelativeUrl(domain)
			val coverUrl = coverElement?.let { getThumbnailUrl(it) }

			Manga(
				id = generateUid(href),
				title = titleElement.text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl.orEmpty(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	private fun getThumbnailUrl(element: Element): String? {
		val bg = element.absUrl("data-bg")
		if (bg.isNotEmpty()) return bg
		return parseBackgroundUrl(element.attr("style"))
	}

	private fun parseBackgroundUrl(styleValue: String?): String? {
		if (styleValue.isNullOrBlank()) return null
		return BACKGROUND_URL_REGEX.find(styleValue)?.groupValues?.get(1)
	}

	private fun SortOrder.toSortParam(): String = when (this) {
		SortOrder.POPULARITY -> "-views"
		SortOrder.UPDATED -> "-updated_at"
		SortOrder.NEWEST -> "-created_at"
		SortOrder.ALPHABETICAL -> "name"
		SortOrder.ALPHABETICAL_DESC -> "-name"
		else -> "-updated_at"
	}

	// ======================== Details ========================

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val title = doc.selectFirst("div.flex.flex-row.truncate.mb-4 span.grow.text-lg.ml-1.text-ellipsis.font-semibold")
			?.text()
			?: manga.title

		val author = doc.infoRow("Tác giả:")
			?.select("a[href*=/tac-gia/]")
			?.joinToString { it.text() }
			?.ifEmpty { null }

		val altNames = doc.infoRow("Tên khác:")
			?.select("a, span:not(.font-semibold)")
			?.joinToString { it.text().trim() }
			?.takeIf { it.isNotBlank() }

		val tags = doc.infoRow("Thể loại:")
			?.select("a[href*=/the-loai/]")
			?.mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix("/").substringAfterLast("/"),
					title = a.text(),
					source = source,
				)
			} ?: emptySet()

		val state = doc.infoRow("Tình trạng:")
			?.select("span.font-semibold")
			?.text()
			?.let { parseStatus(it) }

		val description = doc.selectFirst("div#nav-content-tab-1 p, div.description")
			?.text()
			?.takeIf { it.isNotBlank() }

		// Chapter parsing
		val chapterElements = doc.select("ul.overflow-y-auto a[href^=/truyen/]:has(span.timeago)")
			.ifEmpty { doc.select("a[href^=/truyen/]:has(span.timeago)") }
			.ifEmpty {
				doc.select("ul.overflow-y-auto a[href^=/truyen/]")
			}
			.ifEmpty {
				doc.select("a[href^=/truyen/]:has(span.text-ellipsis)")
					.filter { it.attr("href").count { c -> c == '/' } > 2 }
			}

		val chapters = chapterElements.mapNotNull { element ->
			val href = element.absUrl("href").toRelativeUrl(domain)
			val name = element.selectFirst("span.text-ellipsis")?.text() ?: "Chapter"
			val dateStr = element.selectFirst("span.timeago")?.text().orEmpty()
			MangaChapter(
				id = generateUid(href),
				title = name,
				number = name.substringAfter(" ").toFloatOrNull() ?: -1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(dateStr),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			altTitles = setOfNotNull(altNames),
			state = state,
			tags = tags,
			authors = setOfNotNull(author),
			description = description,
			chapters = chapters,
		)
	}

	private fun Document.infoRow(label: String): Element? {
		return select("div").firstOrNull { row ->
			row.selectFirst("span.font-semibold")?.text() == label
		}
	}

	private fun parseStatus(rawStatus: String?): MangaState? {
		if (rawStatus.isNullOrBlank()) return null
		return when {
			rawStatus.contains("đang tiến hành", ignoreCase = true) -> MangaState.ONGOING
			rawStatus.contains("hoàn thành", ignoreCase = true) -> MangaState.FINISHED
			rawStatus.contains("paused", ignoreCase = true) -> MangaState.PAUSED
			else -> null
		}
	}

	private fun parseChapterDate(dateStr: String): Long {
		if (dateStr.isBlank()) return 0L
		return try {
			CHAPTER_DATE_FORMAT.parse(dateStr)?.time ?: 0L
		} catch (_: Exception) {
			0L
		}
	}

	private val CHAPTER_DATE_FORMAT by lazy {
		SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
	}

	// ======================== Pages ========================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)

		// Method 1: Try WebView-based image extraction (handles anti-scraping WASM)
		val webImageUrls = tryExtractImagesViaWebView(fullUrl)
		if (webImageUrls.isNotEmpty()) {
			System.err.println("[LxManga] getPages: WebView returned ${webImageUrls.size} images")
			return webImageUrls.map { imageUrl ->
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
		}

		// Method 2: Fallback to static HTML parsing (old XOR method)
		System.err.println("[LxManga] getPages: WebView returned empty, trying static HTML")
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val html = doc.outerHtml()

		val actionToken = ACTION_TOKEN_REGEX.find(html)?.groupValues?.get(1)
		val encryptedPayload = ENCRYPTED_IMAGES_REGEX.find(html)?.groupValues?.get(1)

		if (actionToken != null && encryptedPayload != null) {
			val encryptedRows = ENCRYPTED_IMAGE_ROW_REGEX.findAll(encryptedPayload)
				.map { match ->
					match.groupValues[1]
						.split(",")
						.mapNotNull { it.toIntOrNull() }
						.takeIf { it.isNotEmpty() }
				}
				.toList()

			val imageUrls = doc.select("#image-container[data-idx]")
				.mapNotNull { it.attr("data-idx").toIntOrNull() }
				.distinct()
				.sorted()
				.mapNotNull { idx ->
					encryptedRows.getOrNull(idx)
						?.let { codes -> decodeImageUrl(codes, actionToken) }
						?.takeIf { it.isNotBlank() }
				}

			if (imageUrls.isNotEmpty()) {
				lastActionToken = actionToken
				return imageUrls.map { imageUrl ->
					MangaPage(
						id = generateUid(imageUrl),
						url = encodePageMetadata(fullUrl, actionToken, imageUrl),
						preview = null,
						source = source,
					)
				}
			}
		}

		throw Exception("Không thể tải ảnh. Vui lòng thử lại.")
	}

	/**
	 * Use the app's WebView to load the chapter page and wait for
	 * the anti-scraping WASM/JS to decrypt and set image sources.
	 * Returns list of image URLs, or empty list on failure.
	 */
	private suspend fun tryExtractImagesViaWebView(chapterUrl: String): List<String> {
		return try {
			val result = context.evaluateJs(chapterUrl, WEBVIEW_EXTRACT_SCRIPT)
				?: return emptyList()

			val imageUrls = mutableListOf<String>()
			val jsonArray = JSONArray(result)
			for (i in 0 until jsonArray.length()) {
				val url = jsonArray.optString(i, "")
				if (url.isNotBlank() && url.startsWith("http")) {
					imageUrls.add(url)
				}
			}
			imageUrls
		} catch (e: Exception) {
			System.err.println("[LxManga] WebView error: ${e.message}")
			emptyList()
		}
	}

	private fun decodeImageUrl(codes: List<Int>, actionToken: String): String {
		val result = StringBuilder(codes.size)
		codes.forEachIndexed { index, code ->
			val keyCode = actionToken[index % actionToken.length].code
			result.append((code xor keyCode).toChar())
		}
		return result.toString()
	}

	// ======================== Tags ========================

	private suspend fun availableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/the-loai").parseHtml()

		return doc.select("nav.grid button").mapNotNull { button ->
			val key = button.attr("wire:click")
				.substringAfterLast("', '")
				.substringBeforeLast("')")
			val title = button.select("span.text-ellipsis").text()
			if (key.isNotEmpty() && title.isNotEmpty()) {
				MangaTag(key = key, title = title, source = source)
			} else null
		}.toSet()
	}

	companion object {
		private val BACKGROUND_URL_REGEX = Regex("""background-image:\s*url\(['"]?([^'")]+)""", RegexOption.IGNORE_CASE)
		private val ACTION_TOKEN_REGEX = Regex("""<meta\s+name=["']action_token["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
		private val ENCRYPTED_IMAGES_REGEX = Regex("""var\s+_u\s*=\s*(\[\[.*?]]);""", RegexOption.DOT_MATCHES_ALL)
		private val ENCRYPTED_IMAGE_ROW_REGEX = Regex("""\[(\d+(?:,\d+)*)]""")

		@Volatile
		var lastActionToken: String? = null
			private set

		private const val PAGE_METADATA_SEPARATOR = "\u00A7\u00A7"

		/**
		 * JavaScript to inject into WebView after the chapter page loads.
		 * Waits for the anti-scraping WASM/JS to decrypt and set image sources,
		 * then collects all image URLs from #image-container img elements.
		 */
		private const val WEBVIEW_EXTRACT_SCRIPT = """
			(function() {
				return new Promise(function(resolve) {
					var maxWait = 15000;
					var checkInterval = 500;
					var elapsed = 0;

					function tryExtract() {
						var images = document.querySelectorAll('#image-container img');
						var urls = [];
						for (var i = 0; i < images.length; i++) {
							var src = images[i].getAttribute('src') || images[i].src || '';
							if (src && src.indexOf('http') === 0 && src.indexOf('favicon') === -1) {
								urls.push(src);
							}
						}

						if (urls.length > 0) {
							resolve(JSON.stringify(urls));
							return;
						}

						elapsed += checkInterval;
						if (elapsed >= maxWait) {
							// Final attempt: try data-src, background-image, etc.
							var containers = document.querySelectorAll('#image-container');
							for (var i = 0; i < containers.length; i++) {
								var img = containers[i].querySelector('img');
								if (img) {
									var url = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || '';
									if (!url) {
										var bg = window.getComputedStyle(containers[i]).backgroundImage;
										if (bg && bg !== 'none') {
											url = bg.replace(/^url\(["']?/, '').replace(/["']?\)$/, '');
										}
									}
									if (url && url.indexOf('http') === 0) {
										urls.push(url);
									}
								}
							}
							resolve(JSON.stringify(urls));
							return;
						}

						setTimeout(tryExtract, checkInterval);
					}

					// Start checking after initial delay for JS/WASM to initialize
					setTimeout(tryExtract, 2000);
				});
			})()
		"""

		private fun encodePageMetadata(chapterUrl: String, actionToken: String?, imageUrl: String): String {
			return "$chapterUrl$PAGE_METADATA_SEPARATOR${actionToken.orEmpty()}$PAGE_METADATA_SEPARATOR$imageUrl"
		}

		private fun decodePageMetadata(url: String): Triple<String, String?, String> {
			val parts = url.split(PAGE_METADATA_SEPARATOR, limit = 3)
			return when {
				parts.size == 3 -> Triple(parts[0], parts[1].ifBlank { null }, parts[2])
				parts.size == 2 -> Triple(parts[0], parts[1].ifBlank { null }, "")
				else -> Triple(url, null, "")
			}
		}
	}
}
