package tsuki.site.wpcomics.vi

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.wpcomics.WpComicsParser

@MangaSourceParser("NETTRUYENCO", "NetTruyenCO", "vi")
internal class NetTruyenCO(context: MangaLoaderContext) :
	WpComicsParser(context, MangaParserSource.NETTRUYENCO, "nhattruyenqq.com", 21)
