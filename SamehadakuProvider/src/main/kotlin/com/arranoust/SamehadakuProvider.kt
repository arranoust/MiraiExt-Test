package com.arranoust

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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
        "anime-terbaru/page/%d" to "Episode Terbaru"
    )

    // ================== Homepage ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
        else safeGet("$mainUrl/$url")?.selectFirst("div.nvs.nvsc a")?.attr("href")?.let { fixUrl(it) }
            ?: return null

        val document = safeGet(finalUrl) ?: return null

        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")?.let { fixUrl(it) }
        val tags = document.select("div.genre-info > a").map { it.text() }.ifEmpty { listOf("Unknown") }
        val year = document.selectFirst("div.spe > span:contains(Rilis)")?.ownText()?.let {
            Regex("\\d{4}").find(it)?.value?.toIntOrNull()
        }
        val status = getStatus(document.selectFirst("div.spe > span:contains(Status)")?.ownText() ?: "")
        val type =
            getType(document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.trim()?.lowercase() ?: "tv")
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

    // ================== Load Links ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = safeGet(data) ?: return false

        val links = mutableListOf<ExtractorLink>()
        for (el in document.select("div#downloadb li")) {
            val qualityName = el.selectFirst("strong")?.text() ?: "Unknown"
            for (a in el.select("a")) {
                val href = a.attr("href").takeIf { it.isNotEmpty() } ?: continue
                val ex = loadFixedExtractorSusp(fixUrl(href), qualityName, "$mainUrl/", subtitleCallback)
                if (ex != null && links.none { it.url == ex.url }) links.add(ex)
            }
        }

        prioritizeLinks(links).forEach { callback(it) }
        return links.isNotEmpty()
    }
    private suspend fun loadFixedExtractorSusp(
    url: String,
    name: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit
): ExtractorLink? {
    var result: ExtractorLink? = null

    loadExtractor(url, referer, subtitleCallback) { link ->
        result = newExtractorLink(
            link.name,
            link.name,
            link.url,
            link.type
        ) {
            this.referer = link.referer
            this.quality = name.fixQuality()
            this.headers = link.headers
            this.extractorData = link.extractorData
        }
    }

    return result
}

    private var preferredQuality: String = "Auto" // options: Auto, 2160, 1080, 720, 480
    fun setPreferredQuality(value: String) { preferredQuality = value }

    private fun prioritizeLinks(links: List<ExtractorLink>): List<ExtractorLink> {
        if (preferredQuality.equals("Auto", true)) return links
        val prefInt = preferredQuality.filter { it.isDigit() }.toIntOrNull() ?: return links
        return links.sortedWith(compareByDescending<ExtractorLink> {
            val urlLower = (it.url ?: "").lowercase()
            val adaptiveBonus = if (urlLower.contains("m3u8") || urlLower.contains(".mpd")) 10 else 0

            val qualityScore = when {
                it.quality == prefInt -> 1000
                it.quality > prefInt -> 500 - (it.quality - prefInt)
                it.quality < prefInt -> 200 - (prefInt - it.quality)
                else -> 0
            }

            qualityScore + adaptiveBonus
        }.thenByDescending { it.quality })
    }

    // ================== Utils ==================
    private fun String.fixQuality(): Int {
        val s = this.lowercase()
        if (s.contains("4k") || s.contains("2160")) return Qualities.P2160.value
        if (s.contains("1080") || s.contains("full") || s.contains("fhd")) return Qualities.P1080.value
        if (s.contains("720") || s.contains("hd") || s.contains("mp4hd")) return Qualities.P720.value
        val num = this.filter { it.isDigit() }.toIntOrNull()
        if (num != null) return num
        return Qualities.Unknown.value
    }

    private fun String.removeBloat(): String =
        this.replace(Regex("(Nonton)|(Anime)|(Subtitle\\sIndonesia)|(Sub\\sIndo)"), "").trim()

    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl/$url"
    private fun fixUrlNull(url: String?): String? = url?.let { fixUrl(it) }

    // ================== SafeGet with Headers ==================
    private suspend fun safeGet(url: String, retries: Int = 2, backoffMs: Long = 500L): org.jsoup.nodes.Document? {
        var attempt = 0
        var lastEx: Exception? = null
        while (attempt <= retries) {
            try {
                return app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36",
                        "Accept" to "text/html"
                    )
                ).document
            } catch (e: Exception) {
                lastEx = e
                if (attempt == retries) break
                delay(backoffMs * (1 shl attempt))
                attempt++
            }
        }
        return null
    }
}