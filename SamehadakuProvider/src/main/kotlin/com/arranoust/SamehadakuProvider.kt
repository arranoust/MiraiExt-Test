package com.arranoust

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v2.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null
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

    // ================== Homepage ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val document = safeGet("$mainUrl/${request.data.format(page)}")
            ?: return newHomePageResponse(listOf(), false)
        val items = document.select("li[itemtype='http://schema.org/CreativeWork']")
        val homeList = items.mapNotNull { it.toLatestAnimeResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = homeList,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.thumb a") ?: this.selectFirst("a") ?: return null
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

    // ================== Search ==================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val document = safeGet("$mainUrl/?s=$encodedQuery") ?: return emptyList()

        return document.select("main#main article[itemtype='http://schema.org/CreativeWork']")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.animposx a") ?: return null
        val title = a.selectFirst("h2")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return null
        val posterUrl = a.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ================== Load Anime ==================
    override suspend fun load(url: String): LoadResponse? {
        val finalUrl = if (url.contains("/anime/")) url
        else safeGet("$mainUrl/$url")?.selectFirst("div.nvs.nvsc a")?.attr("href")?.let { fixUrl(it) } ?: return null

        val document = safeGet(finalUrl) ?: return null
        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")?.let { fixUrl(it) }
        val year = document.selectFirst("div.spe > span:contains(Rilis)")?.ownText()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val type = getType(document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.trim()?.lowercase() ?: "tv")

        // === LOGIKA MAPPING IDENTIK ===
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId
        
        var animeMetaData: MetaAnimeData? = null
        if (malId != null) {
            try {
                val syncData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                animeMetaData = mapper.readValue(syncData, MetaAnimeData::class.java)
            } catch (e: Exception) { }
        }

        val logoUrl = fetchTmdbLogo(animeMetaData?.mappings?.tmdbId, type)
        val fanart = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull { li ->
            val a = li.selectFirst("span.lchx > a") ?: return@mapNotNull null
            val epNum = Regex("Episode\\s?(\\d+)").find(a.text())?.groupValues?.getOrNull(1)
            val metaEp = animeMetaData?.episodes?.get(epNum)

            newEpisode(fixUrl(a.attr("href"))) {
                this.name = metaEp?.title?.get("en") ?: a.text()
                this.episode = epNum?.toIntOrNull()
                this.posterUrl = metaEp?.image ?: poster
                this.description = metaEp?.overview
                this.score = metaEp?.rating?.toDoubleOrNull()?.times(10)?.toInt() // Mapping score ke 0-1000
                this.addDate(metaEp?.airDateUtc)
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = fanart
            try { this.logoUrl = logoUrl } catch(e: Throwable) {} // Identik dengan try-catch dia
            this.year = year
            this.plot = animeMetaData?.description?.replace(Regex("<.*?>"), "") ?: document.select("div.desc p").text().trim()
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            addTrailer(document.selectFirst("div.trailer-anime iframe")?.attr("src"))
        }
    }

    // ================== Load Links ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = safeGet(data) ?: return false

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
        // referer diset langsung di newExtractorLink
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
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

    // ================== Utils ==================
    private fun String.fixQuality(): Int = when (this.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(val coverType: String?, val url: String?)

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        val episode: String?,
        val image: String?,
        val title: Map<String, String>?,
        val overview: String?,
        val rating: String?,
        val airDateUtc: String?,
        val runtime: Int?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        val titles: Map<String, String>?,
        val description: String?,
        val images: List<MetaImage>?,
        val episodes: Map<String, MetaEpisode>?,
        val mappings: MetaMappings?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @com.fasterxml.jackson.annotation.JsonProperty("themoviedb_id") val tmdbId: Int? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    suspend fun fetchTmdbLogo(tmdbId: Int?, type: TvType): String? {
    if (tmdbId == null) return null
    val apiKey = "98ae14df2b8d8f8f8136499daf79f0e0" 
    val apiType = if (type == TvType.AnimeMovie) "movie" else "tv"
    val url = "https://api.themoviedb.org/3/$apiType/$tmdbId/images?api_key=$apiKey"
    
    return try {
        val res = app.get(url).text
        val json = org.json.JSONObject(res)
        val logos = json.optJSONArray("logos")
        if (logos != null && logos.length() > 0) {
            "https://image.tmdb.org/t/p/w500${logos.getJSONObject(0).getString("file_path")}"
        } else null
    } catch (e: Exception) { null }

    private fun String.removeBloat(): String =
        this.replace(Regex("(Nonton)|(Anime)|(Subtitle\\sIndonesia)|(Sub\\sIndo)"), "").trim()

    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl/$url"
    private fun fixUrlNull(url: String?): String? = url?.let { fixUrl(it) }

    // ================== SafeGet with Headers ==================
    private suspend fun safeGet(url: String) = try {
        // pake headers di parameter get, sesuai API NiceHTTP terbaru
        app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36",
                "Accept" to "text/html"
            )
        ).document
    } catch (_: Exception) {
        null
    }
}
