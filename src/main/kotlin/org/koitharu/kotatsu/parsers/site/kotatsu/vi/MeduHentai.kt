package org.koitharu.kotatsu.parsers.site.vi

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers.Companion.toHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MEDUHENTAI", "MeduHentai", "vi", type = ContentType.HENTAI)
internal class MeduHentaiParser(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.MEDUHENTAI), MangaParserAuthProvider {

    companion object {
        private const val MANGA_PER_PAGE = 24
    }

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("meduhentai.com")

    // --- MangaParserAuthProvider ---
    override val authUrl: String
        get() = domain

    override suspend fun isAuthorized(): Boolean =
        context.cookieJar.getCookies(domain).any {
            it.name.startsWith("__Secure-authjs") || it.name.startsWith("__Host-authjs")
        }

    override suspend fun getUsername(): String {
        try {
            val response = webClient.httpGet("/api/auth/session".toAbsoluteUrl(domain))
            if (response.isSuccessful) {
                val sessionJson = response.body!!.string()
                val obj = JSONObject(sessionJson)
                val user = obj.optJSONObject("user")
                    ?: throw IllegalStateException("User not found in session")
                return user.optString("username").ifBlank { user.optString("email") }
            } else {
                throw IllegalStateException("Failed to get user info: ${response.code}")
            }
        } catch (e: Exception) {
            throw AuthRequiredException(source, e)
        }
    }

    // --- BASIC PARSER FUNCTIONS ---
    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon("https://meduhentai.com/medusa.ico", 32, null)),
        domain
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = false,
        isSearchSupported = true
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = getOrCreateTagMap().values.toSet()
        )
    }

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val q = filter.query
        val page = (offset / MANGA_PER_PAGE.toFloat()).toIntUp() + 1

        val apiUrl = buildString {
            append("/api/manga?")
            when {
                !q.isNullOrEmpty() -> {
                    append("q=${q.urlEncoded()}")
                }
                filter.tags.isNotEmpty() -> {
                    val genreKey = filter.tags.first().key.urlEncoded()
                    append("genre=$genreKey")
                }
                else -> {
                    when (order) {
                        SortOrder.NEWEST, SortOrder.UPDATED -> append("sortBy=latestChapter&sortOrder=desc")
                        SortOrder.POPULARITY -> append("sort=popular")
                        SortOrder.RATING -> append("sortBy=likes&sortOrder=desc")
                        else -> append("sortBy=latestChapter&sortOrder=desc")
                    }
                }
            }
            append("&page=$page&limit=$MANGA_PER_PAGE")
        }.toAbsoluteUrl(domain)

        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val obj = JSONObject(responseJson)
        val mangas = obj.optJSONArray("mangas") ?: return emptyList()
        val tagMap = getOrCreateTagMap()

        return (0 until mangas.length()).map { i ->
            val item = mangas.getJSONObject(i)
            val finalCoverUrl = item.optString("coverImage", null)?.takeIf { it.isNotBlank() }

            Manga(
                id = generateUid(item.getString("_id")),
                title = item.getString("title"),
                url = "/manga/${item.getString("_id")}",
                publicUrl = "/manga/${item.getString("_id")}".toAbsoluteUrl(domain),
                coverUrl = finalCoverUrl,
                authors = setOfNotNull(item.optString("author", null)),
                tags = item.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).mapNotNullToSet { j ->
                        val genreKey = arr.getString(j).lowercase()
                        tagMap[genreKey] ?: MangaTag(genreKey, genreKey, source)
                    }
                } ?: emptySet(),
                source = source,
                contentRating = ContentRating.ADULT,
                altTitles = emptySet(),
                rating = item.optInt("likes", 0).toFloat().takeIf { it > 0f } ?: RATING_UNKNOWN,
                state = null
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast('/')
        val detailsApiUrl = "/api/manga/$mangaId".toAbsoluteUrl(domain)

        val headers = mapOf("Referer" to manga.publicUrl).toHeaders()
        val response = webClient.httpGet(detailsApiUrl, extraHeaders = headers)
        val responseJson = response.body!!.string()

        val obj = JSONObject(responseJson)
        val details = obj.optJSONObject("manga")
            ?: throw IllegalStateException("Failed to parse manga details for ID: $mangaId")

        val uploader = details.optJSONObject("userId")
        val scanlatorName = uploader?.optString("username")

        val chaptersArr = details.optJSONArray("chapters") ?: JSONArray()
        val chapters = (0 until chaptersArr.length()).map { i ->
            val chapterItem = chaptersArr.getJSONObject(i)
            MangaChapter(
                id = generateUid(chapterItem.getString("_id")),
                title = chapterItem.getString("title"),
                number = chapterItem.getInt("chapterNumber").toFloat(),
                url = "$mangaId|${chapterItem.getString("_id")}",
                uploadDate = parseDate(chapterItem.optString("createdAt")) ?: 0L,
                source = source,
                scanlator = scanlatorName,
                volume = 0,
                branch = null
            )
        }.sortedByDescending { it.number }

        val finalCoverUrl = details.optString("coverImage", null)?.takeIf { it.isNotBlank() }
        val author = details.optString("author", null)
        val artist = details.optString("artist", null)
        val authorsSet = setOfNotNull(author, artist)
        val tagMap = getOrCreateTagMap()

        val genresArr = details.optJSONArray("genres") ?: JSONArray()
        val tags = (0 until genresArr.length()).mapNotNullToSet { j ->
            val genreKey = genresArr.getString(j).lowercase()
            tagMap[genreKey] ?: MangaTag(genreKey, genreKey, source)
        }

        val altTitles = details.optJSONArray("alternativeTitles")?.let { arr ->
            (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
        } ?: emptySet()

        return manga.copy(
            coverUrl = finalCoverUrl,
            altTitles = altTitles,
            authors = authorsSet,
            description = details.optString("description", ""),
            tags = tags,
            chapters = chapters,
            state = parseMangaState(details.optString("status", null))
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (mangaId, chapterId) = chapter.url.split('|').takeIf { it.size == 2 }
            ?: throw IllegalArgumentException("Invalid chapter URL format. Expected 'mangaId|chapterId'")

        val detailsApiUrl = "/api/manga/$mangaId".toAbsoluteUrl(domain)

        val readUrl = "/manga/$mangaId/read/$chapterId".toAbsoluteUrl(domain)
        val headers = mapOf("Referer" to readUrl).toHeaders()
        val response = webClient.httpGet(detailsApiUrl, extraHeaders = headers)
        val responseJson = response.body!!.string()

        val obj = JSONObject(responseJson)
        val details = obj.optJSONObject("manga")
            ?: throw IllegalStateException("Failed to parse manga details (for pages)")

        val chaptersArr = details.optJSONArray("chapters") ?: JSONArray()
        var chapterData: JSONObject? = null
        for (i in 0 until chaptersArr.length()) {
            val ch = chaptersArr.getJSONObject(i)
            if (ch.getString("_id") == chapterId) {
                chapterData = ch
                break
            }
        }
        if (chapterData == null) {
            throw IllegalStateException("Chapter $chapterId not found in API response")
        }

        val pagesArr = chapterData.optJSONArray("pages") ?: JSONArray()
        if (pagesArr.length() == 0) {
            return emptyList()
        }

        return (0 until pagesArr.length()).mapNotNull { i ->
            val pageObj = pagesArr.getJSONObject(i)
            val imageUrl = pageObj.getString("imageUrl").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                source = source,
                preview = null
            )
        }
    }

    private var tagCache: ArrayMap<String, MangaTag>? = null
    private val mutex = Mutex()

    private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
        tagCache?.let { return@withLock it }

        val staticGenres = listOf(
            MangaTag(title = "Hành động", key = "action", source = source),
            MangaTag(title = "Phiêu lưu", key = "adventure", source = source),
            MangaTag(title = "Hài hước", key = "comedy", source = source),
            MangaTag(title = "Drama", key = "drama", source = source),
            MangaTag(title = "Fantasy", key = "fantasy", source = source),
            MangaTag(title = "Kinh dị", key = "horror", source = source),
            MangaTag(title = "Bí ẩn", key = "mystery", source = source),
            MangaTag(title = "Lãng mạn", key = "romance", source = source),
            MangaTag(title = "Khoa học viễn tưởng", key = "sci-fi", source = source),
            MangaTag(title = "Đời thường", key = "slice-of-life", source = source),
            MangaTag(title = "Thể thao", key = "sports", source = source),
            MangaTag(title = "Siêu nhiên", key = "supernatural", source = source),
            MangaTag(title = "Giật gân", key = "thriller", source = source)
        )

        val tagMap = ArrayMap<String, MangaTag>()
        for (tag in staticGenres) {
            tagMap[tag.key] = tag
        }

        tagCache = tagMap
        return@withLock tagMap
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr == null) return null
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        return try {
            sdf.parse(dateStr)?.time
        } catch (e: ParseException) {
            try {
                val simplerSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                simplerSdf.parse(dateStr)?.time
            } catch (e2: ParseException) { null }
        }
    }

    private fun parseMangaState(status: String?): MangaState? {
        return when (status?.lowercase()) {
            "completed" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            else -> null
        }
    }
}
