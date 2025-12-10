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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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

            // 5. Tags
            if (filter.tags.isNotEmpty()) {
                val tagsQuery = filter.tags.joinToString("_") { it.key }
                appendParam("genres=$tagsQuery")
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        // Selector dựa trên file: thienthaitruyen1.com_tim-kiem-nang-cao.html
        // Grid container: <div class="grid grid-cols-2 md:grid-cols-4 ...">
        val itemSelector = "div.grid.grid-cols-2.md\\:grid-cols-4 > div"

        return doc.select(itemSelector).map { div ->
            // Tìm thẻ a chứa link truyện (thường bao quanh ảnh cover)
            val linkElement = div.selectFirst("div.thumb-cover > a") 
                ?: div.selectFirst("a[href*='/truyen-tranh/']")

            val href = linkElement?.attrAsRelativeUrl("href") ?: return@map null
            
            // Title nằm ở div bên dưới: <div class="text-[15px] ..."><a ...>Title</a></div>
            val titleElement = div.selectFirst("div.text-\\[15px\\] > a") ?: linkElement
            val title = titleElement.text().trim()

            val coverUrl = div.selectFirst("img")?.attr("src") ?: ""

            // Status detection logic (optional, based on UI stickers if exist)
            val isCompleted = div.selectFirst("span:contains(Full)") != null

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
                state = if (isCompleted) MangaState.FINISHED else MangaState.ONGOING,
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // Dựa trên file: thienthaitruyen1.com_truyen-tranh_my-little-sister-is-a-shut-in-j18.html
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        // Date parser hỗ trợ cả "2 giờ trước" và "19/06/2024"
        val dateParser = SmartDateParser(Locale("vi"))

        // Extract Description
        val description = root.selectFirst("p.comic-content")?.text()?.trim()

        // Extract Author
        val author = root.selectFirst("div.col-span-9:has(div:contains(Tác giả)) p.text-sm")
            ?.text()?.takeIf { it.trim() != "Đang cập nhật" }

        // Extract Status
        val statusText = root.selectFirst("div.col-span-9:has(div:contains(Trạng thái)) p.text-sm")?.text()
        val state = when (statusText) {
            "Đang ra" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> MangaState.ONGOING
        }

        // Extract Tags
        val tags = root.select("div.space-y-1 a[href*='/the-loai/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }

        // Extract Chapters
        // Selector: <div class="chapter-items">
        val chapters = root.select("div.chapter-items")
            .mapChapters(reversed = true) { i, item ->
                val a = item.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                
                // Name: <p class="text-sm ...">Oneshot</p>
                val name = a.selectFirst("p.text-sm")?.text()?.trim() ?: "Chapter ${i + 1}"
                
                // Date: <p class="text-xs ..."><span>19/06/2024</span></p>
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
        // Dựa trên file: thienthaitruyen1.com_truyen-tranh_my-little-sister-is-a-shut-in-j18_oneshot.html
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        // Selector: div.py-8 > div.center > img
        // Container chính chứa ảnh
        val container = doc.selectFirst("div.py-8 div.flex.flex-col.items-center") 
            ?: doc.selectFirst("div.py-8 div.w-full.mx-auto")

        val imageElements = container?.select("img") ?: return emptyList()

        return imageElements.mapNotNull { img ->
            val url = img.attr("src").ifEmpty { img.attr("data-src") }
            
            // Lọc bỏ ảnh loading, ảnh lỗi hoặc banner quảng cáo
            if (url.isBlank() || 
                url.contains("thumb-loading.gif") || 
                url.contains("thienthaitruyen-truyen-tranh-hentai.png") // Logo watermark thường thấy
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

        // Tìm trong <div class="filter-dropdown-container">
        val genreContainer = doc.selectFirst("div.filter-dropdown-container")
            ?: return emptySet()

        return genreContainer.select("label").mapNotNull { label ->
            val input = label.selectFirst("input[type=checkbox]")
            val title = label.selectFirst("span")?.text()
            val key = input?.attr("value")

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

    private fun extractChapterNumber(title: String): Float? {
        // Regex để tìm số (VD: "Chương 10", "Chapter 10.5")
        val regex = Regex("""(?:Chương|Chapter)\s+(\d+(\.\d+)?)""", RegexOption.IGNORE_CASE)
        val match = regex.find(title)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }

    private class SmartDateParser(private val locale: Locale) {
        // Format cứng cho trường hợp "19/06/2024"
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy", locale)

        fun parse(dateString: String?): Long? {
            if (dateString.isNullOrBlank()) return null
            
            val cleanDate = dateString.trim()

            // 1. Thử parse dạng ngày tháng cụ thể
            try {
                if (cleanDate.contains("/")) {
                    return dateFormat.parse(cleanDate)?.time
                }
            } catch (_: Exception) {}

            // 2. Thử parse dạng tương đối (relative)
            try {
                val now = Calendar.getInstance()
                val parts = cleanDate.lowercase(locale).split(" ")
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
