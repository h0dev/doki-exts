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

        // FIX: Thay vì tìm theo class grid cụ thể, tìm trực tiếp class "thumb-cover"
        // Đây là class bao quanh ảnh bìa của mỗi truyện
        val elements = doc.select("div.thumb-cover")

        return elements.mapNotNull { thumb ->
            // Link truyện nằm trong thẻ a bên trong thumb-cover
            val linkNode = thumb.selectFirst("a") ?: return@mapNotNull null
            val href = linkNode.attrAsRelativeUrl("href")
            val coverUrl = thumb.selectFirst("img")?.attr("src") ?: ""

            // Lấy tiêu đề:
            // Tiêu đề thường nằm ở thẻ cha của thumb-cover -> tìm xuống các thẻ text
            // Ưu tiên tìm h3, sau đó tìm thẻ a có class text (cho giao diện mobile/list)
            val container = thumb.parent()
            val titleNode = container?.selectFirst("h3, div[class*='text-'] > a, span.font-medium")
            
            // Fallback: Lấy title từ attribute của ảnh hoặc link nếu không tìm thấy text
            val title = titleNode?.text()?.trim() 
                ?: thumb.selectFirst("img")?.attr("alt")?.substringAfter("truyện ") // Remove prefix nếu có
                ?: linkNode.attr("title")

            if (title.isNullOrBlank()) return@mapNotNull null

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
                state = null, // Có thể update logic check trạng thái nếu cần
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        // Sử dụng parser thông minh hỗ trợ nhiều định dạng ngày
        val dateParser = SmartDateParser(Locale("vi"))

        // Tìm mô tả (thường nằm trong thẻ p có class comic-content)
        val description = root.selectFirst("p.comic-content")?.text()?.trim()

        // Tìm tác giả: Tìm div chứa text "Tác giả", sau đó lấy thẻ p.text-sm bên cạnh
        val author = root.selectFirst("div:contains(Tác giả) + div p.text-sm, div:contains(Tác giả) p.text-sm")
            ?.text()?.takeIf { it.trim() != "Đang cập nhật" }

        // Tìm trạng thái
        val statusText = root.selectFirst("div:contains(Trạng thái) p.text-sm")?.text()
        val state = when (statusText) {
            "Đang ra" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> MangaState.ONGOING
        }

        // Tags
        val tags = root.select("div.space-y-1 a[href*='/the-loai/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }

        // Chapters
        val chapters = root.select("div.chapter-items")
            .mapChapters(reversed = true) { i, item ->
                val a = item.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                
                // Tên chương
                val name = a.selectFirst("p.text-sm")?.text()?.trim() ?: "Chapter ${i + 1}"
                
                // Ngày đăng
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

        // Selector ảnh: Tìm trong div center hoặc container chính
        val container = doc.selectFirst("div.py-8 div.flex.flex-col.items-center") 
            ?: doc.selectFirst("div.py-8 div.w-full.mx-auto")

        val imageElements = container?.select("img") ?: return emptyList()

        return imageElements.mapNotNull { img ->
            // Ưu tiên lấy src, nếu rỗng thì lấy data-src (lazy load)
            val url = img.attr("src").ifEmpty { img.attr("data-src") }
            
            // Lọc bỏ ảnh rác (loading, banner, logo)
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

            // 1. Thử parse dạng ngày tháng cụ thể (dd/MM/yyyy)
            try {
                if (cleanDate.contains("/")) {
                    return dateFormat.parse(cleanDate)?.time
                }
            } catch (_: Exception) {}

            // 2. Thử parse dạng tương đối (vừa xong, 2 giờ trước...)
            try {
                val now = Calendar.getInstance()
                val parts = cleanDate.lowercase(locale).split(" ")
                
                // Xử lý "Vừa xong" hoặc tương tự
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
