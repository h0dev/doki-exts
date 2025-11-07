package org.dokiteam.doki.parsers.site.madara.vi

import androidx.collection.ArrayMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers // Import Headers
import org.jsoup.nodes.Document
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.exception.NotFoundException
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.site.madara.MadaraParser // Dùng base Madara
import org.dokiteam.doki.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Parser cho HentaiVN.world, dựa trên MadaraParser.
 * Ghi đè logic lấy danh sách (getListPage) vì trang này không dùng AJAX cho danh sách
 * và tùy chỉnh lại logic parse (parseMangaList) cho đúng DOM.
 */
@MangaSourceParser("HENTAIWORLD", "HentaiVN.world", "vi", ContentType.HENTAI)
internal class HentaiVNWorld(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HENTAIWORLD, "hentaivn.world") {

	override val listUrl = "/"
	override val tagPrefix = "the-loai/"
	override val datePattern = "dd/MM/yyyy"
	override val authorSearchSupported = true
	override val withoutAjax = true

	/**
	 * Thêm Referer header
	 * Thêm Referer để bypass hotlink protection (anti-leech)
	 * khi tải ảnh từ CDN (vd: cdn.hentaicube.xyz).
	 */
	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://".plus(domain).plus("/"))
		.build()

	/**
	 * Ghi đè `getListPage` để xây dựng URL theo kiểu không-AJAX (withoutAjax = true).
	 */
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pages = page + 1 // Madara dùng page 1-based

		val url = buildString {
			// 1. Xử lý tìm kiếm tác giả (ưu tiên cao nhất)
			if (authorSearchSupported && !filter.author.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)
				append("/tac-gia/")
				append(filter.author!!.lowercase().replace(" ", "-"))

				if (pages > 1) {
					append("/page/")
					append(pages.toString())
				}

				append("/?m_orderby=") // Sắp xếp cho trang tác giả
				when (order) {
					SortOrder.POPULARITY -> append("views")
					SortOrder.UPDATED -> append("latest")
					SortOrder.NEWEST -> append("new-manga")
					SortOrder.ALPHABETICAL -> {}
					SortOrder.RATING -> append("rating")
					SortOrder.RELEVANCE -> {}
					else -> append("latest")
				}
				return@buildString // Hoàn thành URL cho tìm kiếm tác giả
			}

			// 2. Xử lý tìm kiếm (query)
			if (!filter.query.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)
				
				if (pages > 1) {
					append("/page/")
					append(pages.toString())
				}

				append("/?s=")
				append(filter.query.urlEncoded())
				append("&post_type=wp-manga")
			} else {
				// 3. Xử lý duyệt (browse)
				clear()
				append("https://")
				append(domain)
				append(listUrl) // "/"

				if (pages > 1) {
					append("page/") 
					append(pages.toString())
					append("/")
				}
				append("?") 
			}

			// Thêm sorting (cho search và browse)
			val orderKey = when (order) {
				SortOrder.POPULARITY -> "views"
				SortOrder.UPDATED -> "latest"
				SortOrder.NEWEST -> "new-manga"
				SortOrder.ALPHABETICAL -> "alphabet"
				SortOrder.RATING -> "rating"
				SortOrder.RELEVANCE -> if (!filter.query.isNullOrEmpty()) "" else "latest"
				else -> "latest"
			}
			
			if (orderKey.isNotEmpty()) {
				if (!filter.query.isNullOrEmpty() || !this.endsWith("?")) {
					append("&m_orderby=")
				} else {
					append("m_orderby=") // Thêm ngay sau '?'
				}
				append(orderKey)
			}

			// Thêm filters
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
					else -> { /* Bỏ qua */ }
				}
			}
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	/**
	 * Ghi đè `parseMangaList` để khớp với DOM của `main.html`.
	 */
	override fun parseMangaList(doc: Document): List<Manga> {
		val elements = doc.select("div.page-listing-item div.page-item-detail")
		if (elements.isEmpty() && doc.selectFirst(".search-wrap.no-results") != null) {
			return emptyList()
		}

		return elements.mapNotNull { item ->
			val titleAnchor = item.selectFirst("div.item-summary .post-title h3 a") ?: return@mapNotNull null
			val absUrl = titleAnchor.attrAsAbsoluteUrlOrNull("href") ?: return@mapNotNull null
			val slug = absUrl.removeSuffix("/").substringAfterLast('/')
			val coverImg = item.selectFirst("div.item-thumb a img")
			
			// SỬA LỖI: Bỏ .replace(" ", "%20")
			val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src"))
				?.trim() // Vẫn giữ trim()
				?.toAbsoluteUrl(domain).orEmpty() // Đảm bảo URL tuyệt đối

			Manga(
				id = generateUid(slug),
				title = titleAnchor.text(),
				altTitles = emptySet(),
				url = absUrl.toRelativeUrl(domain), 
				publicUrl = absUrl,
				rating = item.selectFirst("div.meta-item.rating span.score")?.text()?.toFloatOrNull()?.div(5f)
					?: RATING_UNKNOWN,
				contentRating = ContentRating.ADULT, 
				coverUrl = coverUrl, // URL này phải tuyệt đối
				largeCoverUrl = null,
				tags = emptySet(), 
				state = null, 
				authors = emptySet(), 
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	// Định nghĩa selector cho các trường chi tiết
	override val selectDesc = "div.description-summary div.summary__content"
	override val selectState = "div.post-content_item:has(div.summary-heading:contains(Trạng thái)) div.summary-content"
	val selectAut = "div.author-content a" 
	override val selectGenre = "div.genres-content a"

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml() 

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		
		val testCheckAsync = doc.select(selectTestAsync) 
		val chaptersDeferred = if (testCheckAsync.isNullOrEmpty()) {
			async { loadChapters(href, doc) } 
		} else {
			async { getChapters(manga, doc) } 
		}

		val desc = doc.select(selectDesc).html()
		val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")

		val state = stateDiv?.let {
			when (it.text().lowercase().trim()) { 
				in ongoing, "đang tiến hành", "đang cập nhật" -> MangaState.ONGOING
				in finished, "hoàn thành", "completed" -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}

		val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()
		val authors = doc.select(selectAut).mapNotNullToSet { it.text() }
		val tags = doc.body().select(selectGenre).mapToSet { a -> createMangaTag(a) }.filterNotNull().toSet()

		manga.copy(
			title = doc.selectFirst("div.post-title h1")?.textOrNull() ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			tags = tags,
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			authors = authors,
			chapters = chaptersDeferred.await(),
			rating = doc.selectFirst("div.post-rating span.score")?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
			contentRating = ContentRating.ADULT
		)
	}

	/**
	 * Ghi đè `selectDate`: Dùng cho logic `loadChapters` (AJAX).
	 */
	override val selectDate = "span.chapter-release-date"

	/**
	 * Ghi đè `getPages`: Selector cho từng ảnh trong trang đọc (read.html)
	 */
	override val selectPage = "div.reading-content div.item img"

	/**
	 * Ghi đè `getPages` để xử lý logic lấy ảnh.
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml() 

		val pageElements = doc.select(selectPage)

		return pageElements.mapNotNull { imgElement ->
			// SỬA LỖI: Bỏ .replace(" ", "%20")
			val imgUrl = (imgElement.attr("data-src")?.trim() ?: imgElement.attr("src")?.trim())
				?.toAbsoluteUrl(domain) // Đảm bảo URL tuyệt đối

			if (imgUrl.isNullOrEmpty()) {
				null
			} else {
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl, // URL này phải tuyệt đối
					preview = null,
					source = source,
				)
			}
		}
	}
}
