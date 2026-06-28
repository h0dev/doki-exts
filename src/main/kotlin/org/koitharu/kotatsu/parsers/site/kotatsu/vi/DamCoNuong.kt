package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DAMCONUONG", "Dâm Cô Nương", "vi", type = ContentType.HENTAI)
internal class DamCoNuong(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DAMCONUONG, 30) {

	// --- Các thuộc tính và hàm khởi tạo ---
	override val configKeyDomain = ConfigKey.Domain("damconuong.mom")
	private val availableTags = suspendLazy(initializer = ::fetchTags)
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)
	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("referer", "https://$domain")
		.build()

	// --- Các hàm lấy danh sách và chi tiết truyện ---
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val q = filter.query
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem")

			append("?sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "-updated_at"
					SortOrder.NEWEST -> "-created_at"
					SortOrder.POPULARITY -> "-views"
					SortOrder.ALPHABETICAL -> "name"
					SortOrder.ALPHABETICAL_DESC -> "-name"
					else -> "-updated_at" // Mặc định là cập nhật mới nhất
				},
			)

			if (filter.states.isNotEmpty()) {
				append("&filter[status]=")
				append(filter.states.joinToString(",") {
					when (it) {
						MangaState.ONGOING -> "2"
						MangaState.FINISHED -> "1"
						else -> "" // Bỏ qua các trạng thái không hỗ trợ
					}
				}.trimEnd(',')) // Xóa dấu phẩy cuối nếu có
			}

			if (filter.tags.isNotEmpty()) {
				append("&filter[accept_genres]=")
				append(filter.tags.joinToString(",") { it.key })
			}

			if (!q.isNullOrEmpty()) {
				append("&filter[name]=")
				append(q.urlEncoded())
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&filter[reject_genres]=")
				append(filter.tagsExclude.joinToString(",") { it.key })
			}

			append("&page=$page")
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc) // Gọi hàm parseMangaList đã chỉnh sửa
	}

	/**
	 * Phân tích danh sách truyện từ trang HTML.
	 * Hàm này đã được cập nhật để lấy ảnh bìa (poster) chính xác hơn
	 * và chuẩn hóa URL ảnh bìa.
	 */
	private fun parseMangaList(doc: Document): List<Manga> {
		// Chọn thẻ div chứa từng mục truyện bằng selector 'div.manga-vertical'
		return doc.select("div.manga-vertical").mapNotNull { element ->
			try {
				// Tìm thẻ 'a' bao quanh ảnh bìa, hỗ trợ cả relative "/truyen/" và absolute URL
				val coverLinkElement = element.selectFirst("a[href*=\"/truyen/\"]")
					?: return@mapNotNull null

				val href = coverLinkElement.attrAsRelativeUrl("href")

				// Tìm thẻ 'img' bên trong cover-frame
				val imgElement = coverLinkElement.selectFirst("div.cover-frame img")
					?: element.selectFirst("div.cover-frame img")
					?: return@mapNotNull null

				// Ưu tiên lấy 'src' (ảnh load trực tiếp từ CDN), fallback 'data-src' (lazy load)
				val rawCoverUrl = imgElement.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("svg") }
					?: imgElement.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
					?: imgElement.attr("alt").takeIf { it.isNotBlank() && it.startsWith("http") }
					?: return@mapNotNull null

				val finalCoverUrl = rawCoverUrl.trim()

				val title = imgElement.attr("alt").takeIf { it.isNotBlank() }
					?: element.selectFirst("div.p-3 h3 a")?.text()?.takeIf { it.isNotBlank() }
					?: "Không có tiêu đề"

				Manga(
					id = generateUid(href),
					title = title.trim(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = ContentRating.ADULT,
					coverUrl = finalCoverUrl,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			} catch (e: Exception) {
				null
			}
		}
	}


	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val altTitles = doc.select("span:containsOwn(Tên khác:)").mapNotNullToSet {
			it.parent()?.select("span.text-base")?.textOrNull()
				?: it.nextElementSibling()?.textOrNull()
		}

		val allTags = availableTags.getOrNull().orEmpty()
		val tags = doc.select("#genres-list a").mapNotNullToSet { a ->
			val title = a.text().toTitleCase()
			allTags.find { x -> x.title == title }
		}

		val stateText = doc.selectFirst("span:containsOwn(Tình trạng:)")?.parent()?.select("span")?.last()?.text()
			?: doc.selectFirst("span:containsOwn(Tình trạng:)")?.closest("div")?.select("span")?.lastOrNull()?.text()
		val state = when {
			stateText?.contains("Đang tiến hành", ignoreCase = true) == true -> MangaState.ONGOING
			stateText?.contains("Hoàn thành", ignoreCase = true) == true -> MangaState.FINISHED
			else -> null
		}

		val chapterListDiv = doc.selectFirst("#chapterList")
			?: throw ParseException("Không tìm thấy danh sách chapter!", url)

		val chapterLinks = chapterListDiv.select("a.block")
		val chapters = chapterLinks.mapChapters(reversed = true) { index, a ->
			val title = a.selectFirst("span.text-ellipsis")?.textOrNull()
				?: a.selectFirst("div.grow span")?.textOrNull()
			val href = a.attrAsRelativeUrl("href")
			val uploadDateText = a.selectFirst("span.ml-2.whitespace-nowrap")?.text()

			MangaChapter(
				id = generateUid(href),
				title = title,
				number = index + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(uploadDateText),
				branch = null,
				source = source,
			)
		}

		val description = doc.selectFirst("div.prose.dark\\:prose-invert")?.text()

		val author = doc.selectFirst("span:containsOwn(Author:) ~ span a")?.text()

		return manga.copy(
			altTitles = altTitles,
			tags = tags,
			state = state,
			chapters = chapters,
			description = description,
			author = author,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val contentDiv = doc.selectFirst("#chapter-content")
			?: throw ParseException("Không tìm thấy div#chapter-content chứa ảnh", url)

		val images = contentDiv.select("img.chapter-img, img[data-index]").mapNotNull { img ->
			// Ưu tiên data-src (lazy load), sau đó src (trang đầu thường load trực tiếp)
			val rawUrl = img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
				?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("svg") }

			if (rawUrl == null) return@mapNotNull null

			val finalUrl = rawUrl.trim()

			MangaPage(
				id = generateUid(finalUrl),
				url = finalUrl,
				preview = null,
				source = source,
			)
		}

		if (images.isNotEmpty()) {
			return images
		}

		throw ParseException("Không tìm thấy danh sách ảnh trong chapter", url)
	}

	// --- Các hàm tiện ích ---

	/**
	 * Phân tích chuỗi ngày tháng tương đối hoặc tuyệt đối thành timestamp (Long).
	 */
	private fun parseChapterDate(date: String?): Long {
		if (date.isNullOrBlank()) return 0L

		val calendar = Calendar.getInstance()

		return try {
			when {
				date.contains("giây trước") -> {
					val seconds = date.substringBefore(" giây trước").toLongOrNull() ?: 0
					calendar.add(Calendar.SECOND, -seconds.toInt())
					calendar.timeInMillis
				}
				date.contains("phút trước") -> {
					val minutes = date.substringBefore(" phút trước").toLongOrNull() ?: 0
					calendar.add(Calendar.MINUTE, -minutes.toInt())
					calendar.timeInMillis
				}
				date.contains("giờ trước") -> {
					val hours = date.substringBefore(" giờ trước").toLongOrNull() ?: 0
					calendar.add(Calendar.HOUR_OF_DAY, -hours.toInt())
					calendar.timeInMillis
				}
				date.contains("ngày trước") -> {
					val days = date.substringBefore(" ngày trước").toLongOrNull() ?: 0
					calendar.add(Calendar.DAY_OF_YEAR, -days.toInt())
					calendar.timeInMillis
				}
				date.contains("tuần trước") -> {
					val weeks = date.substringBefore(" tuần trước").toLongOrNull() ?: 0
					calendar.add(Calendar.WEEK_OF_YEAR, -weeks.toInt())
					calendar.timeInMillis
				}
				date.contains("tháng trước") -> {
					val months = date.substringBefore(" tháng trước").toLongOrNull() ?: 0
					calendar.add(Calendar.MONTH, -months.toInt())
					calendar.timeInMillis
				}
				date.contains("năm trước") -> {
					val years = date.substringBefore(" năm trước").toLongOrNull() ?: 0
					calendar.add(Calendar.YEAR, -years.toInt())
					calendar.timeInMillis
				}
				// Thử phân tích định dạng dd/MM/yyyy
				else -> SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(date)?.time ?: 0L
			}
		} catch (e: Exception) {
			System.err.println("Lỗi parse ngày: '$date' - ${e.message}")
			0L // Trả về 0 nếu có lỗi xảy ra
		}
	}


	/**
	 * Lấy danh sách các thể loại có sẵn từ trang tìm kiếm.
	 */
	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://$domain/tim-kiem"
		return try {
			val doc = webClient.httpGet(url).parseHtml()
			val genreLinks = doc.select("a[href*='/the-loai/']")
				.filter { it.text().isNotBlank() }

			genreLinks.mapNotNullToSet { a ->
				val href = a.attr("href")
				val key = href.substringAfterLast('/')
				val title = a.text().trim().toTitleCase(sourceLocale)
				if (key.isNotBlank() && title.isNotBlank() && key != "the-loai") {
					MangaTag(
						key = key,
						title = title,
						source = source,
					)
				} else {
					null
				}
			}
		} catch (e: Exception) {
			emptySet()
		}
	}
}
