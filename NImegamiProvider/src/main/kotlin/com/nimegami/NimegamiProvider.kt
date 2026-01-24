package com.nimegami

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class NimegamiProvider : MainAPI() {
    override var mainUrl = "https://nimegami.id"
    override var name = "Nimegami"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("On-Going", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage =
            mainPageOf(
                    "" to "Updated Anime",
                    "/type/drama-movie" to "Drama Movie",
                    "/type/drama-series" to "Drama Series",
                    "/type/live" to "Live",
                    "/type/live-action" to "Live Action",
                    "/type/tv" to "Anime",
                    "/type/movie" to "Movie",
                    "/type/ona" to "ONA",
                    "/type/ova" to "OVA",
                    "/type/ova/special" to "OVA Special",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page").document
        val home =
                document.select("div.post-article article, div.archive article").mapNotNull {
                    it.toSearchResult()
                }
        return newHomePageResponse(
                list =
                        HomePageList(
                                name = request.name,
                                list = home,
                                isHorizontalImages = request.name != "Updated Anime"
                        ),
                hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = selectFirst("h2 a")!!.text()
        val href = fixUrl(selectFirst("h2 a")!!.attr("href"))
        val poster = selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")!!.text()
        val poster = doc.selectFirst("div.coverthumbnail img")?.attr("src")

        val episodes = doc.select("li.select-eps").mapIndexed { idx, li ->
            newEpisode(
                li.attr("data")
            ) {
                name = li.text()
                episode = idx + 1
            }
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val decoded = base64Decode(data)

        val sources =
            AppUtils.tryParseJson<List<Sources>>(decoded) ?: return false

        sources.forEach { src ->
            src.url?.forEach { streamUrl ->
                loadExtractor(
                    streamUrl,
                    "https://dl.berkasdrive.com/",
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }

    data class Sources(
        @JsonProperty("format") val format: String?,
        @JsonProperty("url") val url: List<String>?
    )
}