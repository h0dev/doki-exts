package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.jsoup.nodes.Element // Bắt buộc import
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("LANHLUNGTEAM", "Lạnh Lùng Team", "vi", type = ContentType.HENTAI)
internal class LanhLungTeam(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.LANHLUNGTEAM, 60) {

    override val configKeyDomain = ConfigKey.Domain("lanhlungteam.com")

    // FIX: Sửa lại để fetchTags từ trang chủ
    private val availableTags = suspendLazy(initializer = ::fetchTagsFromMainPage)

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // update (Mặc định)
        SortOrder.POPULARITY,   // view
        SortOrder.ALPHABETICAL, // namea-z
        SortOrder.ALPHABETICAL_DESC // namez-a
    )

    // =================================================================
    // HÀM ĐÃ ĐƯỢC CHỈNH SỬA
    // =================================================================
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            // FIX: Trang /the-loai/ chỉ hỗ trợ 1 tag
            isMultipleTagsSupported = false, 
            isTagsExclusionSupported = false,
            // FIX: Tắt search-with-filters vì 2 trang này độc lập
            isSearchWithFiltersSupported = false 
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags.get(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
    )

    // =================================================================
    // HÀM ĐÃ ĐƯỢC CHỈNH SỬA (FIX LỖI SELECTOR VÀ URL)
    // =================================================================
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        
        val url: String
        val itemSelector: String
        val parserFunction: (Element) -> Manga?

        when {
            // 1. Ưu tiên LỌC THEO TAG (dùng trang /the-loai/)
            filter.tags.isNotEmpty() -> {
                // Dùng slug (chiem-huu) làm key
                url = "https://$domain/the-loai/${filter.tags.first().key}?page=$page"
                // Trang thể loại dùng selector 'div.comic-item' (giống trang chủ)
                itemSelector = "div.comic-item"
                parserFunction = ::parseComicItem
            }

            // 2. TÌM KIẾM, SẮP XẾP, LỌC STATUS (dùng trang /tim-kiem-nang-cao/)
            !filter.query.isNullOrEmpty() || 
            filter.states.isNotEmpty() ||
            order != SortOrder.UPDATED // Bất kỳ sort nào khác mặc định
            -> {
                url = buildString {
                    append("https://$domain/tim-kiem-nang-cao")
                    appendParam("page=$page")
                    
                    // Sắp xếp
                    appendParam(
                        "order=" + when (order) {
                            SortOrder.POPULARITY -> "view"
                            SortOrder.ALPHABETICAL -> "namea-z"
                            SortOrder.ALPHABETICAL_DESC -> "namez-a"
                            else -> "update"
                        }
                    )
                    // Từ khóa
                    if (!filter.query.isNullOrEmpty()) {
                        appendParam("keyword=${filter.query.urlEncoded()}")
                    }
                    // Trạng thái
                    if (filter.states.isNotEmpty()) {
                        val statusValue = when (filter.states.first()) {
                            MangaState.ONGOING -> "1"
                            MangaState.FINISHED -> "2"
                            else -> ""
                        }
                        if (statusValue.isNotEmpty()) {
                            appendParam("status=$statusValue")
                        }
                    }
                    // Lưu ý: Không gửi 'genre[]' ở đây theo yêu cầu của bạn
                }
                // Trang tìm kiếm dùng selector 'div.page-item'
                itemSelector = "div.page-item"
                parserFunction = ::parsePageItem
            }

            // 3. MẶC ĐỊNH (Trang chủ, sort=update, page=x)
            else -> {
                url = "https://$domain/?page=$page"
                itemSelector = "div.comic-item"
                parserFunction = ::parseComicItem
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(itemSelector).mapNotNull(parserFunction)
    }

    /**
     * Helper parse cho item ở TRANG CHỦ & THỂ LOẠI (div.comic-item)
     */
    private fun parseComicItem(element: Element): Manga? {
        val a = element.selectFirst("h3.comic-title > a") ?: return null
        val href = a.attrAsRelativeUrl("href")
        val img = element.selectFirst("div.comic-image img")
        val coverUrlLocal = img?.attrOrNull("data-src") ?: img?.attr("src")

        return Manga(
            id = generateUid(href),
            title = a.text(),
            altTitles = emptySet(),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = coverUrlLocal?.toAbsoluteUrl(domain) ?: "",
            tags = setOf(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    /**
     * Helper parse cho item ở TRANG TÌM KIẾM (div.page-item)
     */
    private fun parsePageItem(element: Element): Manga? {
        val a = element.selectFirst("div.comic-title > a") ?: return null // Khác trang chủ
        val href = a.attrAsRelativeUrl("href")
        val img = element.selectFirst("div.page-image img") // Khác trang chủ
        val coverUrlLocal = img?.attrOrNull("data-src") ?: img?.attr("src")

        return Manga(
            id = generateUid(href),
            title = a.text(),
            altTitles = emptySet(),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = coverUrlLocal?.toAbsoluteUrl(domain) ?: "",
            tags = setOf(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }
    // =================================================================
    // KẾT THÚC HÀM CHỈNH SỬA
    // =================================================================

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val chapterDateParser = RelativeDateParser(Locale("vi"))

        val altTitles = root.select("div.other-name > p.other-name-item").mapToSet { it.text() }
        val author = root.selectFirst("div.comic-author p.author-name-item > a")?.text()

        val statusText = root.selectFirst("div.comic-status p.status-item")?.text()
        val state = when (statusText) {
            "Đang tiến hành" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> null
        }

        // Tags (lấy từ trang chi tiết)
        val tags = root.select("div.comic-type a.type-item").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'), // /the-loai/action -> action
                title = a.text().trim(),
                source = source,
            )
        }

        val description = root.selectFirst("div.comic-description > p")?.text()

        val chapters = root.select("ul#list-chapter-dt li.chapter-item")
            .mapChapters(reversed = true) { i, li ->
                val a = li.selectFirstOrThrow("a.chapter-link")
                val href = a.attrAsRelativeUrl("href")
                val name = a.attr("title").ifEmpty { a.text() }
                val dateText = li.selectFirst("span.chapter-time")?.text()

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = name.substringAfter("Chương ").toFloatOrNull() ?: (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null, 
                    uploadDate = chapterDateParser.parse(dateText) ?: 0L,
                    branch = null,
                    source = source,
                )
            }

        return manga.copy(
            altTitles = altTitles,
            state = state,
            tags = tags,
            authors = setOfNotNull(author),
            description = description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val imageElements = doc.select("div.reading-detail div.page-chapter > img")

        return imageElements.map { img ->
            val url = img.attr("src")
            MangaPage(
                id = generateUid(url),
                url = url, 
                preview = null,
                source = source,
            )
        }
    }

    // =================================================================
    // HÀM ĐÃ ĐƯỢC CHỈNH SỬA (FIX LỖI SELECTOR)
    // =================================================================
    /**
     * Lấy danh sách tag từ TRANG CHỦ (div.tags) theo yêu cầu
     */
    private suspend fun fetchTagsFromMainPage(): Set<MangaTag> {
        val url = "https://$domain/"
        val doc = webClient.httpGet(url).parseHtml()

        // FIX 2: Dùng selector 'div.tags a' từ 'lanhlungteam.com.html'
        return doc.select("div.tags a[href*='/the-loai/']").mapNotNullToSet { a ->
            val title = a.text()
            val key = a.attr("href").substringAfterLast('/') // 'chiem-huu'

            if (key.isNotEmpty() && title.isNotEmpty()) {
                MangaTag(
                    key = key, // Key là slug (vd: "chiem-huu")
                    title = title.trim(),
                    source = source,
                )
            } else {
                null
            }
        }
    }
    // =================================================================
    // KẾT THÚC HÀM CHỈNH SỬA
    // =================================================================

    /**
     * Helper class để parse các chuỗi ngày tương đối (VD: "13 giờ trước")
     */
    private class RelativeDateParser(private val locale: Locale) {
        fun parse(relativeDate: String?): Long? {
            if (relativeDate.isNullOrBlank()) return null
            
            try {
                val now = Calendar.getInstance()
                val parts = relativeDate.lowercase(locale).split(" ")
                if (parts.size < 2) return null
                val amount = parts[0].toIntOrNull() ?: return null
                val unit = parts[1] 

                when (unit) {
                    "giây" -> now.add(Calendar.SECOND, -amount)
                    "phút" -> now.add(Calendar.MINUTE, -amount)
                    "giờ" -> now.add(Calendar.HOUR, -amount)
                    "ngày" -> now.add(Calendar.DAY_OF_YEAR, -amount)
                    "tuần" -> now.add(Calendar.WEEK_OF_YEAR, -amount)
                    "tháng" -> now.add(Calendar.MONTH, -amount)
                    "năm" -> now.add(Calendar.YEAR, -amount)
                    else -> return null
                }
                return now.timeInMillis
            } catch (e: Exception) {
                return null
            }
        }
    }

    /**
     * Helper function để thêm param vào URL một cách chính xác (xử lý ? và &)
     */
    private fun StringBuilder.appendParam(param: String) {
        append(if (contains("?")) "&" else "?")
        append(param)
    }
}
