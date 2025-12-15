package com.arranoust

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
    "anime-terbaru/page/%d" to "Episode Terbaru",
    "daftar-anime-2/page/%d" to "Daftar Anime"
)

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val url = when(request.name) {
        "Episode Terbaru" -> "$mainUrl/anime-terbaru/page/$page"
        "Daftar Anime" -> "$mainUrl/daftar-anime-2/page/$page"
        else -> "$mainUrl/anime-terbaru/page/$page"
    }

    val document = app.get(url).document

    val items = when(request.name) {
        "Episode Terbaru" -> document.select("li[itemtype='http://schema.org/CreativeWork']")
        "Daftar Anime" -> document.select("div.animepost")
        else -> emptyList()
    }

    val homeList = items.mapNotNull { 
        if(request.name == "Episode Terbaru") it.toLatestAnimeResult()
        else it.toAnimeListResult()
    }

    return newHomePageResponse(
        list = HomePageList(
            name = request.name,
            list = homeList,
            isHorizontalImages = true // horizontal untuk kedua tab
        ),
        hasNext = true
    )
}

// Extension function sederhana untuk Daftar Anime
fun org.jsoup.nodes.Element.toAnimeListResult(): AnimeSearchResponse? {
    val anchor = this.selectFirst("div.animposx > a") ?: return null
    val url = anchor.attr("href")
    val title = anchor.selectFirst("div.data > div.title > h2")?.text() ?: return null
    val poster = anchor.selectFirst("img.anmsa")?.attr("src") ?: return null

    return AnimeSearchResponse(
        name = title,
        url = url,
        posterUrl = poster
    )
}

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.thumb a") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim()?.removeBloat()
            ?: a.attr("title")?.removeBloat()
            ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("main#main li[itemtype='http://schema.org/CreativeWork']")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.thumb a") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim()?.removeBloat()
            ?: a.attr("title")?.removeBloat()
            ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl/$url"

    override suspend fun load(url: String): LoadResponse? {
        val finalUrl = if (url.contains("/anime/")) url
        else app.get("$mainUrl/$url", headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Referer" to mainUrl,
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )).document.selectFirst("div.nvs.nvsc a")?.attr("href")?.let { fixUrl(it) }

        val document = app.get(finalUrl ?: return null, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Referer" to mainUrl,
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )).document

        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")?.let { fixUrl(it) }
        val tags = document.select("div.genre-info > a").map { it.text() }
        val year = document.selectFirst("div.spe > span:contains(Rilis)")?.ownText()?.let {
            Regex("\\d{4}").find(it)?.value?.toIntOrNull()
        }
        val status = getStatus(document.selectFirst("div.spe > span:contains(Status)")?.ownText() ?: return null)
        val type = getType(document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.trim()?.lowercase() ?: "tv")
        val description = document.select("div.desc p").text().trim()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")?.let { fixUrl(it) }

        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull { li ->
    val header = li.selectFirst("span.lchx > a") ?: return@mapNotNull null
    val epNumber = Regex("Episode\\s?(\\d+)").find(header.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
    val link = fixUrl(header.attr("href"))

    newEpisode(link) {
        this.episode = epNumber
        this.posterUrl = poster
    }
}.reversed()

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            this.posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            addTrailer(trailer)
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("div#downloadb li").forEach { el ->
            el.select("a").forEach {
                loadFixedExtractor(
                    fixUrl(it.attr("href")),
                    el.selectFirst("strong")?.text() ?: "Unknown",
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        name: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = name.fixQuality()
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.fixQuality(): Int = when (this.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun String.removeBloat(): String =
        this.replace(Regex("(Nonton)|(Anime)|(Subtitle\\sIndonesia)|(Sub\\sIndo)"), "").trim()
}