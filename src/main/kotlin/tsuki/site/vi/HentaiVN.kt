package tsuki.site.vi

import androidx.collection.ArrayMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.AbstractMangaParser
import tsuki.exception.AuthRequiredException
import tsuki.model.*
import tsuki.util.*
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("HENTAIVN", "HentaiVN", "vi", type = ContentType.HENTAI)
internal class HentaiVNParser(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.HENTAIVN), MangaParserAuthProvider {

    companion object {
        private const val PLACEHOLDER_IMAGE_URL = "https://hentaivn.su/placeholder-error.webp"
        private const val MANGA_PER_PAGE = 24
    }

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("hentaivn.su")

    // --- MangaParserAuthProvider ---
    override val authUrl: String
        get() = domain

    override suspend fun isAuthorized(): Boolean =
        context.cookieJar.getCookies(domain).any { it.name == "id" }

    override suspend fun getUsername(): String {
        try {
            val response = webClient.httpGet("/api/user/me".toAbsoluteUrl(domain))
            if (response.isSuccessful) {
                val obj = JSONObject(response.body!!.string())
                return obj.optString("displayName").ifBlank { obj.getString("username") }
            } else {
                throw IllegalStateException("Failed to get user info: ${response.code}")
            }
        } catch (e: Exception) {
            throw AuthRequiredException(source, e)
        }
    }

    // --- BASIC PARSER FUNCTIONS ---
    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon("https://raw.githubusercontent.com/dragonx943/listcaidaubuoi/refs/heads/main/hentaivn.png", 512, null)),
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
        isMultipleTagsSupported = true,
        isSearchSupported = true
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = getOrCreateTagMap().values.toSet()
    )

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val q = filter.query
        val page = (offset / MANGA_PER_PAGE.toFloat()).toIntUp() + 1
        val apiUrl = buildString {
            append("/api/library/")
            when {
                !q.isNullOrEmpty() -> append("search?q=${q.urlEncoded()}&page=$page")
                filter.tags.isNotEmpty() -> {
                    val included = filter.tags.joinToString(",") { "(${it.key},1)" }
                    append("advanced-search?g=${included.urlEncoded()}&page=$page")
                }
                else -> {
                    when (order) {
                        SortOrder.NEWEST -> append("new?page=$page")
                        SortOrder.POPULARITY, SortOrder.RATING -> append("trending?page=$page")
                        else -> append("latest?page=$page")
                    }
                }
            }
        }.toAbsoluteUrl(domain)

        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val mangaList = try {
            val obj = JSONObject(responseJson)
            val data = obj.getJSONArray("data")
            (0 until data.length()).map { parseMangaListItem(data.getJSONObject(it)) }
        } catch (_: Exception) {
            val arr = JSONArray(responseJson)
            (0 until arr.length()).map { parseMangaListItem(arr.getJSONObject(it)) }
        }

        return mangaList.filterNot { it.blocked }.map { item ->
            Manga(
                id = generateUid(item.id.toString()),
                title = item.title,
                url = "/manga/${item.id}",
                publicUrl = "/manga/${item.id}".toAbsoluteUrl(domain),
                coverUrl = item.coverUrl?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain),
                authors = setOfNotNull(item.authors),
                tags = item.genres.mapToSet { genre -> MangaTag(genre.name, genre.id.toString(), source) },
                source = source,
                contentRating = ContentRating.ADULT,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                state = null
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val mangaId = manga.url.substringAfterLast('/')
        val detailsDeferred = async {
            val apiUrl = "/api/manga/$mangaId".toAbsoluteUrl(domain)
            val responseJson = webClient.httpGet(apiUrl).body!!.string()
            parseMangaDetails(JSONObject(responseJson))
        }
        val chaptersDeferred = async { fetchChaptersFromApi(mangaId) }

        val details = detailsDeferred.await()
        val chapters = chaptersDeferred.await()

        manga.copy(
            coverUrl = details.coverUrl?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain),
            altTitles = details.alternativeTitles.toSet(),
            authors = details.authors.mapToSet { it.name },
            description = details.description.orEmpty(),
            tags = details.genres.mapToSet { genre -> MangaTag(genre.name, genre.id.toString(), source) },
            chapters = chapters.map { it.copy(scanlator = details.uploader?.name) }
        )
    }

    private suspend fun fetchChaptersFromApi(mangaId: String): List<MangaChapter> {
        val apiUrl = "/api/manga/$mangaId/chapters".toAbsoluteUrl(domain)
        return try {
            val responseJson = webClient.httpGet(apiUrl).body!!.string()
            val arr = JSONArray(responseJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MangaChapter(
                    id = generateUid(obj.getInt("id").toString()),
                    title = obj.getString("title"),
                    number = obj.getInt("readOrder").toFloat(),
                    url = "/chapter/${obj.getInt("id")}",
                    uploadDate = parseDate(obj.optString("createdAt")) ?: 0L,
                    source = source,
                    scanlator = null,
                    volume = 0,
                    branch = null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast('/')
        val apiUrl = "/api/chapter/$chapterId".toAbsoluteUrl(domain)
        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val obj = JSONObject(responseJson)
        val pages = obj.getJSONArray("pages")

        return (0 until pages.length()).mapNotNull { i ->
            val imageUrl = pages.optString(i).takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl.toAbsoluteUrl(domain),
                source = source,
                preview = null
            )
        }
    }

    private var tagCache: ArrayMap<String, MangaTag>? = null
    private val mutex = Mutex()

    private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
        tagCache?.let { return@withLock it }
        val apiUrl = "/api/tag/genre".toAbsoluteUrl(domain)

        val responseJson = webClient.httpGet(apiUrl).body!!.string()
        val arr = JSONArray(responseJson)

        val tagMap = ArrayMap<String, MangaTag>()
        for (i in 0 until arr.length()) {
            val genre = arr.getJSONObject(i)
            val name = genre.getString("name")
            val id = genre.getInt("id").toString()
            tagMap[name] = MangaTag(title = name, key = id, source = source)
        }
        tagCache = tagMap
        return@withLock tagMap
    }

    // --- JSON PARSERS ---

    private fun parseMangaListItem(obj: JSONObject): MangaListItem {
        val genres = obj.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { parseGenreItem(arr.getJSONObject(it)) }
        } ?: emptyList()
        return MangaListItem(
            id = obj.getInt("id"),
            title = obj.getString("title"),
            coverUrl = obj.optString("coverUrl", null),
            authors = obj.optString("authors", null),
            genres = genres,
            blocked = obj.optBoolean("blocked", false)
        )
    }

    private fun parseMangaDetails(obj: JSONObject): MangaDetails {
        val altTitles = obj.optJSONArray("alternativeTitles")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val authors = obj.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).map { parseAuthorItem(arr.getJSONObject(it)) }
        } ?: emptyList()
        val genres = obj.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { parseGenreItem(arr.getJSONObject(it)) }
        } ?: emptyList()
        val uploader = obj.optJSONObject("uploader")?.let {
            Uploader(it.getInt("id"), it.getString("name"))
        }
        return MangaDetails(
            id = obj.getInt("id"),
            title = obj.getString("title"),
            alternativeTitles = altTitles,
            coverUrl = obj.optString("coverUrl", null),
            description = obj.optString("description", null),
            authors = authors,
            genres = genres,
            uploader = uploader
        )
    }

    private fun parseGenreItem(obj: JSONObject) = GenreItem(obj.getInt("id"), obj.getString("name"))
    private fun parseAuthorItem(obj: JSONObject) = AuthorItem(obj.getInt("id"), obj.getString("name"))

    // --- DATA CLASSES ---
    private data class MangaListItem(val id: Int, val title: String, val coverUrl: String?, val authors: String? = null, val genres: List<GenreItem> = emptyList(), val blocked: Boolean = false)
    private data class GenreItem(val id: Int, val name: String)
    private data class AuthorItem(val id: Int, val name: String)
    private data class Uploader(val id: Int, val name: String)
    private data class MangaDetails(val id: Int, val title: String, val alternativeTitles: List<String> = emptyList(), val coverUrl: String?, val description: String?, val authors: List<AuthorItem> = emptyList(), val genres: List<GenreItem> = emptyList(), val uploader: Uploader? = null)

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr == null) return null
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return try {
            sdf.parse(dateStr)?.time
        } catch (_: ParseException) {
            try {
                val simplerSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                simplerSdf.parse(dateStr)?.time
            } catch (_: ParseException) { null }
        }
    }
}
