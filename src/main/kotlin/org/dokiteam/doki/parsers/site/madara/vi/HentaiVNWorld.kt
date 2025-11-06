package org.dokiteam.doki.parsers.site.madara.vi

import androidx.collection.ArrayMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

	/**
	 * Trang chủ là danh sách truyện, không phải /manga/
	 * (Tham khảo: main.html)
	 */
	override val listUrl = "/"

	/**
	 * Tiền tố của tag (Thể loại) là /the-loai/
	 * (Tham khảo: main.html, ifno.html)
	 */
	override val tagPrefix = "the-loai/"

	/**
	 * Định dạng ngày tháng
	 * (Tham khảo: main.html, các chapter list)
	 */
	override val datePattern = "dd/MM/yyyy"

	/**
	 * Trang này hỗ trợ tìm kiếm tác giả
	 * (Tham khảo: ifno.html, link tác giả dạng /tac-gia/...)
	 */
	override val authorSearchSupported = true

	/**
	 * Quan trọng: Đặt là 'true'
	 * Trang này tải danh sách bằng cách reload trang với URL param (giống HentaiVnPlus)
	 * chứ không dùng AJAX (action=madara_load_more) như base class.
	 */
	override val withoutAjax = true

	/**
	 * Ghi đè `getListPage` để xây dựng URL theo kiểu không-AJAX (withoutAjax = true).
	 * Logic này tham khảo từ `HentaiVnPlus` và được điều chỉnh cho `hentaivn.world`.
	 * (Tham khảo: main.html cho pagination và sorting, search.html cho search)
	 */
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pages = page + 1 // Madara dùng page 1-based

		val url = buildString {
			// Xử lý tìm kiếm tác giả (ưu tiên cao nhất)
			if (authorSearchSupported && !filter.author.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)
				append("/tac-gia/")
				append(filter.author.lowercase().replace(" ", "-")) // VD: /tac-gia/shunjou-shuusuke/

				if (pages > 1) {
					append("/page/")
					append(pages.toString())
				}

				// Sorting cho trang tác giả (nếu có)
				append("/?m_orderby=")
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

			// Xử lý tìm kiếm (query) hoặc duyệt (browse)
			append("https://")
			append(domain)

			// Pagination cho duyệt (browse)
			if (filter.query.isNullOrEmpty() && pages > 1) {
				append("/page/")
				append(pages.toString())
			}

			// Param tìm kiếm
			append("/?s=")
			filter.query?.let {
				append(it.urlEncoded())
			}

			append("&post_type=wp-manga") // Param chuẩn của Madara

			// Filter thể loại (genre)
			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach {
					append("&genre[]=")
					append(it.key) // key là slug, vd: 'doujinshi'
				}
			}

			// Filter trạng thái (status)
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

			// Sorting (dựa theo main.html)
			append("&m_orderby=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("latest")
				SortOrder.NEWEST -> append("new-manga")
				SortOrder.ALPHABETICAL -> append("alphabet")
				SortOrder.RATING -> append("rating")
				SortOrder.RELEVANCE -> {} // Mặc định
				else -> append("latest")
			}

			// Pagination cho tìm kiếm (khác với browse)
			if (!filter.query.isNullOrEmpty() && pages > 1) {
				append("&page=")
				append(pages.toString())
			}
		}
		// Phải parseMangaList(doc) chứ không phải (doc, tagMap) vì ta đang override
		// hàm parseMangaList(doc)
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	/**
	 * Ghi đè `parseMangaList` để khớp với DOM của `main.html`.
	 * Base class dùng selector `div.row.c-tabs-item__content` không khớp.
	 */
	override fun parseMangaList(doc: Document): List<Manga> {
		// Selector chính cho từng item truyện (từ main.html)
		val elements = doc.select("div.page-listing-item div.page-item-detail")

		// Xử lý trường hợp không tìm thấy kết quả (từ search.html)
		if (elements.isEmpty() && doc.selectFirst(".search-wrap.no-results") != null) {
			return emptyList()
		}

		return elements.mapNotNull { item ->
			val titleAnchor = item.selectFirst("div.item-summary .post-title h3 a") ?: return@mapNotNull null
			val absUrl = titleAnchor.attrAsAbsoluteUrlOrNull("href") ?: return@mapNotNull null
			// Lấy slug từ URL, loại bỏ dấu / ở cuối nếu có
			val slug = absUrl.removeSuffix("/").substringAfterLast('/')

			val coverImg = item.selectFirst("div.item-thumb a img")
			// Dùng data-src vì trang dùng lazyload, nếu không có thì fallback về src
			val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src"))
				?.toRelativeUrl(domain).orEmpty()

			Manga(
				id = generateUid(slug),
				title = titleAnchor.text(),
				altTitles = emptySet(),
				url = absUrl.toRelativeUrl(domain),
				publicUrl = absUrl,
				// Selector rating riêng của HentaiVN.world (từ main.html)
				rating = item.selectFirst("div.meta-item.rating span.score")?.text()?.toFloatOrNull()?.div(5f)
					?: RATING_UNKNOWN,
				
				// SỬA LỖI: Đổi ContentType.HENTAI thành ContentRating.ADULT
				contentRating = ContentRating.ADULT, 
				
				coverUrl = coverUrl,
				largeCoverUrl = null,
				tags = emptySet(), // Sẽ lấy ở getDetails
				state = null, // Sẽ lấy ở getDetails
				authors = emptySet(), // Sẽ lấy ở getDetails
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	/**
	 * Ghi đè `getDetails`:
	 * Chúng ta KHÔNG gọi super.getDetails() vì cần truy cập 'doc'
	 * để lấy rating.
	 */
	// Định nghĩa selector cho các trường chi tiết
	override val selectDesc = "div.description-summary div.summary__content"
	override val selectState = "div.post-content_item:has(div.summary-heading:contains(Trạng thái)) div.summary-content"
	// SỬA LỖI 1: Xóa 'override' vì 'selectAut' không có trong MadaraParser base
	val selectAut = "div.author-content a" // Selector cho Tác giả
	override val selectGenre = "div.genres-content a" // Selector cho Thể loại

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		// SỬA LỖI 2: Tải 'doc' cục bộ
		val doc = webClient.httpGet(fullUrl).parseHtml() 

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		
		// Logic AJAX chapter của Madara (kế thừa từ base class)
		val testCheckAsync = doc.select(selectTestAsync) // selectTestAsync = "div.listing-chapters_wrap"
		val chaptersDeferred = if (testCheckAsync.isNullOrEmpty()) {
			// 'ifno.html' *sẽ* vào đây, vì nó dùng 'div#manga-chapters-holder'
			async { loadChapters(href, doc) } // Gọi logic AJAX của base class
		} else {
			async { getChapters(manga, doc) } // Fallback nếu có chapter tĩnh
		}

		// Dùng selector đã override
		val desc = doc.select(selectDesc).html()
		val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")

		val state = stateDiv?.let {
			when (it.text().lowercase().trim()) { // Thêm .trim()
				in ongoing, "đang tiến hành", "đang cập nhật" -> MangaState.ONGOING
				in finished, "hoàn thành", "completed" -> MangaState.FINISHED // Thêm từ 'ifno.html'
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}

		// HentaiVN.world (ifno.html) không có alt titles
		val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()

		// Lấy tác giả bằng selector cục bộ (đã bỏ override)
		val authors = doc.select(selectAut).mapNotNullToSet { it.text() }

		manga.copy(
			title = doc.selectFirst("div.post-title h1")?.textOrNull() ?: manga.title, // Selector title 'ifno.html'
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			// Dùng selector đã override
			tags = doc.body().select(selectGenre).mapToSet { a -> createMangaTag(a) }.filterNotNull().toSet(),
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			authors = authors, // Gán tác giả đã lấy
			chapters = chaptersDeferred.await(),
			
			// SỬA LỖI 2: Dùng 'doc' cục bộ để lấy rating
			rating = doc.selectFirst("div.post-rating span.score")?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,

			contentRating = ContentRating.ADULT // Set cứng
		)
	}

	/**
	 * Ghi đè `selectDate`:
	 * Dùng cho logic `loadChapters` (AJAX) của base class.
	 * `ifno.html` (khi gọi AJAX) trả về ngày tháng trong `span.chapter-release-date`
	 */
	override val selectDate = "span.chapter-release-date"
    
	/**
	 * Ghi đè `getPages`:
	 * Selector cho từng ảnh trong trang đọc (read.html)
	 * Base class dùng `div.page-break`, trang này dùng `div.item img`.
	 */
	override val selectPage = "div.reading-content div.item img"

	/**
	 * Ghi đè `getPages` để xử lý logic lấy ảnh.
	 * Vì `selectPage` của chúng ta đã trỏ thẳng đến `img`, 
     * logic `flatMap` của base class sẽ lỗi.
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		// Selector mới trỏ thẳng đến ảnh (từ read.html)
		val pageElements = doc.select(selectPage)

		return pageElements.mapNotNull { imgElement ->
			// Madara thường dùng 'src' trực tiếp, 'data-src' nếu lazyload
			val imgUrl = (imgElement.attr("data-src")?.trim() ?: imgElement.attr("src")?.trim())
				?.toRelativeUrl(domain)

			if (imgUrl.isNullOrEmpty()) {
				null
			} else {
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl,
					preview = null,
					source = source,
				)
			}
		}
	}
}
