package org.dokiteam.doki.parsers.site.vi

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.util.*

@MangaSourceParser("KURONEKO", "Kuro Neko / vi-Hentai", "vi", type = ContentType.HENTAI)
internal class KuroNeko(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.KURONEKO, 30) {

	override val configKeyDomain = ConfigKey.Domain("vi-hentai.moe", "vi-hentai.org")

	companion object {
		// Rate limit for getPages: 15 requests per minute -> 60,000ms / 15 = 4000ms per request
		private const val PAGES_REQUEST_DELAY_MS = 5000L
		private val pagesRequestMutex = Mutex()
		private var lastPagesRequestTime = 0L
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			if (!filter.author.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)

				append("/tac-gia/")
				append(filter.author.lowercase().replace(" ", "-"))

				append("?sort=")
				append(
					when (order) {
						SortOrder.POPULARITY -> "-views"
						SortOrder.UPDATED -> "-updated_at"
						SortOrder.NEWEST -> "-created_at"
						SortOrder.ALPHABETICAL -> "name"
						SortOrder.ALPHABETICAL_DESC -> "-name"
						else -> "-updated_at"
					},
				)

				append("&page=")
				append(page)

				append("&filter[status]=")
				filter.states.forEach {
					append(
						when (it) {
							MangaState.ONGOING -> "2,"
							MangaState.FINISHED -> "1,"
							else -> "2,1"
						},
					)
				}

				return@buildString
			}

			append("https://")
			append(domain)

			append("/tim-kiem")
			append("?sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "-views"
					SortOrder.UPDATED -> "-updated_at"
					SortOrder.NEWEST -> "-created_at"
					SortOrder.ALPHABETICAL -> "name"
					SortOrder.ALPHABETICAL_DESC -> "-name"
					else -> "-updated_at"
				},
			)

			if (!filter.query.isNullOrEmpty()) {
				append("&keyword=")
				append(filter.query.urlEncoded())
			}



			if (page > 1) {
				append("&page=")
				append(page)
			}

			append("&filter[status]=")
			filter.states.forEach {
				append(
					when (it) {
						MangaState.ONGOING -> "2,"
						MangaState.FINISHED -> "1,"
						else -> "2,1"
					},
				)
			}

			if (filter.tags.isNotEmpty()) {
				append("&filter[accept_genres]=")
				filter.tags.joinTo(this, separator = ",") { it.key }
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&filter[reject_genres]=")
				filter.tagsExclude.joinTo(this, separator = ",") { it.key }
			}
		}

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("div.grid div.relative")
			.map { div ->
				val href = div.selectFirst("a[href^=/truyen/]")?.attrOrNull("href")
					?: div.parseFailed("Không thể tìm thấy nguồn ảnh của Manga này!")
				val coverUrl = div.selectFirst("div.cover")?.attr("style")
					?.substringAfter("url('")?.substringBefore("')")

				Manga(
					id = generateUid(href),
					title = div.select("div.p-2 a.text-ellipsis").text(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = ContentRating.ADULT,
					coverUrl = coverUrl.orEmpty(),
					tags = setOf(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val author = root.selectFirst("div.mt-2:contains(Tác giả) span a")?.textOrNull()

		return manga.copy(
			altTitles = setOfNotNull(root.selectLast("div.grow div:contains(Tên khác) span")?.textOrNull()),
			state = when (root.selectFirst("div.mt-2:contains(Tình trạng) span.text-blue-500")?.text()) {
				"Đang tiến hành" -> MangaState.ONGOING
				"Đã hoàn thành" -> MangaState.FINISHED
				else -> null
			},
			tags = root.select("div.mt-2:contains(Thể loại) a.bg-gray-500").mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text(),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = root.selectFirst("meta[name=description]")?.attrOrNull("content"),
			chapters = root.select("div.justify-between ul.overflow-y-auto.overflow-x-hidden a")
				.mapChapters(reversed = true) { i, a ->
					val href = a.attrAsRelativeUrl("href")
					val name = a.selectFirst("span.text-ellipsis")?.text().orEmpty()
					val dateText = a.parent()?.selectFirst("span.timeago")?.attr("datetime").orEmpty()
					val scanlator = root.selectFirst("div.mt-2:contains(Nhóm dịch) span a")?.textOrNull()

					MangaChapter(
						id = generateUid(href),
						title = name,
						number = i.toFloat(),
						volume = 0,
						url = href,
						scanlator = scanlator,
						uploadDate = parseDateTime(dateText),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// Apply rate limiting specifically for fetching pages
		pagesRequestMutex.withLock {
			val currentTime = System.currentTimeMillis()
			val timeSinceLastRequest = currentTime - lastPagesRequestTime
			if (timeSinceLastRequest < PAGES_REQUEST_DELAY_MS) {
				delay(PAGES_REQUEST_DELAY_MS - timeSinceLastRequest)
			}
			lastPagesRequestTime = System.currentTimeMillis()
		}

		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		return doc.select("div.text-center img").mapNotNull { img ->
			// Lấy 'src' hoặc 'data-src' nếu 'src' rỗng, trả về null nếu cả hai đều rỗng
			val url = img.attr("src").takeIf { it.isNotBlank() }
				?: img.attr("data-src").takeIf { it.isNotBlank() }
				?: return@mapNotNull null

			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	// Đã thay đổi: Hardcode tags để tối ưu hiệu suất, tránh gọi request không cần thiết
	private fun availableTags(): Set<MangaTag> = setOf(
		MangaTag(key = "4", title = "3D", source = source),
		MangaTag(key = "5", title = "Ahegao", source = source),
		MangaTag(key = "6", title = "Anal", source = source),
		MangaTag(key = "7", title = "Big Ass", source = source),
		MangaTag(key = "8", title = "Big Boobs", source = source),
		MangaTag(key = "9", title = "BDSM", source = source),
		MangaTag(key = "10", title = "Blowjob", source = source),
		MangaTag(key = "11", title = "Bondage", source = source),
		MangaTag(key = "12", title = "Cosplay", source = source),
		MangaTag(key = "13", title = "Dark Skin", source = source),
		MangaTag(key = "14", title = "Double Penetration", source = source),
		MangaTag(key = "15", title = "Full Color", source = source),
		MangaTag(key = "16", title = "Futanari", source = source),
		MangaTag(key = "17", title = "Gender Bender", source = source),
		MangaTag(key = "18", title = "Harem", source = source),
		MangaTag(key = "19", title = "Incest", source = source),
		MangaTag(key = "20", title = "Mind Break", source = source),
		MangaTag(key = "21", title = "Mind Control", source = source),
		MangaTag(key = "22", title = "Monster", source = source),
		MangaTag(key = "23", title = "Nakadashi", source = source),
		MangaTag(key = "24", title = "Netorare", source = source),
		MangaTag(key = "25", title = "Loli", source = source),
		MangaTag(key = "26", title = "Rape", source = source),
		MangaTag(key = "27", title = "Milf", source = source),
		MangaTag(key = "28", title = "Rimjob", source = source),
		MangaTag(key = "29", title = "Schoolgirl", source = source),
		MangaTag(key = "30", title = "Shota", source = source),
		MangaTag(key = "31", title = "Tentacles", source = source),
		MangaTag(key = "32", title = "Yuri", source = source),
		MangaTag(key = "33", title = "Yaoi", source = source),
		MangaTag(key = "34", title = "Trap", source = source),
		MangaTag(key = "35", title = "School Uniform", source = source),
		MangaTag(key = "36", title = "Swimsuit", source = source),
		MangaTag(key = "37", title = "Pregnant", source = source),
		MangaTag(key = "38", title = "Elf", source = source),
		MangaTag(key = "39", title = "Teacher", source = source),
		MangaTag(key = "40", title = "Stockings", source = source),
		MangaTag(key = "41", title = "Masturbation", source = source),
		MangaTag(key = "42", title = "Netori", source = source),
		MangaTag(key = "43", title = "Cheating", source = source),
		MangaTag(key = "44", title = "X-ray", source = source),
		MangaTag(key = "45", title = "Forced", source = source),
		MangaTag(key = "46", title = "Handjob", source = source),
		MangaTag(key = "47", title = "Footjob", source = source),
		MangaTag(key = "48", title = "Sportswear", source = source),
		MangaTag(key = "49", title = "Deepthroat", source = source),
		MangaTag(key = "50", title = "Oppai", source = source),
		MangaTag(key = "51", title = "Ryona", source = source),
		MangaTag(key = "52", title = "Parasite", source = source),
		MangaTag(key = "53", title = "Scat", source = source),
		MangaTag(key = "54", title = "Guro", source = source),
		MangaTag(key = "55", title = "Fellatio", source = source),
		MangaTag(key = "56", title = "Manga", source = source),
		MangaTag(key = "57", title = "Manhwa", source = source),
		MangaTag(key = "58", title = "Manhua", source = source),
		MangaTag(key = "59", title = "Oneshot", source = source),
		MangaTag(key = "60", title = "Doujinshi", source = source),
		MangaTag(key = "61", title = "Comic", source = source),
		MangaTag(key = "62", title = "NTR", source = source)
	)

	private fun parseDateTime(dateStr: String): Long = runCatching {
		val parts = dateStr.split(' ')
		val dateParts = parts[0].split('-')
		val timeParts = parts[1].split(':')

		val calendar = Calendar.getInstance()
		calendar.set(
			dateParts[0].toInt(),
			dateParts[1].toInt() - 1,
			dateParts[2].toInt(),
			timeParts[0].toInt(),
			timeParts[1].toInt(),
			timeParts[2].toInt(),
		)
		calendar.timeInMillis
	}.getOrDefault(0L)
}
