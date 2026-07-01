package org.koitharu.kotatsu.parsers.site.madara.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("VIETCOMIC", "VietComic", "vi")
internal class VietComic(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.VIETCOMIC, "vietcomic.net", 20) {
	override val datePattern = "dd/MM/yyyy"
}
