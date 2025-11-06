package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.util.*

@MangaSourceParser("THIENTHAITRUYEN", "Thiên Thai Truyện", "vi", type = ContentType.HENTAI)
internal class ThienThaiTruyen(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.THIENTHAITRUYEN, 60) {

    override val configKeyDomain = ConfigKey.Domain("thienthaitruyen.com")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // Dựa trên phân tích search.html (order-filter)
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // latest
        SortOrder.POPULARITY,   // rating
        SortOrder.ALPHABETICAL, // name_asc
        SortOrder.ALPHABETICAL_DESC // name_desc
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED) // Dựa trên status-filter
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            // Trang tìm kiếm/lọc là endpoint chính
            append("https://$domain/tim-kiem-nang-cao")

            // 1. Query
            if (!filter.query.isNullOrEmpty()) {
                appendParam("name=${filter.query.urlEncoded()}")
            }

            // 2. Page
            appendParam("page=$page")

            // 3. Sort Order
            val sortValue = when (order) {
                SortOrder.POPULARITY -> "rating"
                SortOrder.ALPHABETICAL -> "name_asc"
                SortOrder.ALPHABETICAL_DESC -> "name_desc"
                else -> "latest" // Default là UPDATED
            }
            appendParam("sort=$sortValue")

            // 4. Status
            if (filter.states.isNotEmpty()) {
                val statusValue = when (filter.states.first()) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    else -> ""
                }
                if (statusValue.isNotEmpty()) {
                    appendParam("status=$statusValue")
                }
            }

            // 5. Tags (Genres)
            filter.tags.forEach {
                appendParam("genres[]=${it.key}")
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        // Selector dựa trên search.html
        val itemSelector = "div.grid.grid-cols-3.md\\:grid-cols-5 > a[href^='/truyen-tranh/']"

        return doc.select(itemSelector).map { a ->
            val href = a.attrAsRelativeUrl("href")
            val title = a.selectFirst("span.line-clamp-2")?.text().orEmpty()
            
            // Ảnh đã là URL tuyệt đối từ domain wasawow.com
            val coverUrl = a.selectFirst("img")?.attr("src") ?: ""

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = coverUrl, // Đã tuyệt đối
                tags = setOf(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val chapterDateParser = RelativeDateParser(Locale("vi")) // Dùng lại parser từ MeHentai

        // Tác giả
        val author = root.selectFirst("div.grid div:contains(Tác giả) p.text-sm")
            ?.text()?.takeIf { it.trim() != "Đang cập nhật" }

        // Nhóm dịch
        val scanlator = root.selectFirst("div.grid div:contains(Nhóm dịch) a")?.text()

        // Trạng thái
        val statusText = root.selectFirst("div.bg-[#343434] div:contains(Trạng thái) p.text-sm")?.text()
        val state = when (statusText) {
            "Đang ra" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> null
        }

        // Thể loại
        val tags = root.select("div.space-y-1.pt-4 a[href*='/the-loai/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }

        // Mô tả
        val description = root.selectFirst("p.comic-content.desk")?.text()

        // Danh sách chương
        val chapters = root.select("div.chapter-items")
            .mapChapters(reversed = true) { i, div -> // Danh sách chương trên web là từ mới đến cũ
                val a = div.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                val name = a.selectFirst("p.text-sm")?.text().orEmpty()
                val dateText = a.selectFirst("p.text-xs > span")?.text() // "22 giờ trước"

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = name.substringAfter("Chương ").toFloatOrNull() ?: (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = scanlator, // Lấy từ thông tin chung của truyện
                    uploadDate = chapterDateParser.parse(dateText) ?: 0L,
                    branch = null,
                    source = source,
                )
            }

        return manga.copy(
            altTitles = emptySet(), // Không tìm thấy tên khác rõ ràng
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

        // Selector dựa trên read.html
        val imageElements = doc.select("div.py-8 div[class*='mx-auto center'] > img")

        return imageElements.mapNotNull { img ->
            val url = img.attr("src")

            // Lọc ảnh banner/placeholder
            if (url.contains("banner") || url.contains("thienthaitruyen-truyen-tranh-hentai.png")) {
                null
            } else {
                MangaPage(
                    id = generateUid(url),
                    url = url, // URL đã là tuyệt đối (từ wasawow.com)
                    preview = null,
                    source = source,
                )
            }
        }
    }

    private suspend fun availableTags(): Set<MangaTag> {
        // Lấy tags từ dropdown menu ở trang chủ (main.html)
        val url = "https://$domain/official"
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("div.genres-dropdown-content a[href*='/the-loai/']").map { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }.toSet()
    }

    /**
     * Helper class để parse các chuỗi ngày tương đối (VD: "4 ngày trước")
     * (Tái sử dụng từ MeHentai parser)
     */
    private class RelativeDateParser(private val locale: Locale) {
        fun parse(relativeDate: String?): Long? {
            if (relativeDate.isNullOrBlank()) return null
            
            try {
                val now = Calendar.getInstance()
                // Trang này dùng "trước" (vd: "22 giờ trước"), không cần chuẩn hóa "trc"
                val parts = relativeDate.lowercase(locale).split(" ")

                if (parts.size < 2) return null

                val amount = parts[0].toIntOrNull() ?: return null
                val unit = parts[1]

                when (unit) {
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
