package org.koitharu.kotatsu.parsers.site.vi

import androidx.collection.arraySetOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.json.JSONObject
import java.util.*

@MangaSourceParser("GOCTRUYENTRANHVUI", "Góc Truyện Tranh Vui", "vi")
internal class GocTruyenTranhVui(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.GOCTRUYENTRANHVUI, 50) {

    override val configKeyDomain = ConfigKey.Domain("goctruyentranhvui30.com")
    private val apiUrl by lazy { "https://$domain/api/v2" }
    private val baseUrl get() = "https://$domain"

    companion object {
        private const val REQUEST_DELAY_MS = 350L
        private const val TOKEN_KEY = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJBbG9uZSBGb3JldmVyIiwiY29taWNJZHMiOltdLCJyb2xlSWQiOm51bGwsImdyb3VwSWQiOm51bGwsImFkbWluIjpmYWxzZSwicmFuayI6MCwicGVybWlzc2lvbiI6W10sImlkIjoiMDAwMTA4NDQyNSIsInRlYW0iOmZhbHNlLCJpYXQiOjE3NTM2OTgyOTAsImVtYWlsIjoibnVsbCJ9.HT080LGjvzfh6XAPmdDZhf5vhnzUhXI4GU8U6tzwlnXWjgMO4VdYL1jsSFWd-s3NBGt-OAt89XnzaQ03iqDyA"
        private val WEBVIEW_TOKEN_REGEX = Regex(""";\s*wv\)""")
    }

    private val requestMutex = Mutex()
    private var lastRequestTime = 0L
    private var cachedToken: String? = null

    private fun sanitizeUserAgent(ua: String): String = WEBVIEW_TOKEN_REGEX.replace(ua, ")")

    private suspend fun getAuthToken(): String {
        cachedToken?.let {
            println("[GTTV] token: using cached token (prefix=${it.take(30)}...)")
            return it
        }

        // Try extracting from WebView localStorage (works on Android)
        val webToken = try {
            println("[GTTV] token: trying evaluateJs...")
            val raw = context.evaluateJs(
                "https://$domain",
                "window.localStorage.getItem('Authorization')"
            )
            println("[GTTV] token: evaluateJs returned: $raw")
            raw?.removeSurrounding("\"")
        } catch (e: Exception) {
            println("[GTTV] token: evaluateJs failed: ${e.message}")
            null
        }

        val token = if (!webToken.isNullOrBlank() && webToken != "null") {
            println("[GTTV] token: using WebView token (prefix=${webToken.take(30)}...)")
            "Bearer $webToken"
        } else {
            println("[GTTV] token: fallback to hardcoded TOKEN_KEY")
            TOKEN_KEY
        }
        cachedToken = token
        return token
    }

    /**
     * Regular headers for page loads (visiting HTML pages to refresh cookies).
     */
    private fun pageHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", sanitizeUserAgent(context.getDefaultUserAgent()))
        .build()

    /**
     * Full XHR headers matching browser-like requests for API calls.
     */
    private suspend fun xhrHeaders(): Headers = Headers.Builder()
        .add("Authorization", getAuthToken())
        .add("Referer", "https://$domain/")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .add("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Cache-Control", "max-age=0")
        .add("Sec-Ch-Ua-Mobile", "?1")
        .add("Sec-Ch-Ua-Platform", "\"Android\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .build().also {
            println("[GTTV] xhrHeaders: Authorization=${it["Authorization"]?.take(30)}...")
        }

    /**
     * Headers for chapter/image API. Matches keiyoushi's pageHeaders.
     * Uses token if available, falls back to xhrHeaders.
     */
    private suspend fun pageApiHeaders(): Headers {
        val token = getAuthToken()
        return Headers.Builder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Origin", baseUrl)
            .add("Authorization", token)
            .build().also {
                println("[GTTV] pageApiHeaders: Authorization=${it["Authorization"]?.take(30)}...")
            }
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val q = filter.query
        enforceRateLimit()
        val url = buildString {
            append(apiUrl)
            append("/search?p=${page - 1}")
            if (!q.isNullOrBlank()) {
                append("&searchValue=${q.urlEncoded()}")
            }

            val sortValue = when (order) {
                SortOrder.POPULARITY -> "viewCount"
                SortOrder.NEWEST -> "createdAt"
                SortOrder.RATING -> "evaluationScore"
                else -> "recentDate" // UPDATED
            }
            append("&orders%5B%5D=$sortValue")

            filter.tags.forEach { append("&categories%5B%5D=${it.key}") }

            filter.states.forEach {
                val statusKey = when (it) {
                    MangaState.ONGOING -> "PRG"
                    MangaState.FINISHED -> "END"
                    else -> null
                }
                if (statusKey != null) append("&status%5B%5D=$statusKey")
            }
        }

        val json = webClient.httpGet(url, extraHeaders = xhrHeaders()).parseJson()
        val result = json.optJSONObject("result")
        val data = result?.optJSONArray("data")
        println("[GTTV] getListPage page=$page url=$url result=${result != null} dataCount=${data?.length() ?: 0}")
        if (data == null) return emptyList()

        return List(data.length()) { i ->
            val item = data.getJSONObject(i)
            val comicId = item.getString("id")
            val slug = item.getString("nameEn")
            val mangaUrl = "/truyen/$slug"
            val tags = item.optJSONArray("category")?.let { arr ->
                (0 until arr.length()).mapNotNullTo(mutableSetOf()) { index ->
                    val tagName = arr.getString(index)
                    availableTags().find { it.title.equals(tagName, ignoreCase = true) }?.let { genrePair ->
                        MangaTag(key = genrePair.key, title = genrePair.title, source = source)
                    }
                }
            } ?: emptySet()

            Manga(
                id = generateUid(comicId),
                title = item.getString("name"),
                altTitles = item.optString("otherName", "").split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet(),
                url = "$comicId:$slug", // Store both id and slug, separated by ':'
                publicUrl = "https://$domain$mangaUrl",
                rating = item.optDouble("evaluationScore", 0.0).toFloat(),
                contentRating = null,
                coverUrl = "https://$domain${item.getString("photo")}",
                tags = tags,
                state = when (item.optString("statusCode")) {
                    "PRG" -> MangaState.ONGOING
                    "END" -> MangaState.FINISHED
                    else -> null
                },
                authors = setOf(item.optString("author", "Updating")),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val comicId = manga.url.substringBefore(':')
        val slug = manga.url.substringAfter(':')
        println("[GTTV] getDetails START comicId=$comicId slug=$slug url=${manga.url} publicUrl=${manga.publicUrl}")

        try {
            // Step 1: Visit manga detail page to refresh session cookies
            enforceRateLimit()
            println("[GTTV] Step1: refreshing cookies via ${manga.publicUrl}")
            val refreshResp = webClient.httpGet(manga.publicUrl, extraHeaders = pageHeaders())
            println("[GTTV] Step1: status=${refreshResp.code}")
            refreshResp.close()

        // Step 2: Fetch chapter list via API
        val chapters = try {
            enforceRateLimit()
            val chapterApiUrl = "https://$domain/api/comic/$comicId/chapter?limit=-1"
            println("[GTTV] Step2: fetching chapters from $chapterApiUrl")
            val chapterResp = webClient.httpGet(chapterApiUrl, extraHeaders = xhrHeaders())
            println("[GTTV] Step2: status=${chapterResp.code}")
            val chapterBody = chapterResp.body?.string().orEmpty()
            println("[GTTV] Step2: body=${chapterBody.take(500)}")
            val chapterJson = JSONObject(chapterBody)
            val chaptersData = chapterJson.getJSONObject("result").getJSONArray("chapters")
            println("[GTTV] Step2: found ${chaptersData.length()} chapters")

            if (chaptersData.length() == 0) {
                throw Exception("Phiên làm việc đã hết hạn, vui lòng tải lại.")
            }

            List(chaptersData.length()) { i ->
                val item = chaptersData.getJSONObject(i)
                val number = item.getString("numberChapter")
                val name = item.getString("name")
                val chapterUrl = "/truyen/$slug/chuong-$number#$comicId"
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = if (name != "N/A" && name.isNotBlank()) name else "Chapter $number",
                    number = number.toFloatOrNull() ?: -1f,
                    volume = 0,
                    url = chapterUrl,
                    scanlator = null,
                    uploadDate = item.optLong("updateTime", 0L),
                    branch = null,
                    source = source
                )
            }
        } catch (e: Exception) {
            println("[GTTV] Step2 ERROR: ${e.javaClass.simpleName}: ${e.message}")
            if (e.message?.contains("hết hạn") == true) throw e
            throw Exception("Không thể tải danh sách chương. Vui lòng thử lại.\n${e.message}")
        }.reversed()

        // Step 3: Parse detail page for additional info
        val doc = webClient.httpGet(manga.publicUrl, extraHeaders = pageHeaders()).parseHtml()

        val detailTags = doc.select(".group-content > .v-chip-link").mapNotNullTo(mutableSetOf()) { el ->
            availableTags().find { it.title.equals(el.text(), ignoreCase = true) }?.let {
                MangaTag(key = it.key, title = it.title, source = source)
            }
        }

        return manga.copy(
            title = doc.selectFirst(".v-card-title")?.text().orEmpty(),
            tags = manga.tags + detailTags,
            coverUrl = doc.selectFirst("img.image")?.absUrl("src"),
            state = when (doc.selectFirst(".mb-1:contains(Trạng thái:) span")?.text()) {
                "Đang thực hiện" -> MangaState.ONGOING
                "Hoàn thành" -> MangaState.FINISHED
                else -> manga.state
            },
            authors = setOfNotNull(doc.selectFirst(".mb-1:contains(Tác giả:) span")?.text()),
            description = doc.selectFirst(".v-card-text")?.text(),
            chapters = chapters
        )
        } catch (e: Exception) {
            println("[GTTV] getDetails FAILED: ${e.javaClass.simpleName}: ${e.message}")
            println("[GTTV] getDetails stacktrace:")
            e.printStackTrace()
            throw e
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        println("[GTTV] getPages START url=${chapter.url}")
        try {
            enforceRateLimit()

            val chapterUrl = chapter.url
            val slug = chapterUrl.substringAfter("/truyen/").substringBefore("/chuong-")
            val numberChapter = chapterUrl.substringAfter("/chuong-").substringBefore("#")
            val comicId = chapterUrl.substringAfter("#")
            println("[GTTV] getPages slug=$slug chapter=$numberChapter comicId=$comicId")

            if (comicId.isBlank()) {
                throw Exception("Cannot find comicId in chapter URL: ${chapter.url}")
            }

            val formBody = mapOf(
                "comicId" to comicId,
                "chapterNumber" to numberChapter,
                "nameEn" to slug,
            )
            val loadAllUrl = "$baseUrl/api/chapter/loadAll".toHttpUrl()
            println("[GTTV] getPages: POST $loadAllUrl")
            val resp = webClient.httpPost(url = loadAllUrl, form = formBody, extraHeaders = pageApiHeaders())
            println("[GTTV] getPages: status=${resp.code}")
            val body = resp.body?.string().orEmpty()
            println("[GTTV] getPages: body=${body.take(500)}")
            val json = JSONObject(body)
            val data = json.getJSONObject("result").getJSONArray("data")
            println("[GTTV] getPages: found ${data.length()} images")

            if (data.length() == 0) {
                throw Exception("Chưa đăng nhập trong WebView. Hoặc không có ảnh!")
            }

            return List(data.length()) { i ->
                val url = data.getString(i)
                val finalUrl = if (url.startsWith("/image/")) "https://$domain$url" else url
                MangaPage(id = generateUid(finalUrl), url = finalUrl, preview = null, source = source)
            }
        } catch (e: Exception) {
            println("[GTTV] getPages FAILED: ${e.javaClass.simpleName}: ${e.message}")
            println("[GTTV] getPages stacktrace:")
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun enforceRateLimit() {
        requestMutex.withLock {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - lastRequestTime
            if (timeSinceLastRequest < REQUEST_DELAY_MS) {
                delay(REQUEST_DELAY_MS - timeSinceLastRequest)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }

    /**
     * NOTE: This function creates a new Set of MangaTags on every call, which is inefficient.
     * A better approach is to declare a constant list (private val) and reuse it.
     */
    private fun availableTags() = arraySetOf(
        MangaTag("Anime", "ANI", source),
        MangaTag("Drama", "DRA", source),
        MangaTag("Josei", "JOS", source),
        MangaTag("Manhwa", "MAW", source),
        MangaTag("One Shot", "OSH", source),
        MangaTag("Shounen", "SHO", source),
        MangaTag("Webtoons", "WEB", source),
        MangaTag("Shoujo", "SHJ", source),
        MangaTag("Harem", "HAR", source),
        MangaTag("Ecchi", "ECC", source),
        MangaTag("Mature", "MAT", source),
        MangaTag("Slice of life", "SOL", source),
        MangaTag("Isekai", "ISE", source),
        MangaTag("Manga", "MAG", source),
        MangaTag("Manhua", "MAU", source),
        MangaTag("Hành Động", "ACT", source),
        MangaTag("Phiêu Lưu", "ADV", source),
        MangaTag("Hài Hước", "COM", source),
        MangaTag("Võ Thuật", "MAA", source),
        MangaTag("Huyền Bí", "MYS", source),
        MangaTag("Lãng Mạn", "ROM", source),
        MangaTag("Thể Thao", "SPO", source),
        MangaTag("Học Đường", "SCL", source),
        MangaTag("Lịch Sử", "HIS", source),
        MangaTag("Kinh Dị", "HOR", source),
        MangaTag("Siêu Nhiên", "SUN", source),
        MangaTag("Bi Kịch", "TRA", source),
        MangaTag("Trùng Sinh", "RED", source),
        MangaTag("Game", "GAM", source),
        MangaTag("Viễn Tưởng", "FTS", source),
        MangaTag("Khoa Học", "SCF", source),
        MangaTag("Truyện Màu", "COI", source),
        MangaTag("Người Lớn", "ADU", source),
        MangaTag("BoyLove", "BBL", source),
        MangaTag("Hầm Ngục", "DUN", source),
        MangaTag("Săn Bắn", "HUNT", source),
        MangaTag("Ngôn Từ Nhạy Cảm", "NTNC", source),
        MangaTag("Doujinshi", "DOU", source),
        MangaTag("Bạo Lực", "BLM", source),
        MangaTag("Ngôn Tình", "NTT", source),
        MangaTag("Nữ Cường", "NCT", source),
        MangaTag("Gender Bender", "GDB", source),
        MangaTag("Murim", "MRR", source),
        MangaTag("Leo Tháp", "LTT", source),
        MangaTag("Nấu Ăn", "COO", source)
    )
}
