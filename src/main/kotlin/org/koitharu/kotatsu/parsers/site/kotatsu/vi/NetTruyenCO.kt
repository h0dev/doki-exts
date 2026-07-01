package org.koitharu.kotatsu.parsers.site.wpcomics.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.wpcomics.WpComicsParser

@MangaSourceParser("NETTRUYENCO", "NetTruyenCO", "vi")
internal class NetTruyenCO(context: MangaLoaderContext) :
	WpComicsParser(context, MangaParserSource.NETTRUYENCO, "nhattruyenqq.com", 21)
