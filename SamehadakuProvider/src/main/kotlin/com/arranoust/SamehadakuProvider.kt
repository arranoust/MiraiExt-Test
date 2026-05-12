package com.arranoust

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
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
        val a = this.selectFirst("div.thumb a") ?: this.selectFirst("a") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim()?.removeBloat()
            ?: a.attr("title")?.removeBloat() ?: return null
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
            val title = a.selectFirst("h2")?.text()?.trim() ?: a.attr("title") ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(a.attr("href")), TvType.Anime) {
                this.posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val finalUrl = if (url.contains("/anime/")) url
        else safeGet(url)?.selectFirst("div.nvs.nvsc a")?.attr("href")?.let { fixUrl(it) } ?: return null

        val document = safeGet(finalUrl) ?: return null
        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")?.let { fixUrl(it) }
        val year = document.selectFirst("div.spe > span:contains(Rilis)")?.ownText()?.let {
            Regex("\\d{4}").find(it)?.value?.toIntOrNull()
        }
        val type = getType(document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.trim()?.lowercase() ?: "tv")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId
        var animeMetaData: MetaAnimeData? = null

        if (malId != null) {
            try {
                val syncData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = ObjectMapper().readValue(syncData, MetaAnimeData::class.java)
            } catch (e: Exception) { }
        }

        val logoUrl = fetchTmdbLogo(animeMetaData?.mappings?.tmdbId, type)
        val backgroundPoster = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("div.lstepsiode.listeps ul li").amap { li ->
            val header = li.selectFirst("span.lchx > a") ?: return@amap null
            val epName = header.text()
            val epNumText = Regex("Episode\\s?(\\d+)").find(epName)?.groupValues?.getOrNull(1)
            val metaEp = animeMetaData?.episodes?.get(epNumText)

            newEpisode(fixUrl(header.attr("href"))) {
                this.episode = epNumText?.toIntOrNull()
                this.name = metaEp?.title?.get("en") ?: epName
                this.posterUrl = metaEp?.image ?: poster
                this.description = metaEp?.overview ?: "Sinopsis episode belum tersedia."
                this.addDate(metaEp?.airDateUtc)
            }
        }.filterNotNull().reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundPoster
            try { this.logoUrl = logoUrl } catch(e: Throwable) {}
            this.year = year
            this.plot = animeMetaData?.description?.replace(Regex("<.*?>"), "") ?: document.select("div.desc p").text().trim()
            
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            addTrailer(document.selectFirst("div.trailer-anime iframe")?.attr("src")?.let { fixUrl(it) })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = safeGet(data) ?: return false
        
        // MENGGUNAKAN CARA CODE ORANG: amap untuk loop async yang aman bagi suspend functions
        document.select("div#downloadb li").amap { el ->
            val quality = el.selectFirst("strong")?.text()?.fixQuality() ?: Qualities.Unknown.value
            el.select("a").amap { a ->
                val href = a.attr("href")
                if (href.isNotBlank()) {
                    // loadExtractor dipanggil dalam lingkup amap yang merupakan coroutine scope
                    loadExtractor(fixUrl(href), "$mainUrl/", subtitleCallback) { link ->
                        callback.invoke(
                            newExtractorLink(
                                link.source,
                                link.name,
                                link.url,
                                link.referer,
                                quality, // Menggunakan quality yang sudah kita parse
                                link.type,
                                link.headers,
                                link.extractorData
                            )
                        )
                    }
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

    private fun String.removeBloat(): String =
        this.replace(Regex("(?i)(Nonton|Anime|Subtitle\\s?Indonesia|Sub\\s?Indo|Lengkap|Batch)"), "").trim()

    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl/${url.removePrefix("/")}"
    private fun fixUrlNull(url: String?): String? = if (url.isNullOrBlank()) null else fixUrl(url)

    private suspend fun safeGet(url: String) = try {
        app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "text/html")).document
    } catch (_: Exception) { null }

    private suspend fun fetchTmdbLogo(tmdbId: Int?, type: TvType): String? {
        if (tmdbId == null) return null
        val apiType = if (type == TvType.AnimeMovie) "movie" else "tv"
        return try {
            // Mengikuti pola fetch logo dari code orang dengan voting filter sederhana
            val res = app.get("https://api.themoviedb.org/3/$apiType/$tmdbId/images?api_key=98ae14df2b8d8f8f8136499daf79f0e0").text
            val logos = JSONObject(res).optJSONArray("logos")
            if (logos != null && logos.length() > 0) {
                val path = logos.getJSONObject(0).getString("file_path")
                "https://image.tmdb.org/t/p/w500$path"
            } else null
        } catch (e: Exception) { null }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaImage(val coverType: String?, val url: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    val title: Map<String, String>?,
    val overview: String?,
    val image: String?,
    val airDateUtc: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    val titles: Map<String, String>?,
    val description: String?,
    val images: List<MetaImage>?,
    val episodes: Map<String, MetaEpisode>?,
    val mappings: MetaMappings?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaMappings(
    @JsonProperty("themoviedb_id") val tmdbId: Int? = null
)
