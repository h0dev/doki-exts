package org.dokiteam.doki.parsers.site.vi

import okhttp3.Headers
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THIENTHAITRUYEN", "Thiên Thai Truyện", "vi", type = ContentType.HENTAI)
internal class ThienThaiTruyen(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.THIENTHAITRUYEN, 60) {

    override val configKeyDomain = ConfigKey.Domain("thienthaitruyen1.com")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "vi-VN,vi;q=0.9")
        .add("Sec-Ch-Ua", "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"")
        .add("Sec-Ch-Ua-Mobile", "?1")
        .add("Sec-Ch-Ua-Platform", "\"Android\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Upgrade-Insecure-Requests", "1")
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
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
                // FIX: Dùng dấu phẩy ',' thay vì '_' để nối các genres
                val tagsQuery = filter.tags.joinToString(",") { it.key }
                appendParam("genres=$tagsQuery")
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        // Tìm tất cả div trong grid
        val itemElements = doc.select("div.grid > div")

        return itemElements.mapNotNull { div ->
            // Tìm thẻ a chứa ảnh (cover)
            val coverLink = div.selectFirst("a:has(img)") ?: return@mapNotNull null
            val href = coverLink.attrAsRelativeUrl("href")
            val coverUrl = coverLink.selectFirst("img")?.attr("src") ?: ""

            // Tìm title
            val container = div.parent() ?: div
            val titleNode = div.selectFirst("h3 a, div[class*='text-'] > a, span.font-medium")
            
            val title = titleNode?.text()?.trim()
                ?: coverLink.attr("title").takeIf { it.isNotEmpty() }
                ?: coverLink.selectFirst("img")?.attr("alt")?.replace("truyện tranh ", "", true)?.trim()
                ?: ""

            if (title.isBlank() || href.contains("/chuong-")) return@mapNotNull null

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
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val dateParser = SmartDateParser(Locale("vi"))

        val description = root.selectFirst("p.comic-content")?.text()?.trim()
            ?: root.selectFirst("div.detail-content p")?.text()?.trim()

        val author = root.selectFirst("div:contains(Tác giả) p.text-sm")
            ?.text()?.takeIf { it.trim() != "Đang cập nhật" }

        val statusText = root.selectFirst("div:contains(Trạng thái) p.text-sm")?.text()
        val state = when (statusText) {
            "Đang ra" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> MangaState.ONGOING
        }

        val tags = root.select("div.space-y-1 a[href*='/the-loai/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }

        val chapters = root.select("div.chapter-items")
            .mapChapters(reversed = true) { i, item ->
                val a = item.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                val name = a.selectFirst("p.text-sm")?.text()?.trim() ?: "Chapter ${i + 1}"
                val dateText = a.selectFirst("p.text-xs span")?.text()?.trim()

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = extractChapterNumber(name) ?: (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = dateParser.parse(dateText) ?: 0L,
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

        val container = doc.selectFirst("div.py-8 div.flex.flex-col.items-center") 
            ?: doc.selectFirst("div.py-8 div.w-full.mx-auto")

        val imageElements = container?.select("img") ?: return emptyList()

        return imageElements.mapNotNull { img ->
            // FIX: Ưu tiên data-src vì src chứa ảnh loading
            val url = img.attr("data-src").ifBlank { img.attr("src") }
            
            // Lọc bỏ ảnh rác
            if (url.isBlank() || 
                url.contains("thumb-loading.gif") || 
                url.contains("thienthaitruyen-truyen-tranh-hentai.png")
            ) {
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

        // Tìm tất cả label có chứa input checkbox (cách này bắt được mọi layout)
        val labels = doc.select("label:has(input[type=checkbox])")

        return labels.mapNotNull { label ->
            val input = label.selectFirst("input")
            val title = label.selectFirst("span")?.text()?.trim() ?: label.text().trim()
            val key = input?.attr("value")

            if (key != null && title.isNotEmpty()) {
                MangaTag(
                    key = key,
                    title = title,
                    source = source,
                )
            } else {
                null
            }
        }.toSet()
    }

    private fun extractChapterNumber(title: String): Float? {
        val regex = Regex("""(?:Chương|Chapter)\s+(\d+(\.\d+)?)""", RegexOption.IGNORE_CASE)
        val match = regex.find(title)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }

    private class SmartDateParser(private val locale: Locale) {
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy", locale)

        fun parse(dateString: String?): Long? {
            if (dateString.isNullOrBlank()) return null
            val cleanDate = dateString.trim()
            try {
                if (cleanDate.contains("/")) return dateFormat.parse(cleanDate)?.time
                val now = Calendar.getInstance()
                val parts = cleanDate.lowercase(locale).split(" ")
                
                if (cleanDate.contains("vừa") || cleanDate.contains("now")) return now.timeInMillis
                if (parts.size < 2) return null

                val amount = parts[0].toIntOrNull() ?: return null
                val unit = parts[1]

                when {
                    unit.contains("phút") -> now.add(Calendar.MINUTE, -amount)
                    unit.contains("giờ") -> now.add(Calendar.HOUR, -amount)
                    unit.contains("ngày") -> now.add(Calendar.DAY_OF_YEAR, -amount)
                    unit.contains("tuần") -> now.add(Calendar.WEEK_OF_YEAR, -amount)
                    unit.contains("tháng") -> now.add(Calendar.MONTH, -amount)
                    unit.contains("năm") -> now.add(Calendar.YEAR, -amount)
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
