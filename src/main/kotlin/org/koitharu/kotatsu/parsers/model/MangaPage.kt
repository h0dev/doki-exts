package org.koitharu.kotatsu.parsers.model

import org.koitharu.kotatsu.parsers.MangaParser

public data class MangaPage(
	@JvmField public val id: Long,
	@JvmField public val url: String,
	@JvmField public val preview: String?,
	@JvmField public val source: MangaSource,
)
