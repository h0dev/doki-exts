package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
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

@MangaSourceParser("LXMANGA", "LXManga", "vi", type = ContentType.HENTAI)
internal class LxManga(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.LXMANGA, 24) {

	override val configKeyDomain = ConfigKey.Domain("lxmanga.space")

	override fun getRequestHeaders(): Headers = Headers.Builder()
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
				val statuses = filter.states.joinToString(",") { state ->
					when (state) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "paused"
						else -> ""
					}
				}.filter { it.isNotEmpty() }
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
			val titleElement = element.selectFirst("a.text-ellipsis[href^=/truyen/]") ?: return@mapNotNull null
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
		return element.absUrl("data-bg")
			.ifEmpty { parseBackgroundUrl(element.attr("style")) }
			.ifBlank { null }
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
		val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val title = root.selectFirst("div.flex.flex-row.truncate.mb-4 span.grow.text-lg.ml-1.text-ellipsis.font-semibold")
			?.text() ?: manga.title

		val author = root.infoRow("Tác giả:")
			?.select("a[href*=/tac-gia/]")
			?.joinToString { it.text() }
			?.ifEmpty { null }

		val altNames = root.infoRow("Tên khác:")
			?.select("a, span:not(.font-semibold)")
			?.joinToString { it.text().trim() }
			?.takeIf { it.isNotBlank() }

		val tags = root.infoRow("Thể loại:")
			?.select("a[href*=/the-loai/]")
			?.mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text(),
					source = source,
				)
			} ?: emptySet()

		val description = buildString {
			if (altNames != null) {
				append("Tên khác: ", altNames, "\n\n")
			}
			append(root.select("p:contains(Tóm tắt) ~ p").joinToString("\n") { it.wholeText() }.trim())
		}.trim().ifEmpty { null }

		val state = parseStatus(root.infoRow("Tình trạng:")?.text())

		val scanlator = root.infoRow("Thực hiện:")
			?.select("a")
			?.joinToString { it.text() }
			?.ifEmpty { null }

		val chapters = root.select("ul.overflow-y-auto a[href^=/truyen/]:has(span.timeago)")
			.ifEmpty { root.select("a[href^=/truyen/]:has(span.timeago)") }
			.mapChapters(reversed = true) { _, a ->
				val href = a.attrAsRelativeUrl("href")
				val name = a.selectFirst("span.text-ellipsis")?.text() ?: "Chapter"
				val dateText = a.selectFirst("span.timeago")?.attr("datetime").orEmpty()

				MangaChapter(
					id = generateUid(href),
					title = name,
					number = -1f,
					volume = 0,
					url = href,
					scanlator = scanlator,
					uploadDate = parseChapterDate(dateText),
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
			scanlator = scanlator,
			chapters = chapters,
		)
	}

	private fun Document.infoRow(label: String): Element? {
		return select("div").firstOrNull { row ->
			row.selectFirst("> span.font-semibold")?.text() == label
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
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val html = doc.outerHtml()

		// Try encrypted image decoding: _u variable + action_token meta
		val actionToken = ACTION_TOKEN_REGEX.find(html)?.groupValues?.get(1)
		val encryptedPayload = ENCRYPTED_IMAGES_REGEX.find(html)?.groupValues?.get(1)

		if (actionToken != null && encryptedPayload != null) {
			val encryptedRows = ENCRYPTED_IMAGE_ROW_REGEX.findAll(encryptedPayload)
				.mapNotNull { row ->
					row.groupValues.getOrNull(1)
						?.split(',')
						?.mapNotNull { it.toIntOrNull() }
						?.takeIf { it.isNotEmpty() }
				}
				.toList()

			val imageUrls = doc.select("#image-container[data-idx]")
				.mapNotNull { it.attr("data-idx").toIntOrNull() }
				.distinct()
				.sorted()
				.mapNotNull { idx -> encryptedRows.getOrNull(idx) }
				.map { codes -> decodeImageUrl(codes, actionToken) }
				.filter { it.isNotBlank() }

			if (imageUrls.isNotEmpty()) {
				// Store actionToken in companion for image request headers
				lastActionToken = actionToken
				return imageUrls.mapIndexed { index, url ->
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		}

		// Fallback: try simple img tags (older layout)
		return doc.select("div.text-center img, div.text-center div.lazy").mapNotNull { el ->
			val url = el.attr("data-src").ifBlank { null }
				?: el.attr("src").ifBlank { null }
				?: return@mapNotNull null
			if (url.isNotBlank()) {
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			} else null
		}.ifEmpty {
			throw Exception("Không tìm thấy dữ liệu ảnh")
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

	private fun availableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/the-loai").parseHtml()

		return doc.select("nav.grid button").mapNotNull { button ->
			val key = button.attr("wire:click")
				.substringAfterLast(", '")
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
	}
}
