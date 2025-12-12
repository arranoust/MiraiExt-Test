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
                "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            ),
            cookies = mapOf("_as_ipin_ct" to "ID"),
            referer = ref
        )
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/page/" to "Episode Terbaru",
            "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
            "$mainUrl/genres/donghua/page/" to "Donghua",
            "$mainUrl/genres/action/page/" to "Action",
            "$mainUrl/genres/adult-cast/page/" to "Adult Cast",
            "$mainUrl/genres/adventure/page/" to "Adventure",
            "$mainUrl/genres/award-winning/page/" to "Award Winning",
            "$mainUrl/genres/comedy/page/" to "Comedy",
            "$mainUrl/genres/demons/page/" to "Demons",
            "$mainUrl/genres/donghua/page/" to "Donghua",
            "$mainUrl/genres/drama/page/" to "Drama",
            "$mainUrl/genres/ecchi/page/" to "Ecchi",
            "$mainUrl/genres/fantasy/page/" to "Fantasy",
            "$mainUrl/genres/game/page/" to "Game",
            "$mainUrl/genres/harem/page/" to "Harem",
            "$mainUrl/genres/historical/page/" to "Historical",
            "$mainUrl/genres/horror/page/" to "Horror",
            "$mainUrl/genres/isekai/page/" to "Isekai",
            "$mainUrl/genres/kids/page/" to "Kids",
            "$mainUrl/genres/magic/page/" to "Magic",
            "$mainUrl/genres/martial-arts/page/" to "Martial Arts",
            "$mainUrl/genres/mecha/page/" to "Mecha",
            "$mainUrl/genres/military/page/" to "Military",
            "$mainUrl/genres/music/page/" to "Music",
            "$mainUrl/genres/mystery/page/" to "Mystery",
            "$mainUrl/genres/mythology/page/" to "Mythology",
            "$mainUrl/genres/parody/page/" to "Parody",
            "$mainUrl/genres/psychological/page/" to "Psychological",
            "$mainUrl/genres/reincarnation/page/" to "Reincarnation",
            "$mainUrl/genres/school/page/" to "School",
            "$mainUrl/genres/sci-fi/page/" to "Sci-Fi",
            "$mainUrl/genres/seinen/page/" to "Seinen",
            "$mainUrl/genres/shoujo/page/" to "Shoujo",
            "$mainUrl/genres/shounen/page/" to "Shounen",
            "$mainUrl/genres/slice-of-life/page/" to "Slice of Life",
            "$mainUrl/genres/space/page/" to "Space",
            "$mainUrl/genres/sports/page/" to "Sports",
            "$mainUrl/genres/super-power/page/" to "Super Power",
            "$mainUrl/genres/supernatural/page/" to "Supernatural",
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
            title =
                when {
                    (title.contains("-episode")) && !(title.contains("-movie")) ->
                        title.substringBefore("-episode")

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
        val epNum =
            Regex("Episode\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

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
            document.selectFirst("h1.entry-title")
                ?.text()
                .toString()
                .replace("Subtitle Indonesia", "")
                .trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes =
            document.select("ul.daftar > li")
                .mapNotNull { li ->
                    val a = li.selectFirst("a") ?: return@mapNotNull null
                    val epLink = fixUrl(a.attr("href"))
                    val name = a.text()
                    val epNum =
                        Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)
                            ?.toIntOrNull()

                    newEpisode(epLink) {
                        this.name = name
                        this.episode = epNum
                    }
                }
                .reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags =
                document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
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
        val options = document.select(".mobius > .mirror > option")

        coroutineScope {
            val jobs = options.map { element ->
                async {
                    safeApiCall {
                        val encoded = element.attr("data-em")
                        if (encoded.isNullOrEmpty()) return@safeApiCall

                        val decoded =
                            runCatching { base64Decode(encoded) }.getOrNull()
                                ?: return@safeApiCall

                        val iframe =
                            fixUrl(
                                Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: ""
                            )
                        if (iframe.isBlank()) return@safeApiCall

                        val quality = getIndexQuality(element.text())

                        when {
                            iframe.startsWith("$mainUrl/utils/player/arch/") ||
                            iframe.startsWith("$mainUrl/utils/player/race/") -> {

                                val src =
                                    request(iframe, ref = data).document
                                        .selectFirst("source")
                                        ?.attr("src")
                                        ?.let { fixUrl(it) }
                                        ?: return@safeApiCall

                                callback(
                                    ExtractorLink(
                                        source = if (iframe.contains("arch")) "Arch" else "Race",
                                        name = name,
                                        url = src,
                                        referer = mainUrl,
                                        quality = quality,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                            }

                            iframe.startsWith("https://aghanim.xyz/tools/redirect/") -> {
                                val id =
                                    iframe.substringAfter("id=").substringBefore("&token")
                                val link =
                                    "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                                loadFixedExtractor(
                                    link,
                                    quality,
                                    mainUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }

                            iframe.startsWith("$mainUrl/utils/player/framezilla/") ||
                            iframe.startsWith("https://uservideo.xyz") -> {

                                val inner =
                                    request(iframe, ref = data).document
                                        .selectFirst("iframe")?.attr("src")
                                        ?.let { fixUrl(it) }
                                        ?: return@safeApiCall

                                loadFixedExtractor(
                                    inner,
                                    quality,
                                    mainUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }

                            else -> {
                                loadFixedExtractor(
                                    iframe,
                                    quality,
                                    mainUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        return true
    }

    private fun getIndexQuality(text: String?): Int {
        val t = text ?: ""

        Regex("(\\d{3,4})[pP]").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it
        }

        Regex("(\\d{3,4})").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it
        }

        return Qualities.Unknown.value
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = link.url,
                    referer = link.referer,
                    quality = if (quality == Qualities.Unknown.value) link.quality else quality,
                    type = link.type,
                    extractorData = link.extractorData,
                    headers = link.headers
                )
            )
        }
    }
}
