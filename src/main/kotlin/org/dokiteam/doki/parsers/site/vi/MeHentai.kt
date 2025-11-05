package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.util.*
import java.util.concurrent.TimeUnit

@MangaSourceParser("MEHENTAI", "MeHentai", "vi", type = ContentType.HENTAI)
internal class MeHentai(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MEHENTAI, 60) {

    override val configKeyDomain = ConfigKey.Domain("mehentai.top")

    // Thêm header Referer cơ bản, trang này không yêu cầu token như LxManga
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // Dựa trên phân tích file main.html (order_by=view, order_by=update_time)
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isTagSupported = true,
            tagInclusion = MangaListFilter.TagInclusion.SINGLE, // URL chỉ hỗ trợ 1 tag: /the-loai/{key}
            isStateSupported = false, // Không thấy UI filter theo state
            tagExclusion = MangaListFilter.TagExclusion.UNSUPPORTED
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
        availableStates = EnumSet.noneOf(MangaState::class.java) // Không hỗ trợ filter state
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)

            when {
                // Ưu tiên tìm kiếm (từ search.html)
                !filter.query.isNullOrEmpty() -> {
                    append("/search")
                    append("?q=")
                    append(filter.query.urlEncoded())
                    if (page > 1) {
                        append("&page=")
                        append(page)
                    }
                    // Trang search không hỗ trợ sorting
                }
                // Filter theo thể loại (từ main.html nav)
                filter.tags.isNotEmpty() -> {
                    val tag = filter.tags.first()
                    append("/the-loai/")
                    append(tag.key)
                    if (page > 1) {
                        append("?page=")
                        append(page)
                    }
                    // Sẽ thêm sorting ở dưới
                }
                // Trang danh sách/trang chủ (từ main.html)
                else -> {
                    append("/danh-sach") // Trang danh sách chính
                    append("?page=")
                    append(page)
                }
            }

            // Thêm tham số sorting nếu không phải là trang tìm kiếm
            if (filter.query.isNullOrEmpty()) {
                append(if (url.contains("?")) "&" else "?")
                append("order_by=")
                append(
                    when (order) {
                        SortOrder.POPULARITY -> "view"
                        SortOrder.NEWEST -> "created_at" // Suy đoán
                        SortOrder.ALPHABETICAL -> "name" // Suy đoán
                        SortOrder.ALPHABETICAL_DESC -> "name" // Suy đoán
                        else -> "update_time" // Mặc định
                    }
                )
                append("&sort=")
                append(
                    when (order) {
                        SortOrder.ALPHABETICAL -> "asc"
                        else -> "desc" // Đa số các kiểu sort khác đều là desc
                    }
                )
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        // Selector chung cho trang chủ (main.html) và trang tìm kiếm (search.html)
        val containerSelector = "div#halim-advanced-widget-6-ajax-box"
        val itemSelector = "article.thumb.grid-item"

        return doc.select("$containerSelector $itemSelector").map { article ->
            val a = article.selectFirstOrThrow("a.halim-thumb")
            val href = a.attrAsRelativeUrl("href")
            val img = a.selectFirstOrThrow("figure img.lazyload")
            // Ưu tiên data-src, fallback về src
            val coverUrl = img.attrOrNull("data-src") ?: img.attr("src")

            Manga(
                id = generateUid(href),
                title = article.selectFirstOrThrow("h2.entry-title").text(),
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

        // Phân tích info.html
        val author = root.selectFirst("span.directors i.fa-solid.fa-user + span")?.text()
            ?.takeIf { it.isNotBlank() && it != "Đang Cập Nhật" } // Lọc giá trị "Đang Cập Nhật"

        val statusText = root.selectFirst("div.thong-tin span:has(i.fa-sharp.fa-solid.fa-fan) + span span")?.text()
        val state = when (statusText) {
            "Truyện Full" -> MangaState.FINISHED
            "Đang Cập Nhật" -> MangaState.ONGOING // Giả định
            else -> null
        }

        val tags = root.select("span.category:has(i.fa-solid.fa-tag) + span a").mapToSet { a ->
            MangaTag(
                key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
                // Lấy title từ text, dọn dẹp "- "
                title = a.text().removePrefix("- ").trim(),
                source = source,
            )
        }

        val description = root.selectFirst("article[id^=post-].item-content p")?.text()

        // Hàm helper để parse "X ngày trước"
        val chapterDateParser = RelativeDateParser(Locale("vi"))

        val chapters = root.select("ul#list-chap li.chapter a")
            .mapChapters(reversed = true) { i, a -> // list-chap đảo ngược (chap 5 -> 1)
                val href = a.attrAsRelativeUrl("href")
                val name = a.selectFirstOrThrow("span.chap-name").text()
                val dateText = a.selectFirst("span[style*=text-align]")?.text() // "4 ngày trước"

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    // Thử parse số từ "Chapter X", nếu thất bại thì dùng index
                    number = name.substringAfter("Chapter ").toFloatOrNull() ?: (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null, // Không có thông tin nhóm dịch
                    uploadDate = dateText?.let { chapterDateParser.parse(it) }, // Parse relative date
                    branch = null,
                    source = source,
                )
            }

        return manga.copy(
            altTitles = emptySet(), // Không tìm thấy tên khác trong info.html
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

        // Phân tích read.html
        val imageElements = doc.select("div.contentimg div.imageload img.simg")

        return imageElements.map { img ->
            // Trang này dùng nhiều server proxy.
            // Ưu tiên 'data-sv3' (i0.wp.com)
            // Fallback về 'data-sv1' (duckduckgo)
            // Fallback cuối cùng về 'src'
            val url = img.attrOrNull("data-sv3")
                ?: img.attrOrNull("data-sv1")
                ?: img.attr("src")

            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun availableTags(): Set<MangaTag> {
        // Lấy tags từ dropdown menu ở trang chủ (main.html)
        val url = "https://$domain/"
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("ul.dropdown-menu li a[href*='/the-loai/']").map { a ->
            val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
            // Lấy text, ví dụ: "- 18+", dọn dẹp thành "18+"
            val title = a.text().removePrefix("- ").trim()
            MangaTag(
                key = key,
                title = title,
                source = source,
            )
        }.toSet()
    }

    /**
     * Helper class để parse các chuỗi ngày tương đối (VD: "4 ngày trước")
     * (Không có trong file mẫu LxManga, nhưng cần thiết cho trang này
     * vì trang info không cung cấp datetime attribute)
     */
    private class RelativeDateParser(private val locale: Locale) {
        fun parse(relativeDate: String): Long {
            try {
                val now = Calendar.getInstance()
                val parts = relativeDate.lowercase(locale).split(" ")

                if (parts.size < 2) return now.timeInMillis

                val amount = parts[0].toIntOrNull() ?: return now.timeInMillis
                val unit = parts[1]

                when (unit) {
                    "phút" -> now.add(Calendar.MINUTE, -amount)
                    "giờ" -> now.add(Calendar.HOUR, -amount)
                    "ngày" -> now.add(Calendar.DAY_OF_YEAR, -amount)
                    "tuần" -> now.add(Calendar.WEEK_OF_YEAR, -amount)
                    "tháng" -> now.add(Calendar.MONTH, -amount)
                    "năm" -> now.add(Calendar.YEAR, -amount)
                    else -> return now.timeInMillis // Không parse được, trả về "bây giờ"
                }
                return now.timeInMillis
            } catch (e: Exception) {
                // Nếu lỗi, trả về thời gian hiện tại
                return Calendar.getInstance().timeInMillis
            }
        }
    }
}
