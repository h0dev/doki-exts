package tsuki.site.madara.vi

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser

@MangaSourceParser("VIETCOMIC", "VietComic", "vi")
internal class VietComic(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.VIETCOMIC, "vietcomic.net", 20) {
	override val datePattern = "dd/MM/yyyy"
}
