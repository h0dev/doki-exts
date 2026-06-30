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
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

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
		System.err.println("[LxManga] getDetails: url=$fullUrl")
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val bodyHtml = doc.body().html()
		System.err.println("[LxManga] getDetails: title=${doc.title()}")

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

		// Debug: find Livewire components
		val wireInitialData = WIRE_INITIAL_DATA_REGEX.findAll(bodyHtml).toList()
		System.err.println("[LxManga] getDetails: wire:initial-data found=${wireInitialData.size}")
		wireInitialData.forEachIndexed { idx, match ->
			val snippet = match.groupValues[1].take(200)
			System.err.println("[LxManga] getDetails:   wire[$idx]=$snippet")
		}

		val livewireToken = LIVEWIRE_TOKEN_REGEX.find(bodyHtml)?.groupValues?.get(1)
		System.err.println("[LxManga] getDetails: livewire_token=${livewireToken != null}")

		// Try standard chapter selectors first
		val chapters = tryStandardChapterSelectors(doc)

		if (chapters.isEmpty() && livewireToken != null && wireInitialData.isNotEmpty()) {
			// Try Livewire approach
			System.err.println("[LxManga] getDetails: standard selectors failed, trying Livewire...")
			val livewireChapters = tryLivewireChapters(doc, bodyHtml, livewireToken)
			System.err.println("[LxManga] getDetails: livewire chapters count=${livewireChapters.size}")
			if (livewireChapters.isNotEmpty()) {
				return manga.copy(
					title = title,
					altTitles = setOfNotNull(altNames),
					state = state,
					tags = tags,
					authors = setOfNotNull(author),
					description = description,
					chapters = livewireChapters,
				)
			}
		}

		// Debug: if no chapters, dump more context
		if (chapters.isEmpty()) {
			System.err.println("[LxManga] getDetails: NO chapters found! Dumping more context...")
			// Search for any links that look like chapter links
			val allLinks = doc.select("a[href]")
			val chapterLikeLinks = allLinks.filter { a ->
				val href = a.attr("href")
				href.contains("chapter", ignoreCase = true) ||
					href.contains("chuong", ignoreCase = true) ||
					href.contains("chap", ignoreCase = true)
			}
			System.err.println("[LxManga] getDetails: chapter-like links=${chapterLikeLinks.size}")
			chapterLikeLinks.take(5).forEach { a ->
				System.err.println("[LxManga] getDetails:   ${a.attr("href")} -> ${a.text().take(50)}")
			}

			// Dump sections that might contain chapters
			val sections = doc.select("section, div[wire\\:id], div[x-data]")
			System.err.println("[LxManga] getDetails: sections/wire/x-data=${sections.size}")
			sections.take(5).forEach { s ->
				val tag = s.tagName()
				val id = s.attr("id").ifBlank { s.attr("wire:id").ifBlank { s.attr("x-data") } }
				val childCount = s.childrenSize()
				System.err.println("[LxManga] getDetails:   $tag id=$id children=$childCount")
			}
		}

		System.err.println("[LxManga] getDetails: final chapters count=${chapters.size}")

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

	private fun tryStandardChapterSelectors(doc: Document): List<MangaChapter> {
		// Try multiple selector strategies
		val selectors = listOf(
			"div#list-chapter div.chapter-item",
			"div#list-chapter-official div.chapter-item",
			"div.chapter-item",
			"div.chapter-list div",
			"div.list-chapter a",
			"div[wire\\:id] a[href*=chapter]",
			"div[wire\\:id] a[href*=chuong]",
		)

		for (selector in selectors) {
			val items = doc.select(selector)
			if (items.isNotEmpty()) {
				System.err.println("[LxManga] tryStandard: selector='$selector' found=${items.size}")
				return items.mapNotNull { chapterEl ->
					val link = chapterEl.selectFirst("a") ?: chapterEl.takeIf { it.tagName() == "a" } ?: return@mapNotNull null
					val href = link.attr("href").toRelativeUrl(domain)
					val name = link.text()
					val dateStr = chapterEl.selectFirst("span, span.text-xs, time")?.text().orEmpty()
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
			}
		}
		System.err.println("[LxManga] tryStandard: all selectors returned 0")
		return emptyList()
	}

	private fun tryLivewireChapters(doc: Document, bodyHtml: String, livewireToken: String): List<MangaChapter> {
		// Find wire:initial-data components that might be chapter lists
		val wireDataList = WIRE_INITIAL_DATA_REGEX.findAll(bodyHtml).map {
			it.groupValues[1]
		}.toList()

		for (wireDataStr in wireDataList) {
			try {
				val wireData = org.json.JSONObject(wireDataStr)
				val name = wireData.optString("name", "")
				val id = wireData.optString("id", "")
				val serverMemo = wireData.optJSONObject("serverMemo") ?: continue
				val data = serverMemo.optJSONObject("data") ?: continue

				System.err.println("[LxManga] tryLivewire: component=$name id=$id data_keys=${data.keys().asSequence().toList()}")

				// Check if this component has chapter-related data
				val dataStr = data.toString()
				val hasChapters = dataStr.contains("chapter", ignoreCase = true) ||
					dataStr.contains("chuong", ignoreCase = true) ||
					name.contains("chapter", ignoreCase = true)
				if (!hasChapters) continue

				System.err.println("[LxManga] tryLivewire: found chapter component! name=$name")

				// Build Livewire request
				val checksum = wireData.optString("checksum", "")
				val payload = org.json.JSONObject().apply {
					put("fingerprint", org.json.JSONObject().apply {
						put("id", id)
						put("name", name)
						put("locale", "vi")
						put("path", doc.location())
						put("method", "GET")
						put("data", org.json.JSONObject()) // empty initial data
					})
					put("serverMemo", org.json.JSONObject().apply {
						put("dataMeta", org.json.JSONObject())
						put("htmlHash", serverMemo.optString("htmlHash", ""))
						put("data", data)
						put("checksum", checksum)
					})
					put("updates", org.json.JSONArray())
				}

				val requestBody = payload.toString()
					.toRequestBody("application/json".toMediaTypeOrNull())

				val request = Request.Builder()
					.url("https://$domain/livewire/message/$name")
					.post(requestBody)
					.headers(getRequestHeaders())
					.addHeader("X-Livewire", "true")
					.addHeader("X-CSRF-TOKEN", livewireToken)
					.addHeader("Accept", "text/html, application/xhtml+xml")
					.addHeader("Content-Type", "application/json; charset=utf-8")
					.build()

				val response = context.httpClient.newCall(request).execute()
				val responseBody = response.body?.string() ?: continue
				System.err.println("[LxManga] tryLivewire: response length=${responseBody.length}")

				// Parse Livewire JSON response
				val responseJson = org.json.JSONObject(responseBody)
				val responseHtml = responseJson.optString("effects.html", "")
				System.err.println("[LxManga] tryLivewire: response HTML length=${responseHtml.length}")

				if (responseHtml.isNotEmpty()) {
					val responseDoc = org.jsoup.Jsoup.parseBodyFragment(responseHtml)
					val links = responseDoc.select("a[href*=chapter], a[href*=chuong], a[href*=chap]")
					System.err.println("[LxManga] tryLivewire: chapter links in response=${links.size}")

					if (links.isNotEmpty()) {
						return links.map { a ->
							val href = a.attr("href").toRelativeUrl(domain)
							val name2 = a.text()
							MangaChapter(
								id = generateUid(href),
								title = name2,
								number = name2.substringAfter(" ").toFloatOrNull() ?: -1f,
								volume = 0,
								url = href,
								scanlator = null,
								uploadDate = 0L,
								branch = null,
								source = source,
							)
						}
					}
				}
			} catch (e: Exception) {
				System.err.println("[LxManga] tryLivewire: error=${e.message}")
			}
		}

		return emptyList()
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
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
		}
	}

	// ======================== Pages ========================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		System.err.println("[LxManga] getPages: url=$fullUrl")
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val html = doc.outerHtml()
		System.err.println("[LxManga] getPages: html length=${html.length}")

		// Try encrypted image decoding: _u variable + action_token meta
		val actionToken = ACTION_TOKEN_REGEX.find(html)?.groupValues?.get(1)
		val encryptedPayload = ENCRYPTED_IMAGES_REGEX.find(html)?.groupValues?.get(1)
		System.err.println("[LxManga] getPages: actionToken=${actionToken != null} encryptedPayload=${encryptedPayload != null}")

		if (actionToken != null && encryptedPayload != null) {
			val encryptedRows = ENCRYPTED_IMAGES_REGEX.find(html)
				?.groupValues?.get(1)
				?.let { payload ->
					ENCRYPTED_IMAGE_ROW_REGEX.findAll(payload)
						.map { match ->
							match.groupValues[1]
								.split(",")
								.mapNotNull { it.toIntOrNull() }
								.takeIf { it.isNotEmpty() }
						}
						.toList()
				} ?: emptyList()
			System.err.println("[LxManga] getPages: encryptedRows count=${encryptedRows.size}")

			val imageUrls = doc.select("#image-container[data-idx]")
				.mapNotNull { it.attr("data-idx").toIntOrNull() }
				.distinct()
				.sorted()
				.mapNotNull { idx ->
					encryptedRows.getOrNull(idx)
						?.let { codes -> decodeImageUrl(codes, actionToken) }
						?.takeIf { it.isNotBlank() }
				}.ifEmpty {
					System.err.println("[LxManga] getPages: no imageUrls from encrypted path!")
					throw Exception("Không tìm thấy dữ liệu ảnh")
				}
			System.err.println("[LxManga] getPages: imageUrls count=${imageUrls.size}")

			lastActionToken = actionToken
			return imageUrls.map { url ->
				val fullImageUrl = "https://$domain$url"
				MangaPage(
					id = generateUid(fullImageUrl),
					url = encodePageMetadata(fullUrl, actionToken, fullImageUrl),
					preview = null,
					source = source,
				)
			}
		}

		// Fallback: try simple img tags
		System.err.println("[LxManga] getPages: fallback to img tags")
		val imgPages = doc.select("div.text-center img, div.text-center div.lazy").mapNotNull {
			val url = it.attr("data-src").ifBlank { null }
				?: it.attr("src").ifBlank { null }
				?: return@mapNotNull null
			if (url.isNotBlank()) {
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			} else null
		}
		System.err.println("[LxManga] getPages: fallback imgPages count=${imgPages.size}")

		if (imgPages.isEmpty()) {
			val allImgs = doc.select("img")
			System.err.println("[LxManga] getPages: all img tags=${allImgs.size}")
			allImgs.take(5).forEach { img ->
				System.err.println("[LxManga] getPages:   img src=${img.attr("src")} data-src=${img.attr("data-src")}")
			}
			val allDataIdx = doc.select("[data-idx]")
			System.err.println("[LxManga] getPages: all data-idx elements=${allDataIdx.size}")
		}

		return imgPages
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
		private val WIRE_INITIAL_DATA_REGEX = Regex("""wire:initial-data="([^"]+)"""")
		private val LIVEWIRE_TOKEN_REGEX = Regex("""livewire_token\s*=\s*['"]([^'"]+)['"]""")

		@Volatile
		var lastActionToken: String? = null
			private set

		private const val PAGE_METADATA_SEPARATOR = "\u00A7\u00A7"

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
