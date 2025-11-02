package org.dokiteam.doki.parsers.site.vi

import kotlinx.coroutines.runBlocking // Bị xóa vì không còn dùng trong intercept
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.dokiteam.doki.parsers.MangaLoaderContext
import org.dokiteam.doki.parsers.MangaSourceParser
import org.dokiteam.doki.parsers.bitmap.Bitmap // Bị xóa
import org.dokiteam.doki.parsers.bitmap.Rect // Bị xóa
import org.dokiteam.doki.parsers.config.ConfigKey
import org.dokiteam.doki.parsers.core.PagedMangaParser
import org.dokiteam.doki.parsers.network.UserAgents
import org.dokiteam.doki.parsers.model.*
import org.dokiteam.doki.parsers.util.*
import org.dokiteam.doki.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI // Bị xóa

@MangaSourceParser("MIMIHENTAI", "MimiHentai", "vi", type = ContentType.HENTAI)
internal class MimiHentai(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MIMIHENTAI, 18) {

	private val apiSuffix = "api/v2/manga"
	override val configKeyDomain = ConfigKey.Domain("mimihentai.com", "hentaihvn.com")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

	// Giữ nguyên phần còn lại của file từ đây...

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon(
					"https://raw.githubusercontent.com/dragonx943/plugin-sdk/refs/heads/sources/mimihentai/app/src/main/ic_launcher-playstore.png",
					512,
					null),
			),
			domain,
		)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isAuthorSearchSupported = true,
			isTagsExclusionSupported = true,
		)

	init {
		setFirstPage(0)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(availableTags = fetchTags())

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append("$domain/$apiSuffix")

			if (!filter.query.isNullOrEmpty() ||
				!filter.author.isNullOrEmpty() ||
				filter.tags.isNotEmpty()
			) {
				append("/advance-search?page=")
				append(page)
				append("&max=18") // page size, avoid rate limit

				if (!filter.query.isNullOrEmpty()) {
					append("&name=")
					append(filter.query.urlEncoded())
				}

				if (!filter.author.isNullOrEmpty()) {
					append("&author=")
					append(filter.author.urlEncoded())
				}

				if (filter.tags.isNotEmpty()) {
					append("&genre=")
					append(filter.tags.joinToString(",") { it.key })
				}

				if (filter.tagsExclude.isNotEmpty()) {
					append("&ex=")
					append(filter.tagsExclude.joinToString(",") { it.key })
				}

				append("&sort=")
				append(
					when (order) {
						SortOrder.UPDATED -> "updated_at"
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.POPULARITY -> "follows"
						SortOrder.POPULARITY_TODAY,
						SortOrder.POPULARITY_WEEK,
						SortOrder.POPULARITY_MONTH -> "views"
						SortOrder.RATING -> "likes"
						else -> ""
					}
				)
			}

			else {
				append(
					when (order) {
						SortOrder.UPDATED -> "/tatcatruyen?page=$page&sort=updated_at"
						SortOrder.ALPHABETICAL -> "/tatcatruyen?page=$page&sort=title"
						SortOrder.POPULARITY -> "/tatcatruyen?page=$page&sort=follows"
						SortOrder.POPULARITY_TODAY -> "/tatcatruyen?page=$page&sort=views"
						SortOrder.POPULARITY_WEEK -> "/top-manga?page=$page&timeType=1&limit=18"
						SortOrder.POPULARITY_MONTH -> "/top-manga?page=$page&timeType=2&limit=18"
						SortOrder.RATING -> "/tatcatruyen?page=$page&sort=likes"
						else -> "/tatcatruyen?page=$page&sort=updated_at" // default
					}
				)

				if (filter.tagsExclude.isNotEmpty()) {
					append("&ex=")
					append(filter.tagsExclude.joinToString(",") { it.key })
				}
			}
		}

		val raw = webClient.httpGet(url)
		return if (url.contains("/top-manga")) {
			val data = raw.parseJsonArray()
			parseTopMangaList(data)
		} else {
			val data = raw.parseJson().getJSONArray("data")
			parseMangaList(data)
		}
	}

	private fun parseTopMangaList(data: JSONArray): List<Manga> {
		return data.mapJSON { jo ->
			val id = jo.getLong("id")
			val title = jo.getString("title").takeIf { it.isNotEmpty() } ?: "Web chưa đặt tên"
			val description = jo.getStringOrNull("description")

			val differentNames = mutableSetOf<String>().apply {
				jo.optJSONArray("differentNames")?.let { namesArray ->
					for (i in 0 until namesArray.length()) {
						namesArray.optString(i)?.takeIf { it.isNotEmpty() }?.let { name ->
							add(name)
						}
					}
				}
			}

			val authors = jo.optJSONArray("authors")?.mapJSON {
				it.getString("name")
			}?.toSet() ?: emptySet()

			val tags = jo.optJSONArray("genres")?.mapJSON { genre ->
				MangaTag(
					key = genre.getLong("id").toString(),
					title = genre.getString("name"),
					source = source
				)
			}?.toSet() ?: emptySet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = differentNames,
				url = "/$apiSuffix/info/$id",
				publicUrl = "https://$domain/g/$id",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = jo.getString("coverUrl"),
				state = null,
				description = description,
				tags = tags,
				authors = authors,
				source = source,
			)
		}
	}

	private fun parseMangaList(data: JSONArray): List<Manga> {
		return data.mapJSON { jo ->
			val id = jo.getLong("id")
			val title = jo.getString("title").takeIf { it.isNotEmpty() } ?: "Web chưa đặt tên"
			val description = jo.getStringOrNull("description")

			val differentNames = mutableSetOf<String>().apply {
				jo.optJSONArray("differentNames")?.let { namesArray ->
					for (i in 0 until namesArray.length()) {
						namesArray.optString(i)?.takeIf { it.isNotEmpty() }?.let { name ->
							add(name)
						}
					}
				}
			}

			val authors = jo.getJSONArray("authors").mapJSON {
				it.getString("name")
			}.toSet()

			val tags = jo.getJSONArray("genres").mapJSON { genre ->
				MangaTag(
					key = genre.getLong("id").toString(),
					title = genre.getString("name"),
					source = source
				)
			}.toSet()

			Manga(
				id = generateUid(id),
				title = title,
				altTitles = differentNames,
				url = "/$apiSuffix/info/$id",
				publicUrl = "https://$domain/g/$id",
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = jo.getString("coverUrl"),
				state = null,
				tags = tags,
				description = description,
				authors = authors,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val json = webClient.httpGet(url).parseJson()
		val id = json.getLong("id")
		val description = json.getStringOrNull("description")
		val uploaderName = json.getJSONObject("uploader").getString("displayName")

		val tags = json.getJSONArray("genres").mapJSONToSet { jo ->
			MangaTag(
				title = jo.getString("name").toTitleCase(sourceLocale),
				key = jo.getLong("id").toString(),
				source = source,
			)
		}

		val urlChaps = "https://$domain/$apiSuffix/gallery/$id"
		val parsedChapters = webClient.httpGet(urlChaps).parseJsonArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US)
		val chapters = parsedChapters.mapJSON { jo ->
			MangaChapter(
				id = generateUid(jo.getLong("id")),
				title = jo.getStringOrNull("title"),
				number = jo.getFloatOrDefault("order", 0f),
				url = "/$apiSuffix/chapter?id=${jo.getLong("id")}",
				uploadDate = dateFormat.parse(jo.getString("createdAt"))?.time ?: 0L,
				source = source,
				scanlator = uploaderName,
				branch = null,
				volume = 0,
			)
		}.reversed()

		return manga.copy(
			tags = tags,
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseJson()
		return json.getJSONArray("pages").mapJSON { jo ->
			val imageUrl = jo.getString("imageUrl")
			val gt = jo.getStringOrNull("drm")
			
			// Vẫn tạo URL "giả" chứa marker. Hàm intercept sẽ bắt URL này.
			val finalUrl = if (gt != null) {
				"$imageUrl/$DRM_MARKER/$gt"
			} else {
				imageUrl
			}

			MangaPage(
				id = generateUid(imageUrl),
				url = finalUrl,
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * [CHỈNH SỬA]
	 * Intercept các request ảnh.
	 * Nếu URL chứa DRM_MARKER, thay vì tải ảnh gốc và giải mã,
	 * chúng ta gọi đến proxy descrambler API.
	 */
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url

		val pathSegments = url.pathSegments
		// Sửa lỗi typo: DRM_MARKTER -> DRM_MARKER
		val markerIndex = pathSegments.indexOf(DRM_MARKER)

		if (markerIndex == -1 || markerIndex + 1 >= pathSegments.size) {
			// Không phải ảnh DRM, cho request đi tiếp
			return chain.proceed(request)
		}
		
		// Trích xuất drmString (gt)
		val gt = pathSegments[markerIndex + 1]

		// Trích xuất URL ảnh gốc
		val originalUrl = url.newBuilder().apply {
			removePathSegment(pathSegments.size - 1) // Xóa gt
			removePathSegment(pathSegments.size - 2) // Xóa DRM_MARKER
		}.build()

		// --- LOGIC MỚI: Gọi proxy ---

		val proxyEndpoint = "https://mdimg.hdev.it.eu.org/descramble"
		
		// 1. Tạo JSON body cho proxy
		val jsonBody = """
		{
		  "imageUrl": "${originalUrl.toString()}",
		  "drmString": "$gt"
		}
		""".trimIndent()

		// 2. Tạo RequestBody
		val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

		// 3. Xây dựng request POST mới đến proxy
		val proxyRequest = request.newBuilder()
			.url(proxyEndpoint)
			.post(requestBody)
			.header("Content-Type", "application/json")
			// Giữ lại User-Agent gốc phòng trường hợp proxy cần
			.header("User-Agent", request.header("User-Agent") ?: userAgent)
			.build()

		// 4. Thực thi request đến proxy và trả về kết quả
		// Phản hồi này đã là dữ liệu ảnh đã được giải mã
		return chain.proceed(proxyRequest)

		// --- LOGIC CŨ (đã bị xóa) ---
		// val newRequest = request.newBuilder().url(originalUrl).build()
		// val response = chain.proceed(newRequest)
		//
		// return context.redrawImageResponse(response) { bitmap ->
		// 	runBlocking {
		// 		extractMetadata(bitmap, gt)
		// 	}
		// }
	}

	// [ĐÃ XÓA] Toàn bộ các hàm private extractMetadata, decodeGt, getKeyByStrategy, 
	// getFixedEncryptionKey, hexToBytes đã được xóa vì không còn sử dụng.

	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "https://$domain/$apiSuffix/genres"
		val response = webClient.httpGet(url).parseJsonArray()
		return response.mapJSONToSet { jo ->
			MangaTag(
				title = jo.getString("name").toTitleCase(sourceLocale),
				key = jo.getLong("id").toString(),
				source = source,
			)
		}
	}

	companion object {
		private const val DRM_MARKER = "mhdrm"
	}
}
