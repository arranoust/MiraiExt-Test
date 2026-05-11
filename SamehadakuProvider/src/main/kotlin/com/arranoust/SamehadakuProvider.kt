package com.arranoust

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v2.samehadaku.how"
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
        "anime-terbaru/page/%d" to "Episode Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = safeGet("$mainUrl/${request.data.format(page)}")
            ?: return newHomePageResponse(listOf(), false)
        val items = document.select("li[itemtype='http://schema.org/CreativeWork']")
        val homeList = items.mapNotNull { it.toLatestAnimeResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, homeList, isHorizontalImages = true),
            hasNext = true
        )
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.thumb a") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text()?.removeBloat() ?: a.attr("title")?.removeBloat() ?: return null
        val href = fixUrl(a.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = safeGet("$mainUrl/?s=$query") ?: return emptyList()
        return document.select("main#main article[itemtype='http://schema.org/CreativeWork']").mapNotNull {
            val a = it.selectFirst("div.animposx a") ?: return@mapNotNull null
            val title = a.selectFirst("h2")?.text()?.removeBloat() ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(a.attr("href")), TvType.Anime) {
                this.posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        var document = safeGet(url) ?: return null
        
        if (!url.contains("/anime/")) {
            val animeLink = document.selectFirst("div.nvs.nvsc a")?.attr("href")
            if (animeLink != null) {
                val newDoc = safeGet(fixUrl(animeLink))
                if (newDoc != null) document = newDoc
            }
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.thumb > img")?.attr("src"))
        val type = getType(document.selectFirst("div.spe > span:contains(Type)")?.ownText() ?: "tv")
        val year = Regex("\\d{4}").find(document.selectFirst("div.spe > span:contains(Rilis)")?.ownText() ?: "")?.value?.toIntOrNull()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId
        
        var animeMetaData: MetaAnimeData? = null
        if (malId != null) {
            try {
                val syncData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = ObjectMapper().readValue(syncData, MetaAnimeData::class.java)
            } catch (e: Exception) { }
        }

        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull { li ->
            val a = li.selectFirst("span.lchx > a") ?: return@mapNotNull null
            val epNum = Regex("Episode\\s?(\\d+)").find(a.text())?.groupValues?.getOrNull(1)
            val metaEp = animeMetaData?.episodes?.get(epNum)

            newEpisode(fixUrl(a.attr("href"))) {
                this.name = metaEp?.title?.get("en") ?: a.text()
                this.episode = epNum?.toIntOrNull()
                this.posterUrl = metaEp?.image ?: poster
                this.description = metaEp?.overview
                this.addDate(metaEp?.airDateUtc)
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.posterUrl = tracker?.image ?: poster
            this.year = year
            this.plot = animeMetaData?.description?.replace(Regex("<.*?>"), "") ?: document.select("div.desc p").text().trim()
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            addTrailer(document.selectFirst("div.trailer-anime iframe")?.attr("src"))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = safeGet(data) ?: return false
        
        // Ambil semua elemen li di dalam download box
        val downloadElements = document.select("div#downloadb li")
        
        for (el in downloadElements) {
            val quality = el.selectFirst("strong")?.text()?.fixQuality() ?: Qualities.Unknown.value
            val links = el.select("a")
            
            for (a in links) {
                val url = fixUrl(a.attr("href"))
                
                // PENTING: Gunakan loadExtractor tapi jangan panggil fungsi suspend lain di dalamnya
                // Jika loadExtractor sendiri adalah suspend, dia harus berada di lingkup yang benar
                loadExtractor(url, "$mainUrl/", subtitleCallback) { link ->
                    callback.invoke(newExtractorLink(
                        link.name, 
                        link.name, 
                        link.url, 
                        link.type
                    ) {
                        this.quality = quality
                        this.referer = link.referer
                        this.headers = link.headers
                    })
                }
            }
        }
        return true
    }

    private fun String.fixQuality(): Int = when (this.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun String.removeBloat(): String = this.replace(Regex("(?i)(Nonton|Anime|Subtitle\\s?Indonesia|Sub\\s?Indo)"), "").trim()
    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl/${url.removePrefix("/")}"
    private fun fixUrlNull(url: String?): String? = url?.let { fixUrl(it) }

    private suspend fun safeGet(url: String) = try {
        app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "text/html")).document
    } catch (_: Exception) { null }
}

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(val title: Map<String, String>?, val overview: String?, val image: String?, val airDateUtc: String?)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(val titles: Map<String, String>?, val description: String?, val episodes: Map<String, MetaEpisode>?)
