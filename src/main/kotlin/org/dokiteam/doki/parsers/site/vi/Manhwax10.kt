package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.util.*

@MangaSourceParser("MANHWAX10", "ManhwaX10", "vi", type = ContentType.HENTAI)
internal class ManhwaX10(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MANHWAX10, 60) {

    override val configKeyDomain = ConfigKey.Domain("manhwax10.net")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // Trang web sử dụng path-based sorting, chủ yếu là 'Mới Cập Nhật' và 'Phổ Biến' (mặc định)
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY, // Mặc định
        SortOrder.UPDATED,    // /truyen-moi-cap-nhat
        SortOrder.NEWEST      // Map sang UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            // isTagSupported sẽ được Doki tự động bật khi getFilterOptions() trả về 'availableTags'
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
                // 1. Ưu tiên tìm kiếm (từ search.html)
                !filter.query.isNullOrEmpty() -> {
                    append("/tim-kiem?key=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append(page)
                }
                // 2. Lọc theo thể loại (từ header/info.html)
                filter.tags.isNotEmpty() -> {
                    append("/the-loai/")
                    append(filter.tags.first().key) // /the-loai/{key}/{page}
                    append("/")
                    append(page)
                }
                // 3. Sắp xếp (từ nav)
                order == SortOrder.UPDATED || order == SortOrder.NEWEST -> {
                    append("/truyen-moi-cap-nhat/") // /truyen-moi-cap-nhat/{page}
                    append(page)
                }
                // 4. Mặc định/Phổ biến (từ nav 'Tất Cả')
                else -> {
                    append("/danh-sach-truyen/") // /danh-sach-truyen/{page}
                    append(page)
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        // Selector này khớp với grid item trên cả trang search, trang thể loại, và trang "Mới Cập Nhật"
        val itemSelector = "div.w-full.grid div.relative.text-left.rounded-xl"

        return doc.select(itemSelector).map { div ->
            // Thẻ <a> đầu tiên chứa link và ảnh bìa
            val a = div.selectFirst("a[href^=/truyen/]")
                ?: div.parseFailed("Không thể tìm thấy link manga")
            
            val href = a.attrAsRelativeUrl("href")
            val img = a.selectFirst("img")
            
            // Ưu tiên data-src (lazyload)
            val coverUrl = img?.attrOrNull("data-src") ?: img?.attr("src")

            // Tiêu đề nằm ở thẻ <a> thứ hai, bên trong 1 div/h3
            val titleElement = div.selectFirst("a[href^=/truyen/] [class*=line-clamp-2]")
            val title = titleElement?.text() ?: "N/A"

            Manga(
                id = generateUid(href),
                title = title,
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
        
        // Dùng helper parse ngày tương đối ("3 giờ trc", "2 ngày trc")
        val chapterDateParser = RelativeDateParser(Locale("vi"))

        // Parse Tác giả, lọc "Đang Cập Nhật"
        val author = root.selectFirst("h3.text-sm:contains(Tác giả:)")
            ?.text()?.substringAfter(":")?.trim()
            ?.takeIf { it.isNotBlank() && it != "Đang Cập Nhật" }

        // Parse Thể loại
        val tags = root.select("h3.text-sm:contains(Thể Loại:) a[href^='/the-loai/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").split("/")[2], // /the-loai/drama/1 -> drama
                title = a.text(),
                source = source,
            )
        }

        // Parse Mô tả
        val description = root.selectFirst("p.line-clamp-4.pt-2")?.text()

        // Parse danh sách chương (từ info.html)
        val chapters = root.select("div#chapter-list a").mapChapters(reversed = false) { i, a ->
            val href = a.attrAsRelativeUrl("href")
            val name = a.selectFirst("h3 div.inline-block")?.text().orEmpty()
            val dateText = a.selectFirst("h3 div.float-right")?.text()

            MangaChapter(
                id = generateUid(href),
                title = name,
                // Thử parse số từ "Chương X", nếu thất bại dùng index
                number = name.substringAfter("Chương ").toFloatOrNull() ?: (i + 1).toFloat(),
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = chapterDateParser.parse(dateText) ?: 0L, // 0L là "không rõ ngày"
                branch = null,
                source = source,
            )
        }
        
        // Suy đoán trạng thái: Nếu chương đầu tiên (mới nhất) có chữ "END" -> FINISHED
        val state = if (chapters.firstOrNull()?.title?.contains("END", ignoreCase = true) == true) {
            MangaState.FINISHED
        } else {
            MangaState.ONGOING // Ngược lại thì là ONGOING
        }

        return manga.copy(
            altTitles = emptySet(), // Không thấy tên khác
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

        // Chọn tất cả ảnh trong khu vực đọc (từ read.html)
        val imageElements = doc.select("div.w-full.flex.flex-col.items-center div[class*=max-w] img")

        return imageElements.mapNotNull { img ->
            // Ưu tiên data-src (lazyload)
            val url = img.attrOrNull("data-src") ?: img.attr("src")

            // Lọc các ảnh placeholder của trang
            if (url.endsWith("loading.webp") || url.contains("/page_logo.png")) {
                null
            } else {
                MangaPage(
                    id = generateUid(url),
                    url = url.toAbsoluteUrl(domain), // Các URL ảnh là relative (vd: /imgs/...)
                    preview = null,
                    source = source,
                )
            }
        }
    }

    private suspend fun availableTags(): Set<MangaTag> {
        // Lấy tags từ dropdown menu ở trang chủ (main.html)
        val url = "https://$domain/"
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("div#h-genre ul a[href^='/the-loai/']").map { a ->
            MangaTag(
                key = a.attr("href").split("/")[2], // /the-loai/18/1 -> 18
                title = a.text(),
                source = source,
            )
        }.toSet()
    }

    /**
     * Helper class để parse các chuỗi ngày tương đối (VD: "3 giờ trc", "2 ngày trc")
     * (Tái sử dụng từ MeHentai parser)
     */
    private class RelativeDateParser(private val locale: Locale) {
        fun parse(relativeDate: String?): Long? {
            if (relativeDate.isNullOrBlank()) return null
            
            try {
                val now = Calendar.getInstance()
                // Chuẩn hóa "tháng trc", "ngày trc" -> "tháng trước", "ngày trước"
                val normalizedDate = relativeDate.replace(" trc", " trước")
                val parts = normalizedDate.lowercase(locale).split(" ")

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
}
