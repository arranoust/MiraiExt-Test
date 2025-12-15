package com.arranoust

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
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

        val homeList = document
            .select("li[itemtype='http://schema.org/CreativeWork']")
            .mapNotNull { it.toLatestAnimeResult() }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = homeList,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a = selectFirst("div.thumb a") ?: selectFirst("a") ?: return null
        val title = selectFirst("h2.entry-title a")?.text()?.removeBloat()
            ?: a.attr("title")?.removeBloat()
            ?: return null

        return newAnimeSearchResponse(
            title,
            fixUrl(a.attr("href")),
            TvType.Anime
        ) {
            posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        }
    }

    // ================== Search ==================
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = safeGet("$mainUrl/?s=$encoded") ?: return emptyList()

        return document
            .select("main#main article[itemtype='http://schema.org/CreativeWork']")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst("div.animposx a") ?: return null
        val title = a.selectFirst("h2")?.text() ?: return null

        return newAnimeSearchResponse(
            title,
            fixUrl(a.attr("href")),
            TvType.Anime
        ) {
            posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
        }
    }

    // ================== Load Anime ==================
    override suspend fun load(url: String): LoadResponse? {
        val finalUrl =
            if (url.contains("/anime/")) url
            else safeGet("$mainUrl/$url")
                ?.selectFirst("div.nvs.nvsc a")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: return null

        val document = safeGet(finalUrl) ?: return null

        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.thumb img")?.attr("src"))
        val description = document.select("div.desc p").text()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")?.let { fixUrl(it) }

        val type = getType(
            document.selectFirst("div.spe > span:contains(Type)")?.ownText() ?: ""
        )

        val status = getStatus(
            document.selectFirst("div.spe > span:contains(Status)")?.ownText() ?: ""
        )

        val episodes = document
            .select("div.lstepsiode.listeps ul li")
            .mapNotNull {
                val a = it.selectFirst("span.lchx a") ?: return@mapNotNull null
                val ep = Regex("(\\d+)").find(a.text())?.value?.toIntOrNull()
                newEpisode(fixUrl(a.attr("href"))) {
                    episode = ep
                    posterUrl = poster
                }
            }
            .reversed()

        return newAnimeLoadResponse(title, finalUrl, type) {
            posterUrl = poster
            plot = description
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
            addTrailer(trailer)
        }
    }

    // ================== Load Links (FIXED) ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = safeGet(data) ?: return false
        var found = false

        for (el in document.select("div#downloadb li")) {
            val qualityName = el.selectFirst("strong")?.text() ?: "Unknown"

            for (a in el.select("a")) {
                val href = a.attr("href")
                if (href.isBlank()) continue

                loadExtractor(
                    fixUrl(href),
                    "$mainUrl/",
                    subtitleCallback
                ) { link ->
                    found = true
                    callback(
                        newExtractorLink(
                            link.name,
                            link.name,
                            link.url,
                            link.type
                        ) {
                            referer = link.referer
                            headers = link.headers
                            extractorData = link.extractorData
                            quality = qualityName.fixQuality()
                        }
                    )
                }
            }
        }
        return found
    }

    // ================== Utils ==================
    private fun String.fixQuality(): Int {
        val s = lowercase()
        return when {
            s.contains("2160") || s.contains("4k") -> Qualities.P2160.value
            s.contains("1080") -> Qualities.P1080.value
            s.contains("720") -> Qualities.P720.value
            else -> filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    private fun String.removeBloat(): String =
        replace(Regex("(Nonton|Anime|Subtitle\\sIndonesia|Sub\\sIndo)", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun fixUrl(url: String): String =
        if (url.startsWith("http")) url else "$mainUrl/$url"

    private fun fixUrlNull(url: String?): String? =
        url?.let { fixUrl(it) }

    // ================== SafeGet ==================
    private suspend fun safeGet(
        url: String,
        retries: Int = 2,
        backoffMs: Long = 500L
    ): org.jsoup.nodes.Document? {
        repeat(retries + 1) { attempt ->
            try {
                return app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Accept" to "text/html"
                    )
                ).document
            } catch (_: Exception) {
                if (attempt < retries) delay(backoffMs * (1 shl attempt))
            }
        }
        return null
    }
}
