package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import java.util.concurrent.Semaphore
import kotlin.text.Regex

class AnimeSail : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun getIndexQuality(str: String?): Int {
            val match = Regex("(\\d{3,4})\\s*[pP]").find(str ?: "")
            return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        repeat(3) { attempt ->
            try {
                return app.get(
                    url,
                    headers = mapOf(
                        "Accept" to
                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                    ),
                    cookies = mapOf("_as_ipin_ct" to "ID"),
                    referer = ref,
                    timeout = 15_000
                )
            } catch (e: Exception) {
                if (attempt == 2) throw e
            }
        }
        throw Exception("Failed to fetch $url")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
        "$mainUrl/genres/donghua/page/" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) uri
        else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode") && !title.contains("-movie")) -> title.substringBefore("-episode")
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrl(this.selectFirst("div.limit img")?.attr("src") ?: "")
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

        val title =
            document.selectFirst("h1.entry-title")?.text().toString().replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li")
            .mapNotNull { it.toEpisode() }
            .sortedBy { it.episode }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            tags = document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    private fun Element.toEpisode(): Episode? {
        val anchor = this.selectFirst("a") ?: return null
        val episodeLink = fixUrl(anchor.attr("href"))
        val episodeName = anchor.text()
        val episodeNumber = Regex("Episode\\s?(\\d+)").find(episodeName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newEpisode(episodeLink) {
            name = episodeName
            episode = episodeNumber
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {

        val document = request(data).document
        val semaphore = Semaphore(5)

        val jobs = document.select(".mobius > .mirror > option").map { element ->
            async {
                semaphore.withPermit {
                    safeApiCall {
                        val iframe = fixUrl(Jsoup.parse(base64Decode(element.attr("data-em"))).select("iframe").attr("src"))
                        val quality = getIndexQuality(element.text())

                        when {
                            iframe.startsWith("$mainUrl/utils/player/arch/") ||
                            iframe.startsWith("$mainUrl/utils/player/race/") -> {
                                request(iframe, ref = data).document.select("source").attr("src").let { link ->
                                    val source = if (iframe.contains("/arch/")) "Arch" else "Race"
                                    callback(
                                        ExtractorLink(
                                            source = source,
                                            name = source,
                                            url = link,
                                            referer = mainUrl,
                                            quality = quality,
                                            type = ExtractorLinkType.VIDEO
                                        )
                                    )
                                }
                            }
                            iframe.startsWith("https://aghanim.xyz/tools/redirect/") -> {
                                val link = "https://rasa-cintaku-semakin-berantai.xyz/v/${iframe.substringAfter("id=").substringBefore("&token")}"
                                loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                            }
                            iframe.startsWith("$mainUrl/utils/player/framezilla/") ||
                            iframe.startsWith("https://uservideo.xyz") -> {
                                request(iframe, ref = data).document.select("iframe").attr("src").let { link ->
                                    loadFixedExtractor(fixUrl(link), quality, mainUrl, subtitleCallback, callback)
                                }
                            }
                            else -> {
                                loadFixedExtractor(iframe, quality, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        }

        jobs.awaitAll()
        true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            withContext(Dispatchers.IO) {
                val isHEVC = link.url.contains("h265", true) || link.url.contains("hevc", true)
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = if (isHEVC) "$name (HEVC)" else name,
                        url = link.url,
                        referer = link.referer,
                        quality = if (isHEVC) -1 else quality,
                        type = if (isHEVC) ExtractorLinkType.M3U8 else link.type,
                        extractorData = link.extractorData,
                        headers = link.headers
                    )
                )
            }
        }
    }
}
