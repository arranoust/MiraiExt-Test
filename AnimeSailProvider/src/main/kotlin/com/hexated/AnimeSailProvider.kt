package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app

class AnimeSail : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
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
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
            ),
            cookies = mapOf("_as_ipin_ct" to "ID"),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/rilisan-anime-terbaru/page/" to "Anime Terbaru",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Donghua Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map { it.toSearchResult() }
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

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).orEmpty())
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrl(this.selectFirst("div.limit img")?.attr("src").orEmpty())
        val epNum = Regex("Episode\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document
        return document.select("div.listupd article").map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace("Subtitle Indonesia", "")?.trim().orEmpty()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src").orEmpty()
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").mapNotNull { li ->
            val anchor = li.selectFirst("a") ?: return@mapNotNull null
            val epLink = fixUrl(anchor.attr("href"))
            val epName = anchor.text()
            val epNum = Regex("Episode\\s?(\\d+)").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(epLink) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text().orEmpty()
            tags = document.select("tbody th:contains(Genre)").next().select("a").mapNotNull { it.text() }
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
        val document = request(data).document

        coroutineScope {
            val jobs = document.select(".mobius > .mirror > option").map { element ->
                async {
                    safeApiCall {
                        val iframe = fixUrl(Jsoup.parse(base64Decode(element.attr("data-em"))).select("iframe").attr("src"))
                        val quality = getIndexQuality(element.text())
                        when {
                            iframe.startsWith("$mainUrl/utils/player/") -> {
                                request(iframe, ref = data).document.select("source").attr("src").let { link ->
                                    callback.invoke(newExtractorLink(name, name, link, mainUrl, quality, ExtractorLinkType.VIDEO))
                                }
                            }
                            iframe.contains("krakenfiles.com/embed-video/") -> {
                                loadKrakenFilesExtractor(iframe, subtitleCallback, callback)
                            }
                            iframe.contains("mega.nz/embed") -> {
                                loadMegaExtractor(iframe, subtitleCallback, callback)
                            }
                            iframe.contains("pixel/") -> {
                                loadPixelExtractor(iframe, subtitleCallback, callback)
                            }
                            else -> {
                                loadFixedExtractor(iframe, quality, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        return true
    }

    private suspend fun loadKrakenFilesExtractor(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to url)).document
            val directLink = document.selectFirst("a#downloadButton")?.attr("href") ?: return
            val quality = when {
                directLink.contains("1080") -> 1080
                directLink.contains("720") -> 720
                else -> Qualities.Unknown.value
            }
            callback.invoke(newExtractorLink("KrakenFiles", "KrakenFiles", directLink, url, quality, ExtractorLinkType.VIDEO))
        } catch (e: Exception) {
            println("KrakenFiles extractor error: ${e.message}")
        }
    }

    private suspend fun loadMegaExtractor(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Mega extractor bisa pakai loadExtractor atau implementasi Mega yang tersedia
        loadExtractor(url, url, subtitleCallback) { link ->
            callback.invoke(newExtractorLink("Mega", "Mega", link.url, url, Qualities.Unknown.value, link.type))
        }
    }

    private suspend fun loadPixelExtractor(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, url, subtitleCallback) { link ->
            callback.invoke(newExtractorLink("Pixel", "Pixel", link.url, url, Qualities.Unknown.value, link.type))
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(newExtractorLink(name, name, link.url, link.referer, quality, link.type))
            }
        }
    }
}
