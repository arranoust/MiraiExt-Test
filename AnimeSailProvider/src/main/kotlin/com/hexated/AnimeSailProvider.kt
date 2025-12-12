package com.animesail.optimized

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Optimized AnimeSail provider
 * - Supports many players (kodir2, arch, race, hexupload, pomf, fichan, blogger, framezilla, uservideo, aghanim redirect)
 * - Preserves headers, referer, extractorData, type, quality, and other metadata
 * - Robust error handling and safe parsing
 * - Proper quality detection and Auto quality support
 */
class AnimeSailProviderOptimized : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (compatible; Cloudstream/1.0)"
            ),
            cookies = mapOf("_as_ipin_ct" to "ID"),
            referer = ref
        )
    }

    // Full list of categories (kept reasonably broad but avoid duplicates)
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
        "$mainUrl/genres/donghua/page/" to "Donghua",
        "$mainUrl/genres/action/page/" to "Action",
        "$mainUrl/genres/adventure/page/" to "Adventure",
        "$mainUrl/genres/comedy/page/" to "Comedy",
        "$mainUrl/genres/drama/page/" to "Drama",
        "$mainUrl/genres/ecchi/page/" to "Ecchi",
        "$mainUrl/genres/fantasy/page/" to "Fantasy",
        "$mainUrl/genres/harem/page/" to "Harem",
        "$mainUrl/genres/horror/page/" to "Horror",
        "$mainUrl/genres/isekai/page/" to "Isekai",
        "$mainUrl/genres/mecha/page/" to "Mecha",
        "$mainUrl/genres/mystery/page/" to "Mystery",
        "$mainUrl/genres/romance/page/" to "Romance",
        "$mainUrl/genres/slice-of-life/page/" to "Slice of Life",
        "$mainUrl/genres/sports/page/" to "Sports",
        "$mainUrl/genres/supernatural/page/" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").mapNotNull { it.safeToSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.safeToSearchResult(): AnimeSearchResponse? {
        try {
            val rawHref = this.selectFirst("a")?.attr("href") ?: return null
            val href = getProperAnimeLink(fixUrlNull(rawHref).toString())
            val title = this.select(".tt > h2").text().trim().ifEmpty { return null }
            val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src")) ?: ""
            val epNum = this.selectFirst(".tt > h2")?.text()?.let {
                Regex("Episode\\s?(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            return newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addSub(epNum)
            }
        } catch (e: Exception) {
            // ignore single broken item
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document
        return document.select("div.listupd article").mapNotNull { it.safeToSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")?.text().toString().replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").mapNotNull { episodeElement ->
            try {
                val anchor = episodeElement.selectFirst("a") ?: return@mapNotNull null
                val episodeLink = fixUrl(anchor.attr("href"))
                val episodeName = anchor.text()
                val episodeNumber = Regex("Episode\\s?(\\d+)").find(episodeName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(episodeLink) {
                    this.name = episodeName
                    this.episode = episodeNumber
                }
            } catch (e: Exception) {
                null
            }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags = document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try { request(data).document } catch (e: Exception) { return false }

        // iterate mirrors safely
        document.select(".mobius > .mirror > option").forEach { opt ->
            safeApiCall {
                val em = opt.attr("data-em").ifEmpty { return@safeApiCall }
                val iframeRaw = try { Jsoup.parse(base64Decode(em)).select("iframe").attr("src") } catch (e: Exception) { "" }
                if (iframeRaw.isEmpty()) return@safeApiCall
                val iframe = fixUrl(iframeRaw)
                val quality = getIndexQuality(opt.text())

                when {
                    // kodir2 -> special inline js source
                    iframe.startsWith("$mainUrl/utils/player/kodir2") -> {
                        try {
                            val text = request(iframe, ref = data).text
                            val sourceHtml = text.substringAfter("= `").substringBefore("`;")
                            val src = Jsoup.parse(sourceHtml).select("source").last()?.attr("src") ?: return@safeApiCall
                            callback.invoke(buildExtractorLink("kodir2", src, quality, referer = mainUrl))
                        } catch (e: Exception) {
                            // ignore
                        }
                    }

                    // arch, race, hexupload, pomf -> simple source tags
                    iframe.contains("/arch/") || iframe.contains("/race/") || iframe.contains("/hexupload/") || iframe.contains("/pomf/") -> {
                        try {
                            val doc = request(iframe, ref = data).document
                            val src = doc.select("source").attr("src")
                            val sourceName = when {
                                iframe.contains("/arch/") -> "Arch"
                                iframe.contains("/race/") -> "Race"
                                iframe.contains("/hexupload/") -> "Hexupload"
                                iframe.contains("/pomf/") -> "Pomf"
                                else -> name
                            }
                            callback.invoke(buildExtractorLink(sourceName, src, quality, referer = mainUrl))
                        } catch (e: Exception) { /* ignore */ }
                    }

                    // fichan/blogger -> try to parse iframe contents (some sites embed direct links)
                    iframe.contains("fichan") || iframe.contains("blogger") -> {
                        try {
                            loadFixedExtractor(iframe, quality, data, subtitleCallback, callback)
                        } catch (e: Exception) { /* ignore */ }
                    }

                    // aghanim redirect -> translate to target v service
                    iframe.startsWith("https://aghanim.xyz/tools/redirect/") -> {
                        try {
                            val id = iframe.substringAfter("id=").substringBefore("&token")
                            val link = "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                            loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                        } catch (e: Exception) { /* ignore */ }
                    }

                    // framezilla / uservideo -> iframe inside iframe
                    iframe.contains("framezilla") || iframe.contains("uservideo") -> {
                        try {
                            val inner = request(iframe, ref = data).document.select("iframe").attr("src")
                            if (inner.isNotEmpty()) loadFixedExtractor(fixUrl(inner), quality, data, subtitleCallback, callback)
                        } catch (e: Exception) { /* ignore */ }
                    }

                    // otherwise -> fallback to extractor (supports many hosts: mixdrop, mp4upload, doodstream, streamwish etc if loadExtractor is configured)
                    else -> {
                        loadFixedExtractor(iframe, quality, data, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    // Build a well-formed extractor link preserving metadata
    private fun buildExtractorLink(
        source: String,
        url: String,
        quality: Int?,
        referer: String? = null,
        type: ExtractorLinkType = INFER_TYPE
    ): ExtractorLink {
        return newExtractorLink(
            source = source,
            name = source,
            url = url,
            type = type
        ) {
            this.referer = referer ?: mainUrl
            this.quality = quality ?: Qualities.Unknown.value
        }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            // Ensure callback runs in IO dispatcher and preserve all link fields
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(
                    newExtractorLink(
                        source = link.name,
                        name = link.name,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer ?: referer ?: mainUrl
                        this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else (quality ?: link.quality ?: Qualities.Unknown.value)
                        this.type = link.type
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

}
