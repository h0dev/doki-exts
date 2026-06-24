package org.koitharu.kotatsu.parsers.site.madara.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("TRUYENTRANH88", "TruyenTranh88", "vi")
internal class TruyenTranh88(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TRUYENTRANH88, "truyentranh88.com", 20) {
	override val datePattern = "dd/MM/yyyy"
}
