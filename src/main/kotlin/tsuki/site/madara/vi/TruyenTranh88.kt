package tsuki.site.madara.vi

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser

@MangaSourceParser("TRUYENTRANH88", "TruyenTranh88", "vi")
internal class TruyenTranh88(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TRUYENTRANH88, "truyentranh88.com", 20) {
	override val datePattern = "dd/MM/yyyy"
}
