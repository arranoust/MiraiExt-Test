package com.nimegami

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class NimegamiProvider : MainAPI() {

    override var name = "Nimegami"
    override var mainUrl = "https://nimegami.id"
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl).document

        val items = doc.select("div.post-article article").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Update Terbaru",
                    items,
                    isHorizontalImages = true
                )
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