package org.dokiteam.doki.parsers.site.vi

import androidx.collection.ArrayMap
import androidx.collection.arraySetOf
import org.json.JSONObject
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaParserAuthProvider
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.exception.AuthRequiredException
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.json.*
import org.dokiteam.doki.parsers.util.suspendlazy.suspendLazy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

private const val PAGE_SIZE = 20
private const val CHAPTER_PAGE_SIZE = 50

@MangaSourceParser("CMANGA", "CManga", "vi")
internal class CMangaParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.CMANGA, PAGE_SIZE), MangaParserAuthProvider {

    // Cho phép người dùng override domain (nếu Doki hỗ trợ)
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("cmangax16.com")

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.POPULARITY,
            SortOrder.POPULARITY_TODAY,
            SortOrder.POPULARITY_WEEK,
            SortOrder.POPULARITY_MONTH,
            SortOrder.RELEVANCE,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )

    private val tags = suspendLazy(initializer = this::getTags)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = tags.get().values.toArraySet(),
            availableStates = arraySetOf(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        )
    }

    override val authUrl: String
        get() = domain

    override suspend fun isAuthorized(): Boolean =
        context.cookieJar.getCookies(domain).any { it.name == "login_password" }

    override suspend fun getUsername(): String {
        val userId = webClient.httpGet("https://$domain").parseRaw()
            .substringAfter("token_user = ")
            .substringBefore(';')
            .trim()
        if (userId.isEmpty() || userId == "0") throw AuthRequiredException(
            source,
            IllegalStateException("No userId found"),
        )
        return webClient.httpGet("/api/user_info?user=$userId".toAbsoluteUrl(domain)).parseJson()
            .parseJson("info")
            .getString("name")
    }

    // ============================== Lấy danh sách truyện ===============================

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = urlBuilder().apply {
            if (filter.query.isNullOrEmpty() && (order == SortOrder.RELEVANCE ||
                        order == SortOrder.POPULARITY_TODAY ||
                        order == SortOrder.POPULARITY_WEEK ||
                        order == SortOrder.POPULARITY_MONTH)
            ) {
                addPathSegments("api/home_album_top")
            } else {
                addPathSegments("api/home_album_list")
                addQueryParameter("num_chapter", "0")
                addQueryParameter("team", "0")
                addQueryParameter("sort", "update")
                addQueryParameter("tag", filter.tags.joinToString(separator = ",") { it.key })
                addQueryParameter("string", filter.query.orEmpty())
                addQueryParameter(
                    "status",
                    when (filter.states.oneOrThrowIfMany()) {
                        MangaState.ONGOING -> "doing"
                        MangaState.FINISHED -> "done"
                        MangaState.PAUSED -> "drop"
                        else -> "all"
                    },
                )
            }

            addQueryParameter("file", "image")
            addQueryParameter("limit", PAGE_SIZE.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter(
                "type",
                when (order) {
                    SortOrder.UPDATED -> "update"
                    SortOrder.POPULARITY -> "hot"
                    SortOrder.NEWEST -> "new"
                    SortOrder.POPULARITY_TODAY -> "day"
                    SortOrder.POPULARITY_WEEK -> "week"
                    SortOrder.POPULARITY_MONTH -> "month"
                    SortOrder.RELEVANCE -> "fire" // return duplicate manga so the app won't load second page
                    else -> throw IllegalArgumentException("Order not supported ${order.name}")
                },
            )
        }.build()

        val mangaList = webClient.httpGet(url).parseJson().getJSONArray("data")
        return mangaList.mapJSONNotNull { jo ->
            val info = jo.parseJson("info")
            val slug = info.getStringOrNull("url") ?: return@mapJSONNotNull null
            val id = info.getLongOrDefault("id", 0L)
            if (id == 0L) {
                return@mapJSONNotNull null
            }
            val relativeUrl = "/album/$slug-$id"
            val title = info.getString("name").replace("\\", "")
            val altTitle = info.optJSONArray("name_other")?.asTypedList<String>()?.map { it.replace("\\", "") }

            Manga(
                id = generateUid(id),
                title = title.toTitleCase(),
                altTitles = altTitle?.toSet().orEmpty(),
                url = relativeUrl,
                publicUrl = relativeUrl.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = resolveCoverUrl(info.getStringOrNull("avatar")),
                tags = info.optJSONArray("tags")?.asTypedList<String>()
                    ?.mapNotNullToSet { tags.get()[it.lowercase()] }
                    .orEmpty(),
                state = when (info.optString("status")) {
                    "doing" -> MangaState.ONGOING
                    "done" -> MangaState.FINISHED
                    else -> null
                },
                authors = emptySet(),
                largeCoverUrl = null,
                description = info.getStringOrNull("detail")?.replace("\\\"", "\""),
                chapters = emptyList(),
                source = source,
            )
        }
    }

    // ============================== Chi tiết truyện & chapters ===============================

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast('-')
        val slug = manga.url.substringBeforeLast('-').substringAfterLast('/')
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        df.timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

        // Lấy tất cả chapters (phân trang nhiều lần nếu cần)
        val allChapters = mutableListOf<MangaChapter>()
        var currentPage = 1
        var hasMore = true

        while (hasMore) {
            val response = webClient.httpGet(
                "/api/chapter_list?album=$mangaId&page=$currentPage&limit=$CHAPTER_PAGE_SIZE&v=0".toAbsoluteUrl(domain)
            ).parseJson()
            val items = response.getJSONArray("data")
            if (items.length() == 0) break

            val chaptersOnPage = items.mapChapters(reversed = true) { _, jo ->
                val chapterInfo = parseChapterInfo(jo.getString("info")) ?: return@mapChapters null
                val chapterId = chapterInfo.optString("id").ifEmpty { jo.getLong("id_chapter").toString() }
                val chapterNumber = chapterInfo.optString("num").toFloatOrNull() ?: -1f
                val chapterTitle = buildChapterTitle(chapterNumber, chapterInfo.optString("name"))
                val isLocked = isChapterLocked(chapterInfo)

                MangaChapter(
                    id = generateUid(chapterId),
                    title = if (isLocked) "🔒 $chapterTitle" else chapterTitle,
                    number = chapterNumber,
                    volume = 0,
                    url = "/album/$slug/chapter-$chapterNumber-$chapterId",
                    uploadDate = df.parseSafe(chapterInfo.getString("last_update")),
                    branch = null,
                    scanlator = null,
                    source = source,
                )
            }
            allChapters.addAll(chaptersOnPage)
            hasMore = items.length() >= CHAPTER_PAGE_SIZE
            currentPage++
        }

        return manga.copy(chapters = allChapters)
    }

    // ============================== Lấy pages của chapter ===============================

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast('-')
        val userSecurity = getUserSecurity()

        val url = urlBuilder().apply {
            addPathSegments("api/chapter_image")
            addQueryParameter("chapter", chapterId)
            addQueryParameter("v", "0")
            addQueryParameter("time", (System.currentTimeMillis() / 1000).toString())
            addQueryParameter("user_id", userSecurity.id ?: "0")
            addQueryParameter("user_token", userSecurity.token ?: "")
        }.build()

        val response = webClient.httpGet(url).parseJson()
        val imageData = response.optJSONObject("data")
        if (imageData == null || imageData.optInt("status") != 1) {
            throw IllegalStateException("Chapter is locked or requires login. Please log in via WebView.")
        }

        val images = imageData.getJSONArray("image").asTypedList<String>()
        if (images.isEmpty()) {
            throw IllegalStateException("No images found for this chapter.")
        }

        return images.mapIndexed { index, imgUrl ->
            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                source = source,
                preview = null,
            )
        }
    }

    // ============================== Lấy danh sách tags ===============================

    private suspend fun getTags(): Map<String, MangaTag> {
        val tagList = webClient.httpGet("assets/json/album_tags_image.json".toAbsoluteUrl(domain)).parseJson()
            .getJSONObject("list")
        val tags = ArrayMap<String, MangaTag>(tagList.length())
        for (key in tagList.keys()) {
            val jo = tagList.getJSONObject(key)
            val name = jo.getString("name")
            tags[name.lowercase()] = MangaTag(
                title = name.toTitleCase(),
                key = name,
                source = source,
            )
        }
        return tags
    }

    // ============================== Helper functions ===============================

    private fun resolveCoverUrl(avatar: String?): String? {
        if (avatar.isNullOrBlank()) return null
        return if (avatar.startsWith("http")) avatar
        else "/assets/tmp/album/$avatar".toAbsoluteUrl(domain)
    }

    private fun parseChapterInfo(rawInfo: String): JSONObject? {
        return runCatching { JSONObject(rawInfo) }.getOrNull()
    }

    private fun buildChapterTitle(number: Float, title: String?): String {
        val numStr = if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()
        val chapterNum = "Chương $numStr"
        if (title.isNullOrBlank()) return chapterNum
        // Tránh trùng lặp "Chương 1: Chương 1"
        val normalizedTitle = title.lowercase().removeSuffix(":")
        if (normalizedTitle == "chương $numStr" ||
            normalizedTitle == "chapter $numStr" ||
            normalizedTitle == "chap $numStr" ||
            normalizedTitle == numStr) {
            return chapterNum
        }
        return "$chapterNum: $title"
    }

    private fun isChapterLocked(info: JSONObject): Boolean {
        // Kiểm tra lock field
        val lock = info.optJSONObject("lock")
        val hasActiveLock = lock != null && (lock.optLong("end", 0) >= System.currentTimeMillis() / 1000)
        val level = info.optInt("level", 0)
        val lockLevel = lock?.optInt("level", 0) ?: 0
        val lockFee = lock?.optInt("fee", 0) ?: 0
        return hasActiveLock || level != 0 || lockLevel != 0 || lockFee != 0
    }

    private data class UserSecurity(val id: String?, val token: String?)

    private fun getUserSecurity(): UserSecurity {
        val cookie = context.cookieJar.getCookies(domain).firstOrNull { it.name == "user_security" }
        val cookieValue = cookie?.value ?: return UserSecurity(null, null)
        val decoded = decodeCookieValue(cookieValue) ?: return UserSecurity(null, null)
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return UserSecurity(null, null)
        return UserSecurity(
            id = json.optString("id").takeIf { it.isNotEmpty() },
            token = json.optString("token").takeIf { it.isNotEmpty() }
        )
    }

    private fun decodeCookieValue(value: String): String? {
        var decoded = value
        repeat(2) {
            val next = runCatching { URLDecoder.decode(decoded, StandardCharsets.UTF_8.name()) }.getOrNull()
                ?: return null
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    // ============================== JSON extensions (giữ nguyên từ code cũ) ===============================

    private fun JSONObject.parseJson(key: String): JSONObject {
        return JSONObject(getString(key))
    }
}
