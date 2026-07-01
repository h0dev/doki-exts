package org.koitharu.kotatsu.parsers.site.kotatsu.vi

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TRUYENMM", "TruyenMM", "vi", type = ContentType.HENTAI)
internal class TruyenMM(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TRUYENMM, 20) {

	override val configKeyDomain = ConfigKey.Domain("truyenmmhayr.com")

	override fun getRequestHeaders() = Headers.Builder()
		.add("Referer", "https://$domain/")
		.build()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		val url = if (!query.isNullOrEmpty()) {
			"https://$domain/tim-kiem?key=${query.urlEncoded()}&page=$page"
		} else {
			when (order) {
				SortOrder.POPULARITY -> "https://$domain/danh-sach-truyen/$page"
				SortOrder.NEWEST -> "https://$domain/truyen-moi-cap-nhat/$page"
				else -> "https://$domain/danh-sach-truyen/$page"
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.book_list .book_avatar").mapNotNull { element ->
			val a = element.selectFirst("a") ?: return@mapNotNull null
			val href = a.absUrl("href").toRelativeUrl(domain)
			val title = a.attr("title").ifEmpty { a.text() }
			val coverUrl = element.selectFirst("img")?.absUrl("src")
			Manga(
				id = generateUid(href),
				title = title,
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

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("h1")?.text() ?: manga.title
		val description = doc.selectFirst("div.story-detail-info p, div.story-info p")?.text()
		val state = doc.selectFirst("span.status")?.text()?.let {
			when {
				it.contains("Đang cập nhật", ignoreCase = true) -> MangaState.ONGOING
				it.contains("Hoàn thành", ignoreCase = true) -> MangaState.FINISHED
				else -> null
			}
		}
		val tags = doc.select("a[href*=/the-loai/]").mapToSet { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = a.text(),
				source = source,
			)
		}
		val chapters = doc.select("div.chapter-list a, ul.list-chapter a").mapNotNull { a ->
			val href = a.absUrl("href").toRelativeUrl(domain)
			val name = a.selectFirst(".chapter-name, span")?.text() ?: "Chapter"
			val dateStr = a.selectFirst(".time, span.time")?.text().orEmpty()
			MangaChapter(
				id = generateUid(href),
				title = name,
				number = name.substringAfterLast(" ").toFloatOrNull() ?: -1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseDate(dateStr),
				branch = null,
				source = source,
			)
		}
		return manga.copy(
			title = title,
			state = state,
			tags = tags,
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.chapter-content img, div.reading-detail img, div.text-center img").mapNotNull { img ->
			val url = img.attr("data-src").ifEmpty { img.absUrl("src") }
			if (url.isNotEmpty()) {
				MangaPage(generateUid(url), url, null, source)
			} else null
		}
	}

	private fun parseDate(dateStr: String): Long {
		if (dateStr.isBlank()) return 0L
		return try {
			DATE_FORMAT.parse(dateStr)?.time ?: 0L
		} catch (_: Exception) {
			0L
		}
	}

	private val DATE_FORMAT by lazy {
		SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
