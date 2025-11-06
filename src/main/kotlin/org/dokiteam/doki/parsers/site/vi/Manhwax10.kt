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

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY, // Mặc định
        SortOrder.UPDATED,    // /truyen-moi-cap-nhat
        SortOrder.NEWEST      // Map sang UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
        availableStates = EnumSet.noneOf(MangaState::class.java)
    )

    // =================================================================
    // HÀM ĐÃ ĐƯỢC CHỈNH SỬA
    // =================================================================
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
                // 2. Lọc theo thể loại
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

        val itemSelector = "div.w-full.grid > div.relative.text-left.rounded-xl:has(a[href^=/truyen/])"

        return doc.select(itemSelector).map { div ->
            val a = div.selectFirstOrThrow("a[href^=/truyen/]")
            val href = a.attrAsRelativeUrl("href")
            
            val img = a.selectFirst("img")
            val coverUrl = img?.attrOrNull("data-src") ?: img?.attr("src")

            val title = div.selectFirstOrThrow("[class*=line-clamp-2]").text()

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                // *** FIX (Lỗi 404): Chuyển URL tương đối sang tuyệt đối ***
                coverUrl = coverUrl.toAbsoluteUrl(domain),
                tags = setOf(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }
    // =================================================================
    // KẾT THÚC HÀM CHỈNH SỬA
    // =================================================================

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val chapterDateParser = RelativeDateParser(Locale("vi"))

        val author = root.selectFirst("h3.text-sm:contains(Tác giả:)")
            ?.text()?.substringAfter(":")?.trim()
            ?.takeIf { it.isNotBlank() && it != "Đang Cập Nhật" }

        val tags = root.select("h3.text-sm:contains(Thể Loại:) a[href^='/the-loai/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").split("/")[2], // /the-loai/drama/1 -> drama
                title = a.text(),
                source = source,
            )
        }

        val description = root.selectFirst("p.line-clamp-4.pt-2")?.text()

        val chapters = root.select("div#chapter-list a").mapChapters(reversed = false) { i, a ->
            val href = a.attrAsRelativeUrl("href")
            val name = a.selectFirst("h3 div.inline-block")?.text().orEmpty()
            val dateText = a.selectFirst("h3 div.float-right")?.text()

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
        
        val state = if (chapters.firstOrNull()?.title?.contains("END", ignoreCase = true) == true) {
            MangaState.FINISHED
        } else {
            MangaState.ONGOING
        }

        return manga.copy(
            altTitles = emptySet(),
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

        val imageElements = doc.select("div.w-full.flex.flex-col.items-center div[class*=max-w] img")

        return imageElements.mapNotNull { img ->
            val url = img.attrOrNull("data-src") ?: img.attr("src")

            if (url.endsWith("loading.webp") || url.contains("/page_logo.png")) {
                null
            } else {
                MangaPage(
                    id = generateUid(url),
                    url = url.toAbsoluteUrl(domain), 
                    preview = null,
                    source = source,
                )
            }
        }
    }

    private suspend fun availableTags(): Set<MangaTag> {
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
     */
    private class RelativeDateParser(private val locale: Locale) {
        fun parse(relativeDate: String?): Long? {
            if (relativeDate.isNullOrBlank()) return null
            
            try {
                val now = Calendar.getInstance()
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
