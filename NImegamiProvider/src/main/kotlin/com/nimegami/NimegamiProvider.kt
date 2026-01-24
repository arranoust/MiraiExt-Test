package com.nimegami

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

        val items = doc.select("div.list_eps_stream a").map {
            it.toSearchResult()
        }

        return HomePageResponse(
            listOf(
                HomePageList(
                    "Update Terbaru",
                    items,
                    isHorizontalImages = true
                )
            )
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.attr("title")
        val href = fixUrl(this.attr("href"))
        val poster = this.selectFirst("img")?.attr("src")

        return AnimeSearchResponse(
            title,
            href,
            name,
            TvType.Anime,
            poster,
            null
        )
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: "Nimegami"
        val poster = doc.selectFirst("img")?.attr("src")

        val episodes = doc.select("li.select-eps").map {
            Episode(
                data = it.attr("data"), // 🔴 BASE64 ASLI
                name = it.text()
            )
        }

        return AnimeLoadResponse(
            title,
            url,
            name,
            TvType.Anime,
            episodes
        ).apply {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // 1️⃣ decode base64
        val decoded = base64Decode(data)

        // 2️⃣ parse JSON
        val sources = tryParseJson<List<Source>>(decoded)
            ?: return false

        // 3️⃣ kirim URL ke extractor
        sources.forEach { src ->
            src.url?.forEach { link ->
                loadExtractor(
                    link,
                    "https://dl.berkasdrive.com/",
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}

data class Source(
    val format: String?,
    val url: List<String>?
)