package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("TRUYENHENTAIZ", "TruyenHentaiZ", "vi", type = ContentType.HENTAI)
internal class TruyenHentaiZ(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.TRUYENHENTAIZ, 60) {

    override val configKeyDomain = ConfigKey.Domain("truyenhentaiz.net")

    // Sử dụng suspendLazy để cache lại danh sách tag
    private val availableTags = suspendLazy(initializer = ::fetchTags)

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // Dựa trên UI (Mới cập nhật, Phổ biến (Xem nhiều nhất))
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY
    )

    // Sử dụng API Doki cũ (giống DamCoNuong)
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false, // Trang này không hỗ trợ lọc nhiều tag, chỉ 1 tag 1 lần
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags.get(), // Lấy tag từ lazy loader
        availableStates = EnumSet.noneOf(MangaState::class.java) // Không hỗ trợ lọc theo trạng thái
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)

            when {
                // 1. Ưu tiên tìm kiếm (từ truyenhentaiz.net__s_g.html)
                !filter.query.isNullOrEmpty() -> {
                    append("/page/$page?s=${filter.query.urlEncoded()}")
                }
                
                // 2. Lọc theo thể loại (chỉ hỗ trợ 1 tag)
                filter.tags.isNotEmpty() -> {
                    append("/category/${filter.tags.first().key}/page/$page")
                }

                // 3. Sắp xếp
                order == SortOrder.POPULARITY -> {
                    append("/xem-nhieu-nhat/page/$page")
                }

                // 4. Mặc định (Mới cập nhật - từ truyenhentaiz.net.html)
                else -> {
                    append("/moi-cap-nhat/page/$page")
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        // Selector chung cho trang chủ, tìm kiếm, thể loại
        val itemSelector = "div.col[class*=item-]"

        return doc.select(itemSelector).map { element ->
            val a = element.selectFirstOrThrow("a[href*='.html']")
            val href = a.attrAsRelativeUrl("href")
            
            // Ưu tiên data-src (lazy load), fallback về src
            val img = a.selectFirstOrThrow("img.card-img-top")
            val coverUrl = img.attrOrNull("data-src") ?: img.attr("src")

            Manga(
                id = generateUid(href),
                title = a.attr("title").ifEmpty { a.selectFirst("h2.card-manga-title")?.text().orEmpty() },
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = coverUrl.toAbsoluteUrl(domain), // Ảnh đã là URL tuyệt đối
                tags = setOf(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val chapterDateParser = RelativeDateParser(Locale("vi")) // Dùng lại parser từ ThienThaiTruyen

        // Tên khác
        val altTitles = root.selectFirst("p.other-name-container span.other-name")
            ?.text()?.split("/")?.map { it.trim() }?.toSet() ?: emptySet()

        // Tác giả / Doujinshi
        val author = root.selectFirst("div.manga-info span:has(i.bi-journal-bookmark-fill) a")?.text()

        // Trạng thái
        val statusText = root.selectFirst("div.manga-info span:has(i.bi-arrow-clockwise) strong")?.text()
        val state = when (statusText) {
            "FULL" -> MangaState.FINISHED
            "Đang cập nhật" -> MangaState.ONGOING // Giả định
            else -> null
        }

        // Thể loại
        val tags = root.select("div.categories a[href*='/category/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }

        // Mô tả
        val description = root.selectFirst("p.mt-2.card-text.desc")?.text()

        // Danh sách chương
        val chapters = root.select("ul.list-group.list-chapters li.list-group-item")
            .mapChapters(reversed = true) { i, li -> // List trên web là từ mới -> cũ
                val a = li.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                val name = a.selectFirst("span.fw-bold")?.text().orEmpty()
                val dateText = li.selectFirst("em")?.text() // "23 giờ trước"

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = name.substringAfter("Chapter ").toFloatOrNull() ?: (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null, // Không thấy thông tin scanlator
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

        // Selector dựa trên ...chapter-1.html.html
        val imageElements = doc.select("div#chapter-content img[decoding=async]")

        return imageElements.map { img ->
            val url = img.attr("src")
            MangaPage(
                id = generateUid(url),
                url = url, // URL đã là tuyệt đối (từ cdn2.tymanga.com)
                preview = null,
                source = source,
            )
        }
    }

    /**
     * Lấy danh sách tag từ sidebar trang chủ
     */
    private suspend fun fetchTags(): Set<MangaTag> {
        val url = "https://$domain/"
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("ul#genres-nav li a[href*='/category/']").mapNotNullToSet { a ->
            val key = a.attr("href").substringAfterLast('/')
            val title = a.selectFirst("span")?.text()
            if (key.isNotEmpty() && title != null) {
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
     * Helper class để parse các chuỗi ngày tương đối (VD: "4 ngày trước")
     */
    private class RelativeDateParser(private val locale: Locale) {
        fun parse(relativeDate: String?): Long? {
            if (relativeDate.isNullOrBlank()) return null
            
            try {
                val now = Calendar.getInstance()
                // Trang này dùng "trước" (vd: "22 giờ trước")
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
}
