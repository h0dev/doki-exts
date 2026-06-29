package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("VIHENTAI", "ViHentai", "vi", type = ContentType.HENTAI)
internal class ViHentai(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.VIHENTAI, 24) {

	override val configKeyDomain = ConfigKey.Domain("vi-hentai.moe")

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	// ======================== Password Solving (Interceptor) ========================

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (!request.url.toString().startsWith("https://$domain")) {
			return chain.proceed(request)
		}
		val response = chain.proceed(request)
		val body = response.peekBody(Long.MAX_VALUE).string()
		if (!body.contains("wire:initial-data") || !body.contains("enter-secret")) {
			return response
		}
		response.close()
		return try {
			solvePassword(chain, body)
			chain.proceed(request)
		} catch (_: Exception) {
			chain.proceed(request)
		}
	}

	private fun solvePassword(chain: Interceptor.Chain, html: String) {
		val wireDataStr = WIRE_INITIAL_DATA_REGEX.find(html)?.groupValues?.get(1)
			?.let { Parser.unescapeEntities(it, true) }
			?: throw IllegalStateException("Password: wire:initial-data not found")

		val csrfToken = LIVEWIRE_TOKEN_REGEX.find(html)?.groupValues?.get(1)
			?: throw IllegalStateException("Password: CSRF token not found")

		val password = PASSWORD_REGEX.find(html)?.groupValues?.get(1)
			?: throw IllegalStateException("Password: password not found")

		val wireData = JSONObject(wireDataStr)
		val fingerprint = wireData.getJSONObject("fingerprint")
		val serverMemo = wireData.getJSONObject("serverMemo")

		val livewireHeaders = Headers.Builder()
			.add("Content-Type", "application/json")
			.add("X-CSRF-TOKEN", csrfToken)
			.add("X-Livewire", "true")
			.add("Accept", "text/html, application/xhtml+xml")
			.add("Referer", "https://$domain/")
			.build()

		// Sync request — send password
		val syncPayload = JSONObject().apply {
			put("fingerprint", fingerprint)
			put("serverMemo", serverMemo)
			put("updates", JSONArray().apply {
				put(JSONObject().apply {
					put("type", "syncInput")
					put("payload", JSONObject().apply {
						put("id", "s1")
						put("name", "password")
						put("value", password)
					})
				})
			})
		}

		val syncRequest = Request.Builder()
			.url("https://$domain/livewire/message/enter-secret")
			.post(syncPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
			.headers(livewireHeaders)
			.build()

		val syncResponse = chain.proceed(syncRequest)
		val syncResult = JSONObject(syncResponse.body?.string() ?: "{}")
		syncResponse.close()

		val syncMemo = syncResult.optJSONObject("serverMemo")
		val mergedMemo = mergeServerMemos(serverMemo, syncMemo)

		// Submit request — confirm
		val submitPayload = JSONObject().apply {
			put("fingerprint", fingerprint)
			put("serverMemo", mergedMemo)
			put("updates", JSONArray().apply {
				put(JSONObject().apply {
					put("type", "callMethod")
					put("payload", JSONObject().apply {
						put("id", "c1")
						put("method", "submit")
						put("params", JSONArray())
					})
				})
			})
		}

		val submitRequest = Request.Builder()
			.url("https://$domain/livewire/message/enter-secret")
			.post(submitPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
			.headers(livewireHeaders)
			.build()

		chain.proceed(submitRequest).close()
	}

	private fun mergeServerMemos(original: JSONObject, syncMemo: JSONObject?): JSONObject {
		if (syncMemo == null) return original
		return JSONObject().apply {
			for (key in original.keys()) {
				when {
					key == "data" && syncMemo.has("data") -> {
						val originalData = original.optJSONObject("data") ?: JSONObject()
						val syncData = syncMemo.getJSONObject("data")
						put("data", JSONObject().apply {
							for (k in originalData.keys()) put(k, originalData.get(k))
							for (k in syncData.keys()) put(k, syncData.get(k))
						})
					}
					syncMemo.has(key) -> put(key, syncMemo.get(key))
					else -> put(key, original.get(key))
				}
			}
		}
	}

	// ======================== List ========================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			if (!filter.query.isNullOrEmpty()) {
				append("https://$domain/tim-kiem")
				append("?keyword=${filter.query.urlEncoded()}")
				append("&page=$page")
			} else {
				append("https://$domain/danh-sach")
				append("?page=$page")
				append("&sort=${order.toSortParam()}")

				if (filter.states.isNotEmpty()) {
					filter.states.oneOrThrowIfMany()?.let { state ->
						val status = when (state) {
							MangaState.ONGOING -> "2"
							MangaState.FINISHED -> "1"
							else -> ""
						}
						if (status.isNotEmpty()) {
							append("&filter[status]=$status")
						}
					}
				}

				if (filter.tags.isNotEmpty()) {
					append("&filter[accept_genres]=")
					append(filter.tags.joinToString(",") { it.key })
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.manga-vertical").mapNotNull { element ->
			val linkElement = element.selectFirst("div.p-2 a") ?: return@mapNotNull null
			val absUrl = linkElement.absUrl("href")
			val mangaUrl = absUrl.toRelativeUrl(domain)
			Manga(
				id = generateUid(mangaUrl),
				title = linkElement.text(),
				altTitles = emptySet(),
				url = mangaUrl,
				publicUrl = absUrl,
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = element.selectFirst("div.cover")?.extractBackgroundImage(),
				largeCoverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	private fun SortOrder.toSortParam(): String = when (this) {
		SortOrder.POPULARITY -> "-views"
		SortOrder.NEWEST -> "-created_at"
		SortOrder.UPDATED -> "-updated_at"
		else -> "-updated_at"
	}

	// ======================== Details ========================

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirst("span.grow.text-lg")?.text() ?: manga.title
		val author = doc.selectFirst("a[href*=/tac-gia/]")?.text()
		val tags = doc.select("div.mt-2.flex.flex-wrap.gap-1 a[href*=/the-loai/]").mapNotNullToSet {
			val tagName = it.text()
			if (tagName.isNotEmpty()) {
				MangaTag(
					title = tagName.toTitleCase(sourceLocale),
					key = it.attr("href").substringAfterLast('/').trim(),
					source = source,
				)
			} else null
		}
		val coverUrl = doc.selectFirst("div.cover-frame div.cover, div.cover-frame")?.extractBackgroundImage()
		val description = doc.selectFirst("div.mg-plot")?.select("p")
			?.drop(1)
			?.joinToString("\n") { it.text() }
			?.trim()
			?.takeIf { it.isNotBlank() }
			?: doc.selectFirst("meta[property=og:description]")?.attr("content")
				?.substringBefore(" - Việt Hentai")

		val state = doc.selectFirst("a[href*='filter[status]'] span, a[href*='filter%5Bstatus%5D'] span")
			?.text()
			?.let { statusText ->
				when {
					statusText.contains("Đã hoàn thành") -> MangaState.FINISHED
					statusText.contains("Đang tiến hành") -> MangaState.ONGOING
					else -> null
				}
			}

		val chapters = parseChapters(doc)

		return manga.copy(
			title = title,
			altTitles = emptySet(),
			url = manga.url,
			publicUrl = manga.publicUrl,
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = coverUrl ?: manga.coverUrl,
			largeCoverUrl = null,
			tags = tags,
			state = state,
			authors = setOfNotNull(author),
			description = description,
			chapters = chapters,
			source = source,
		)
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		return doc.select("ul li:has(a[href*=/truyen/])").mapNotNull { row ->
			val chapterElement = row.selectFirst(
				"a[href*=/truyen/]:has(span.text-ellipsis), a[href*=/truyen/]:has(span.truncate)",
			) ?: return@mapNotNull null
			val chapterName = chapterElement.selectFirst("span.text-ellipsis, span.truncate")
				?.text()
				?.trim()
				.orEmpty()
			if (chapterName.isEmpty()) return@mapNotNull null

			val chapterUrl = chapterElement.absUrl("href").toRelativeUrl(domain)
			val dateStr = row.selectFirst("span.timeago[datetime]")?.attr("datetime")

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterName,
				number = -1f,
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = dateStr?.let { parseDate(it) } ?: 0L,
				branch = null,
				source = source,
			)
		}
	}

	private fun parseDate(dateStr: String): Long? {
		return try {
			dateFormat.parse(dateStr)?.time
		} catch (_: Exception) {
			null
		}
	}

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
		}
	}

	// ======================== Pages ========================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val packedScript = doc.select("script").map { it.data() }
			.firstOrNull { it.contains("eval(function(h,u,n,t,e,r)") }
			?: throw Exception("Could not find packed script with image data")

		return ViHentaiPacker.extractImageUrls(packedScript).mapIndexed { index, url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	// ======================== Tags ========================

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("a[href*=/the-loai/]").mapNotNullToSet {
			val title = it.text().trim()
			val key = it.attr("href").substringAfterLast('/').trim()
			if (title.isNotEmpty() && key.isNotEmpty()) {
				MangaTag(title = title.toTitleCase(sourceLocale), key = key, source = source)
			} else null
		}
	}

	// ======================== Utilities ========================

	private fun Element.extractBackgroundImage(): String? {
		val style = attr("style")
		return BACKGROUND_IMAGE_REGEX.find(style)?.groupValues?.get(1)
	}

	companion object {
		private val BACKGROUND_IMAGE_REGEX = Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""")
		private val WIRE_INITIAL_DATA_REGEX = Regex("""wire:initial-data="([^"]+)"""")
		private val LIVEWIRE_TOKEN_REGEX = Regex("""livewire_token\s*=\s*'([^']+)'""")
		private val PASSWORD_REGEX = Regex("""input\.value\s*=\s*'([^']+)'""")
	}
}
