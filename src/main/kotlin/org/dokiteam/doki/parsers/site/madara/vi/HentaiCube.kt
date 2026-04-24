package org.dokiteam.doki.parsers.site.madara.vi

import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaParserSource
import org.dokiteam.doki.parsers.exception.ParseException
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.site.madara.MadaraParser
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.suspendlazy.getOrNull
import org.dokiteam.doki.parsers.util.suspendlazy.suspendLazy
import org.dokiteam.doki.parsers.util.PreferencesHelper
import okhttp3.HttpUrl.Companion.toHttpUrl

@MangaSourceParser("HENTAICUBE", "CBHentai", "vi", ContentType.HENTAI)
internal class HentaiCube(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.HENTAICUBE, "hentaicube.xyz"),
    ConfigurableSource {

    override val datePattern = "dd/MM/yyyy"
    override val postReq = true
    override val authorSearchSupported = true
    override val postDataReq = "action=manga_views&manga="

    // Tachiyomi: filterNonMangaItems = false
    override val filterNonMangaItems = false

    // Tachiyomi: mangaSubString = "read"
    override val mangaSubString = "read"

    // Tachiyomi: altNameSelector
    override val altNameSelector = ".post-content_item:contains(Tên khác) .summary-content"

    // Tachiyomi: URL search prefix
    private val URL_SEARCH_PREFIX = "url:"

    // Tachiyomi: Regex patterns
    private val thumbnailOriginalUrlRegex = Regex("-\\d+x\\d+(\\.[a-zA-Z]+)$")
    private val oldMangaUrlRegex by lazy { Regex("^https?://$domain/\\w+/") }
    private val domainRegex = Regex("""^https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9]{1,6}$""")

    // Preferences cho dynamic domain
    private val preferences: PreferencesHelper = PreferencesHelper(context, source)
    private var _cachedBaseUrl: String? = null

    // Support dynamic domain từ Tachiyomi
    override val baseUrl: String
        get() = _cachedBaseUrl ?: preferences.getString(BASE_URL_PREF, "https://$domain") ?: "https://$domain"

    // Session manager để xử lý redirect
    private var sessionDomain: String = domain

    init {
        // Khởi tạo preferences với domain mặc định
        if (!preferences.contains(BASE_URL_PREF)) {
            preferences.putString(BASE_URL_PREF, "https://$domain")
        }
        
        // Lưu domain gốc
        if (!preferences.contains(DEFAULT_BASE_URL_PREF)) {
            preferences.putString(DEFAULT_BASE_URL_PREF, "https://$domain")
        }
        
        _cachedBaseUrl = preferences.getString(BASE_URL_PREF, "https://$domain")
    }

    private val availableTags = suspendLazy(initializer = ::fetchTags)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags.get(),
    )

    // Tachiyomi: Xử lý URL search prefix và special characters
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Xử lý URL search prefix (giống Tachiyomi)
        if (filter.query?.startsWith(URL_SEARCH_PREFIX) == true) {
            val mangaSlug = filter.query.substringAfter(URL_SEARCH_PREFIX)
            val mangaUrl = "$baseUrl/$mangaSubString/$mangaSlug/"
            val doc = webClient.httpGet(mangaUrl).parseHtml()
            val manga = parseMangaDetails(doc, mangaUrl)
            return listOf(manga)
        }

        // Xử lý special characters như Tachiyomi
        val queryFixed = filter.query
            ?.replace("–", "-")
            ?.replace("’", "'")
            ?.replace("“", "\"")
            ?.replace("”", "\"")
            ?.replace("…", "...")

        val updatedFilter = MangaListFilter(
            query = queryFixed,
            tags = filter.tags,
            states = filter.states,
            contentRating = filter.contentRating,
            year = filter.year,
            author = filter.author,
            includeOperator = filter.includeOperator,
            excludeOperator = filter.excludeOperator
        )

        return super.getListPage(page, order, updatedFilter)
    }

    // Tachiyomi: Override popularMangaFromElement để xử lý thumbnail
    override suspend fun parseMangaList(document: Document): List<Manga> {
        val mangas = super.parseMangaList(document)
        return mangas.map { manga ->
            manga.copy(
                thumbnailUrl = manga.thumbnailUrl?.replace(thumbnailOriginalUrlRegex, "$1")
            )
        }
    }

    // Tachiyomi: Fix old manga URLs
    override fun getMangaUrl(manga: Manga): String {
        val url = super.getMangaUrl(manga)
        return url.replace(oldMangaUrlRegex, "$baseUrl/$mangaSubString/")
    }

    // Tachiyomi: Distinct pages
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val root = doc.body().selectFirst("div.main-col-inner")?.selectFirst("div.reading-content")
            ?: throw ParseException("Root not found", fullUrl)
        return root.select("img").map { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }.distinctBy { it.url } // Tachiyomi: distinctBy imageUrl
    }

    override suspend fun createMangaTag(a: Element): MangaTag? {
        val allTags = availableTags.getOrNull().orEmpty()
        val title = a.text().replace(Regex("\\(\\d+\\)"), "").trim()
        return allTags.find {
            it.title.trim().equals(title, ignoreCase = true)
        }
    }

    // Tachiyomi: Fetch tags với xử lý dynamic domain
    private suspend fun fetchTags(): Set<MangaTag> {
        val url = "${baseUrl}/the-loai-genres"
        val doc = webClient.httpGet(url).parseHtml()
        val elements = doc.select("ul.list-unstyled li a")
        return elements.mapToSet { element ->
            val href = element.attr("href")
            val key = href.substringAfter("/theloai/").removeSuffix("/")
            val title = element.text().replace(Regex("\\(\\d+\\)"), "").trim()
            MangaTag(
                key = key,
                title = title,
                source = source,
            )
        }.toSet()
    }

    // Tachiyomi: Xử lý redirect và cập nhật domain
    suspend fun handleRedirect(url: String): String {
        var currentUrl = url
        var redirectCount = 0
        val maxRedirects = 5

        while (redirectCount < maxRedirects) {
            val response = webClient.httpGet(currentUrl, followRedirects = false)
            
            if (response.isRedirect()) {
                val newUrl = response.header("Location") ?: break
                val newUrlHttp = newUrl.toHttpUrl()
                val redirectedDomain = "${newUrlHttp.scheme}://${newUrlHttp.host}"
                
                if (redirectedDomain != baseUrl) {
                    _cachedBaseUrl = redirectedDomain
                    preferences.putString(BASE_URL_PREF, redirectedDomain)
                }
                
                currentUrl = newUrl
                redirectCount++
            } else {
                break
            }
        }
        
        if (redirectCount >= maxRedirects) {
            throw ParseException("Too many redirects: $maxRedirects", url)
        }
        
        return currentUrl
    }

    // Tachiyomi: Setup preferences cho dynamic domain
    override fun setupPreferences(): List<Preference> {
        return listOf(
            Preference.EditTextPreference(
                key = BASE_URL_PREF,
                title = "Ghi đè URL cơ sở",
                summary = "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt.\n" +
                    "Để trống để sử dụng URL mặc định.\n" +
                    "Hiện tại sử dụng: $baseUrl",
                defaultValue = "https://$domain",
                dialogTitle = "Ghi đè URL cơ sở",
                dialogMessage = "Mặc định: https://$domain",
                validator = { str ->
                    if (str.isBlank()) true
                    else runCatching { str.toHttpUrl() }.isSuccess && 
                         domainRegex.matchEntire(str) != null
                },
                onPreferenceChanged = { newValue ->
                    _cachedBaseUrl = newValue.ifBlank { null }
                    preferences.putString(BASE_URL_PREF, newValue)
                    true
                }
            )
        )
    }

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
    }
}
