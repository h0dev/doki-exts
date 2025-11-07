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

    private val availableTags = suspendLazy(initializer = ::fetchTags)

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // =================================================================
    // HÀM ĐÃ ĐƯỢC CHỈNH SỬA
    // =================================================================
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // /moi-cap-nhat (Mặc định)
        SortOrder.POPULARITY,   // /xem-nhieu-nhat
        SortOrder.NEWEST        // /truyen-moi
        // Trang 'Đề cử' (/de-cu) sẽ không được gán vào sort order cụ thể,
        // nhưng vẫn có thể truy cập nếu logic getListPage xử lý (đã bỏ)
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            // FIX: Tắt hỗ trợ multi-tag theo yêu cầu
            isMultipleTagsSupported = false, 
            isTagsExclusionSupported = false
        )
    // =================================================================
    // KẾT THÚC HÀM CHỈNH SỬA
    // =================================================================

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags.get(),
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
                // 1. Ưu tiên tìm kiếm
                !filter.query.isNullOrEmpty() -> {
                    append("/page/$page?s=${filter.query.urlEncoded()}")
                }
                
                // 2. Lọc theo thể loại (Đã tắt multi-tag)
                filter.tags.isNotEmpty() -> {
                    append("/category/${filter.tags.first().key}/page/$page")
                }

                // 3. Sắp xếp (Trang danh sách)
                else -> {
                    when (order) {
                        SortOrder.POPULARITY -> append("/xem-nhieu-nhat/page/$page")
                        SortOrder.NEWEST -> append("/truyen-moi/page/$page")
                        // FIX: Mặc định (UPDATED) trỏ về /moi-cap-nhat
                        else -> append("/moi-cap-nhat/page/$page") 
                    }
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        val itemSelector = "div.col[class*=item-]"

        return doc.select(itemSelector).mapNotNull { element ->
            val a = element.selectFirst("a[href*='.html']")
            if (a == null) {
                return@mapNotNull null
            }

            val href = a.attrAsRelativeUrl("href")
            
            val img = a.selectFirst("img.card-img-top")
            val coverUrl = img?.attrOrNull("data-src") ?: img?.attr("src")

            Manga(
                id = generateUid(href),
                title = a.attr("title").ifEmpty { a.selectFirst("h2.card-manga-title")?.text().orEmpty() },
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentType.HENTAI.toContentRating(), // Đảm bảo đúng loại
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

        val altTitles = root.selectFirst("p.other-name-container span.other-name")
            ?.text()?.split("/")?.map { it.trim() }?.toSet() ?: emptySet()

        val author = root.selectFirst("div.manga-info span:has(i.bi-journal-bookmark-fill) a")?.text()

        val statusText = root.selectFirst("div.manga-info span:has(i.bi-arrow-clockwise) strong")?.text()
        val state = when (statusText) {
            "FULL" -> MangaState.FINISHED
            "Đang cập nhật" -> MangaState.ONGOING
            else -> null
        }

        val tags = root.select("div.categories a[href*='/category/']").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().trim(),
                source = source,
            )
        }

        val description = root.selectFirst("p.mt-2.card-text.desc")?.text()

        val chapters = root.select("ul.list-group.list-chapters li.list-group-item")
            .mapChapters(reversed = true) { i, li ->
                val a = li.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                val name = a.selectFirst("span.fw-bold")?.text().orEmpty()
                val dateText = li.selectFirst("em")?.text()

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = name.substringAfter("Chapter ").toFloatOrNull() ?: (i + 1).toFloat(),
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

        val imageElements = doc.select("div#chapter-content img[decoding=async]")

        return imageElements.map { img ->
            val url = img.attr("src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

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
     * Helper class để parse các chuỗi ngày tương đối (VD: "23 giờ trước")
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
}
