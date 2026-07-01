package org.koitharu.kotatsu.parsers.site.tachiyomi.vi.moetruyen

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("MOETRUYEN", "MoeTruyen", "vi")
internal class MoeTruyen(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MOETRUYEN, 24) {

	override val configKeyDomain = ConfigKey.Domain("moetruyen.net")
	private val baseUrl get() = "https://$domain"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
	)

	private val imgxGrants = ConcurrentHashMap<String, ImgxGrant>()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// ============================== List ===============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		if (!query.isNullOrBlank()) {
			return searchManga(query, page)
		}
		if (order == SortOrder.POPULARITY && page == 1) {
			val popular = getPopularManga()
			if (popular.isNotEmpty()) {
				return popular
			}
		}
		return getMangaListing(page, order, filter)
	}

	private suspend fun getPopularManga(): List<Manga> {
		val doc = webClient.httpGet(baseUrl).parseHtml()
		return doc.select("ol.homepage-ranking-list[data-ranking-period=total] a.homepage-ranking-item__link")
			.mapNotNull { element ->
				val href = element.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				val title = element.selectFirst("h3.homepage-ranking-item__title")?.text()
					?: element.ownText().nullIfEmpty()
					?: return@mapNotNull null
				val coverUrl = element.selectFirst("img")?.src()
				Manga(
					id = generateUid(href),
					title = title,
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = coverUrl,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
	}

	private suspend fun getMangaListing(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("$baseUrl/manga?page=$page")
			append("&sort=")
			append(MoeTruyenFilters.sortOrderToValue(order))
			if (filter.states.isNotEmpty()) {
				filter.states.oneOrThrowIfMany()?.let { state ->
					append("&status=")
					append(MoeTruyenFilters.stateToValue(state))
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("article.manga-card--list").map { element ->
			mangaFromListing(element)
		}
	}

	private suspend fun searchManga(query: String, page: Int): List<Manga> {
		val url = "$baseUrl/manga?page=$page&q=${query.urlEncoded()}"
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("article.manga-card--list").mapNotNull { element ->
			mangaFromListing(element)
		}
	}

	private fun mangaFromListing(element: Element): Manga {
		val link = element.selectFirst("a.manga-card--list-link")
			?: element.selectFirst("a[href*=/manga/]")
			?: element.parent()?.selectFirst("a[href*=/manga/]")
			?: element
		val href = link.attrAsRelativeUrlOrNull("href") ?: ""
		val title = link.text().nullIfEmpty()
			?: element.selectFirst("h3, h2, .manga-card--list-title")?.text()
			?: ""
		val coverUrl = element.selectFirst("img.manga-card--list-img, img[src*=/uploads/]")
			?.src()
		return Manga(
			id = generateUid(href),
			title = title,
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	// ============================== Details ===============================

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirst("h1.manga-detail-title")?.text() ?: manga.title
		val coverUrl = doc.selectFirst(".detail-cover img")?.src()
		val author = doc.select("p.manga-detail-meta-line")
			.find { it.text().contains("Tác giả", ignoreCase = true) }
			?.text()?.substringAfter(":")?.trim()
			?.nullIfEmpty()
		val statusText = doc.selectFirst(".manga-status-pill")?.text()?.trim()
		val state = parseStatus(statusText)
		val description = doc.selectFirst("[data-description-content]")
			?.text()
			?: doc.selectFirst(".manga-description__text")
				?.text()

		val tags = doc.select(".manga-detail-genre-chips a.chip, .manga-detail-genre-chips a[href*=/genre/]")
			.mapNotNull { element ->
				val name = element.text().trim().nullIfEmpty() ?: return@mapNotNull null
				MangaTag(
					key = name.lowercase(Locale.ROOT),
					title = name.toTitleCase(sourceLocale),
					source = source,
				)
			}.toSet()

		val chapters = fetchChapters(doc)

		return manga.copy(
			title = title,
			altTitles = emptySet(),
			coverUrl = coverUrl,
			tags = tags,
			authors = if (author != null) setOf(author) else emptySet(),
			description = description,
			state = state,
			chapters = chapters,
		)
	}

	private fun parseStatus(statusText: String?): MangaState? = when {
		statusText == null -> null
		statusText.contains("Còn tiếp", ignoreCase = true) -> MangaState.ONGOING
		statusText.contains("Hoàn thành", ignoreCase = true) -> MangaState.FINISHED
		statusText.contains("Tạm dừng", ignoreCase = true) -> MangaState.PAUSED
		else -> null
	}

	// ============================== Chapters ==============================

	private suspend fun fetchChapters(doc: Document): List<MangaChapter> {
		val chapters = mutableListOf<MangaChapter>()
		chapters.addAll(parseChapterPage(doc))

		// Paginate chapters
		var currentDoc = doc
		while (true) {
			val nextLink = currentDoc.selectFirst(
				"nav[aria-label*='Phân trang chương'] a[aria-label='Trang chương sau']:not(.is-disabled)",
			) ?: break
			val nextUrl = nextLink.attrAsAbsoluteUrlOrNull("href") ?: break
			currentDoc = webClient.httpGet(nextUrl).parseHtml()
			chapters.addAll(parseChapterPage(currentDoc))
		}

		return chapters
	}

	private fun parseChapterPage(doc: Document): List<MangaChapter> {
		val numberRegex = Regex("""[\d.]+""")
		return doc.select("ul.chapter-list li.chapter a.chapter-link").mapNotNull { element ->
			val href = element.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val name = element.selectFirst(".chapter-num")?.text()?.trim()
				?: element.text().trim()
				?: return@mapNotNull null
			val number = numberRegex.find(name)?.value?.toFloatOrNull() ?: 0f
			val dateText = element.selectFirst(".chapter-time")?.text()?.trim()
			val uploadDate = parseRelativeDate(dateText, element)

			MangaChapter(
				id = generateUid(href),
				title = name,
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}
	}

	private fun parseRelativeDate(text: String?, element: Element): Long {
		if (text == null) return 0L
		// Try absolute date from title attribute first
		val titleAttr = element.selectFirst(".chapter-time")?.attr("title")?.nullIfEmpty()
		if (titleAttr != null) {
			val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
				timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
			}
			formatter.parseSafe(titleAttr).let { if (it != 0L) return it }
		}

		val now = System.currentTimeMillis()
		val regex = Regex("""(\d+)\s+(giây|phút|giờ|ngày|tuần|tháng|năm)\s+trước""")
		val match = regex.find(text) ?: return now
		val amount = match.groupValues[1].toLongOrNull() ?: return now
		val unit = match.groupValues[2]
		val millis = when (unit) {
			"giây" -> amount * 1000L
			"phút" -> amount * 60_000L
			"giờ" -> amount * 3_600_000L
			"ngày" -> amount * 86_400_000L
			"tuần" -> amount * 604_800_000L
			"tháng" -> amount * 2_592_000_000L
			"năm" -> amount * 31_536_000_000L
			else -> return now
		}
		return now - millis
	}

	// ============================== Pages =================================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()

		// Try direct image mode first
		val directImages = doc.select("img.page-media").mapNotNull { img ->
			img.src()
		}
		if (directImages.isNotEmpty()) {
			return directImages.map { url ->
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		// Fall back to IMGX grant mode
		val accessUrl = extractImgxAccessUrl(doc) ?: return emptyList()
		return fetchImgxPages(accessUrl, chapterUrl)
	}

	private fun extractImgxAccessUrl(doc: Document): String? {
		// Check for data-imgx-access-url on first img or reader container
		doc.selectFirst("img[data-imgx-access-url]")?.let {
			return it.attr("data-imgx-access-url")
		}
		doc.selectFirst("[data-reader-lazy-pages]")?.let {
			return it.attr("data-imgx-access-url").nullIfEmpty()
		}
		return null
	}

	private suspend fun fetchImgxPages(accessUrl: String, chapterUrl: String): List<MangaPage> {
		val accessHttpUrl = accessUrl.toHttpUrl()
		val pageCount = 50 // fetch up to 50 pages at once
		val pageIndexes = (0 until pageCount).toList()
		val token = "default" // token from page context or meta

		val proofVersion = "imgx-page-access-proof-v1"
		val issuedAt = System.currentTimeMillis()
		val nonce = randomHex(16)
		val accessPath = accessHttpUrl.encodedPath
		val pageIndexPart = pageIndexes.joinToString(",")
		val proofInput = listOf(proofVersion, token, accessPath, "", pageIndexPart, issuedAt.toString(), nonce.lowercase())
			.joinToString("\n")
		val proofHash = MessageDigest.getInstance("SHA-256")
			.digest(proofInput.toByteArray(Charsets.UTF_8))
		val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(proofHash)

		val body = JSONObject().apply {
			put("pageIndexes", JSONArray(pageIndexes))
			put("pageAccessProof", JSONObject().apply {
				put("proof", proof)
				put("version", proofVersion)
				put("token", token)
				put("issuedAt", issuedAt)
				put("nonce", nonce.lowercase())
			})
		}

		val headers = Headers.Builder()
			.add("Referer", chapterUrl)
			.build()

		val response = webClient.httpPost(accessUrl.toHttpUrl(), body, headers)
		val json = response.parseJson()

		val pagesArr = json.optJSONArray("pages") ?: return emptyList()
		val pages = mutableListOf<MangaPage>()

		for (i in 0 until pagesArr.length()) {
			val pageObj = pagesArr.optJSONObject(i) ?: continue
			val downloadUrl = pageObj.optString("downloadUrl", null) ?: continue
			val grant = pageObj.optString("grant", null) ?: continue
			val storageKey = pageObj.optString("storageKey", null) ?: continue

			imgxGrants[downloadUrl] = ImgxGrant(grant, storageKey)

			pages.add(
				MangaPage(
					id = generateUid(downloadUrl),
					url = downloadUrl,
					preview = null,
					source = source,
				),
			)
		}

		return pages
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)

		val grant = imgxGrants[request.url.toString()] ?: return response
		val bodyBytes = response.body?.bytes() ?: return response

		if (bodyBytes.size <= 13) {
			return response
		}
		val byte0 = bodyBytes[0].toInt() and 0xFF
		val byte1 = bodyBytes[1].toInt() and 0xFF
		val byte2 = bodyBytes[2].toInt() and 0xFF
		val byte3 = bodyBytes[3].toInt() and 0xFF
		if (byte0 != 0x49 || byte1 != 0x4D || byte2 != 0x47 || byte3 != 0x58) {
			return response
		}

		val decrypted = ImageDecryptor.decrypt(bodyBytes, grant.grant, grant.storageKey)
		val mediaType = response.body?.contentType()
		response.close()
		return response.newBuilder()
			.body(decrypted.toResponseBody(mediaType))
			.build()
	}

	// ============================== Helpers ===============================

	private fun randomHex(length: Int): String {
		val random = SecureRandom()
		val bytes = ByteArray(length / 2)
		random.nextBytes(bytes)
		return bytes.joinToString("") { "%02x".format(it) }
	}

	private data class ImgxGrant(val grant: String, val storageKey: String)
}
