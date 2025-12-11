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

    // UPDATE: Domain cập nhật theo curl/html
    override val configKeyDomain = ConfigKey.Domain("thienthaitruyen1.com")

    // UPDATE: Headers đầy đủ giả lập Chrome trên Android
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Authority", domain)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "vi-VN,vi;q=0.9")
        .add("Cache-Control", "max-age=0")
        .add("Sec-Ch-Ua", "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"")
        .add("Sec-Ch-Ua-Mobile", "?1")
        .add("Sec-Ch-Ua-Platform", "\"Android\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
        .add("Referer", "https://$domain/tim-kiem-nang-cao")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // latest
        SortOrder.POPULARITY,   // rating
        SortOrder.ALPHABETICAL, // name_asc
        SortOrder.ALPHABETICAL_DESC // name_desc
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true, 
            isTagsExclusionSupported = false 
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
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
                else -> "latest"
            }
            appendParam("sort=$sortValue")

            // 4. Status
            val statusValue = if (filter.states.isNotEmpty()) {
                when (filter.states.first()) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    else -> "all"
                }
            } else {
                "all"
            }
            appendParam("status=$statusValue")

            // 5. Tags (Genres)
            if (filter.tags.isNotEmpty()) {
                // HTML form dùng name="genres[]", thông thường server PHP/Laravel sẽ nhận list
                // Cách appendParam ở đây có thể cần điều chỉnh tùy backend, 
                // nhưng based on code cũ & form, ta lặp qua từng tag
                filter.tags.forEach { tag ->
                    appendParam("genres[]=${tag.key}")
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        
        // UPDATE: Selector chính xác theo HTML mới
        // Container: div.grid.grid-cols-3.md:grid-cols-5
        // Item: thẻ <a> trực tiếp bên trong
        val itemSelector = "div.grid.grid-cols-3.md\\:grid-cols-5 > a"

        return doc.select(itemSelector).map { a ->
            val href = a.attrAsRelativeUrl("href")
            
            // Title nằm trong span.line-clamp-2
            val title = a.selectFirst("span.line-clamp-2")?.text()?.trim().orEmpty()
            
            // Cover nằm trong img, lấy src trực tiếp (HTML provided có src thật, không dùng data-src)
            val coverElement = a.selectFirst("img")
            val coverUrl = coverElement?.attr("src") ?: ""

            // Lấy chapter mới nhất để hiển thị thêm (optional)
            val latestChapter = a.selectFirst("span.text-[#999]")?.text()

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = coverUrl, 
                tags = setOf(),
                state = null, // List page không hiển thị rõ state trừ khi parse cái badge "Đang ra"
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val chapterDateParser = RelativeDateParser(Locale("vi"))

        val author = root.selectFirst("div.grid div:contains(Tác giả) p.text-sm")
            ?.text()?.takeIf { it.trim() != "Đang cập nhật" }

        val scanlator = root.selectFirst("div.grid div:contains(Nhóm dịch) a")?.text()

        val statusText = root.selectFirst("div.bg-[#343434] div:contains(Trạng thái) p.text-sm")?.text()
        val state = when (statusText) {
            "Đang ra" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> null
        }

        val tags = root.select("div.space-y-1.pt-4 a[href*='/the-loai/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }

        val description = root.selectFirst("p.comic-content.desk")?.text()

        val chapters = root.select("div.chapter-items")
            .mapChapters(reversed = true) { i, div ->
                val a = div.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                val name = a.selectFirst("p.text-sm")?.text().orEmpty()
                val dateText = a.selectFirst("p.text-xs > span")?.text()

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = name.substringAfter("Chương ").toFloatOrNull() ?: (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = scanlator,
                    uploadDate = chapterDateParser.parse(dateText) ?: 0L,
                    branch = null,
                    source = source,
                )
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

        val imageElements = doc.select("div.py-8 div[class*='mx-auto center'] > img")

        return imageElements.mapNotNull { img ->
            val url = img.attr("src")
            
            if (url.contains("banner") || url.contains("thienthaitruyen-truyen-tranh-hentai.png")) {
                null
            } else {
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }
    }

    private suspend fun availableTags(): Set<MangaTag> {
        val url = "https://$domain/tim-kiem-nang-cao"
        val doc = webClient.httpGet(url).parseHtml()

        // UPDATE: Selector cho filter dropdown genres
        val genreContainer = doc.selectFirst("#genres-filter div.filter-dropdown-container")
            ?: return emptySet()

        return genreContainer.select("label").mapNotNull { label ->
            val input = label.selectFirst("input[type=checkbox]")
            val title = label.selectFirst("span")?.text()
            val key = input?.attr("value") // e.g., "adult"

            if (key != null && title != null) {
                MangaTag(
                    key = key,
                    title = title.trim(),
                    source = source,
                )
            } else {
                null
            }
        }.toSet()
    }

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

    private fun StringBuilder.appendParam(param: String) {
        append(if (contains("?")) "&" else "?")
        append(param)
    }
}
