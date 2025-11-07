package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.suspendlazy.suspendLazy // Đảm bảo đã import
import java.util.*

@MangaSourceParser("LANHLUNGTEAM", "Lạnh Lùng Team", "vi", type = ContentType.HENTAI)
internal class LanhLungTeam(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.LANHLUNGTEAM, 60) {

    override val configKeyDomain = ConfigKey.Domain("lanhlungteam.com")

    // Sử dụng suspendLazy để cache lại danh sách tag
    private val availableTags = suspendLazy(initializer = ::fetchTags)

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // Dựa trên 'tim-kiem-nang-cao...html'
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // update (Mặc định)
        SortOrder.POPULARITY,   // view
        SortOrder.ALPHABETICAL, // namea-z
        SortOrder.ALPHABETICAL_DESC // namez-a
    )

    // Dùng API Doki cũ (giống DamCoNuong)
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true, // Trang này hỗ trợ 'genre[]'
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags.get(),
        // Trang tìm kiếm hỗ trợ lọc status: 1 (Ongoing), 2 (Finished)
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            // Endpoint chính cho cả tìm kiếm, lọc, và trang chủ
            append("https://$domain/tim-kiem-nang-cao")

            // 1. Page
            appendParam("page=$page")

            // 2. Sort Order
            appendParam(
                "order=" + when (order) {
                    SortOrder.POPULARITY -> "view"
                    SortOrder.ALPHABETICAL -> "namea-z"
                    SortOrder.ALPHABETICAL_DESC -> "namez-a"
                    else -> "update" // Mặc định là UPDATED
                }
            )

            // 3. Query (Keyword)
            if (!filter.query.isNullOrEmpty()) {
                appendParam("keyword=${filter.query.urlEncoded()}")
            }

            // 4. Status
            if (filter.states.isNotEmpty()) {
                // Chỉ hỗ trợ lọc 1 status 1 lần
                val statusValue = when (filter.states.first()) {
                    MangaState.ONGOING -> "1"
                    MangaState.FINISHED -> "2"
                    else -> ""
                }
                if (statusValue.isNotEmpty()) {
                    appendParam("status=$statusValue")
                }
            }

            // 5. Tags (Genres) - Hỗ trợ nhiều tag
            filter.tags.forEach {
                appendParam("genre[]=${it.key}")
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        // *** FIX 1 (List truyện): Dùng selector 'div.comic-item' ***
        // (Selector này chung cho cả main.html và tim-kiem-nang-cao.html)
        val itemSelector = "div.comic-item"

        return doc.select(itemSelector).mapNotNull { element ->
            // Link và tiêu đề nằm trong <h3>
            val a = element.selectFirst("h3.comic-title > a")
            if (a == null) {
                // Bỏ qua item không hợp lệ (ví dụ: quảng cáo)
                return@mapNotNull null
            }
            
            val href = a.attrAsRelativeUrl("href")
            val img = element.selectFirst("div.comic-image img")
            
            // Ưu tiên data-src (lazy load), fallback về src
            val coverUrlLocal = img?.attrOrNull("data-src") ?: img?.attr("src")

            Manga(
                id = generateUid(href),
                title = a.text(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                // Xử lý null-safety cho coverUrl
                coverUrl = coverUrlLocal?.toAbsoluteUrl(domain) ?: "", 
                tags = setOf(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val chapterDateParser = RelativeDateParser(Locale("vi")) // Tái sử dụng helper

        // Tên khác
        val altTitles = root.select("div.other-name > p.other-name-item").mapToSet { it.text() }

        // Tác giả
        val author = root.selectFirst("div.comic-author p.author-name-item > a")?.text()

        // Trạng thái
        val statusText = root.selectFirst("div.comic-status p.status-item")?.text()
        val state = when (statusText) {
            "Đang tiến hành" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> null
        }

        // Thể loại
        val tags = root.select("div.comic-type a.type-item").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'), // /the-loai/action -> action
                title = a.text().trim(),
                source = source,
            )
        }

        // Mô tả
        val description = root.selectFirst("div.comic-description > p")?.text()

        // Danh sách chương
        val chapters = root.select("ul#list-chapter-dt li.chapter-item")
            .mapChapters(reversed = true) { i, li -> // List trên web là từ mới -> cũ
                val a = li.selectFirstOrThrow("a.chapter-link")
                val href = a.attrAsRelativeUrl("href")
                val name = a.attr("title").ifEmpty { a.text() } // Ưu tiên title
                val dateText = li.selectFirst("span.chapter-time")?.text() // "13 giờ trước"

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

        // Selector dựa trên '...chuong-0.html'
        val imageElements = doc.select("div.reading-detail div.page-chapter > img")

        return imageElements.map { img ->
            val url = img.attr("src")
            MangaPage(
                id = generateUid(url),
                url = url, // URL đã là tuyệt đối (từ i.manhwax10.net)
                preview = null,
                source = source,
            )
        }
    }

    /**
     * Lấy danh sách tag từ trang tìm kiếm nâng cao
     */
    private suspend fun fetchTags(): Set<MangaTag> {
        // *** FIX 2 (Tag): Tải trang tim-kiem-nang-cao ***
        val url = "https://$domain/tim-kiem-nang-cao"
        val doc = webClient.httpGet(url).parseHtml()

        // *** FIX 2 (Tag): Dùng selector chính xác ***
        return doc.select("div.comic-filter-item-wrapper label.comic-filter-item").mapNotNullToSet { label ->
            val input = label.selectFirst("input[name='genre[]']")
            val title = label.selectFirst("span")?.text()
            val key = input?.attr("value") // e.g., "2"

            if (key != null && title != null) {
                MangaTag(
                    key = key,
                    title = title.trim(),
                    source = source,
                )
            } else {
                null
            }
        }
    }

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
                val unit = parts[1] // "giờ", "ngày", "phút"...

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
