package com.anizone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class AnizoneProvider : MainAPI() {

    override var name = "AniZone"
    override var mainUrl = "https://anizone.to"
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    
    override val mainPage = mainPageOf(
        "tv" to "Latest TV Series",
        "movie" to "Latest Movies"
    )

    // =========================
    // MAIN PAGE 
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = "$mainUrl/anime?page=$page"
        val doc = Jsoup.connect(url).get()

        val items = doc.select("div.bg-slate-900.rounded-lg")
            .mapNotNull { parseCard(it, request.data) }

        return newHomePageResponse(
            HomePageList(
                request.name,
                items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val url = "$mainUrl/search?query=${URLEncoder.encode(query, "UTF-8")}"
        val doc = Jsoup.connect(url).get()

        return doc.select("div.bg-slate-900.rounded-lg")
            .mapNotNull { parseCard(it, null) }
    }

    // =========================
    // LOAD DETAIL
    // =========================
    override suspend fun load(url: String): LoadResponse {

        val doc = Jsoup.connect(url).get()

        val title = doc.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("main img")?.attr("src")
        val plot = doc.selectFirst(".sr-only + div")?.text().orEmpty()

        val info = doc.select("span.inline-block").map { it.text() }

        val year = info.firstOrNull { it.matches(Regex("\\d{4}")) }?.toIntOrNull()

        val status = when {
            info.any { it.equals("Completed", true) } -> ShowStatus.Completed
            info.any { it.equals("Ongoing", true) } -> ShowStatus.Ongoing
            else -> null
        }

        val genres = doc.select("a[href*=\"/tag/\"]")
            .map { it.text() }

        val episodes = doc.select("li[x-data]")
            .mapNotNull { parseEpisode(it) }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            tags = genres
            this.year = year
            showStatus = status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    // =========================
    // STREAM
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        val player = doc.selectFirst("media-player") ?: return false

        player.select("track").forEach {
            subtitleCallback(
                newSubtitleFile(
                    it.attr("label"),
                    it.attr("src")
                )
            )
        }

        callback(
            newExtractorLink(
                name,
                name,
                player.attr("src"),
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }

    // =========================
    // HELPERS
    // =========================

    /**
     * @param filter "tv", "movie", atau null (untuk search)
     */
    private fun parseCard(el: Element, filter: String?): SearchResponse? {

        val link = el.selectFirst("a[href*=\"/anime/\"]") ?: return null
        val img = el.selectFirst("img") ?: return null

        val title = img.attr("alt").ifBlank {
            link.attr("title")
        }
        if (title.isBlank()) return null

        // ambil teks tipe (TV Series / Movie)
        val typeText = el.select(".text-xs span")
            .firstOrNull()
            ?.text()
            ?.lowercase()

        val isMovie = typeText?.contains("movie") == true
        val isTv = typeText?.contains("tv") == true

        // filter homepage
        when (filter) {
            "tv" -> if (!isTv) return null
            "movie" -> if (!isMovie) return null
        }

        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newMovieSearchResponse(
            title,
            link.attr("href"),
            tvType
        ) {
            posterUrl = img.attr("src")
        }
    }

    private fun parseEpisode(el: Element): Episode? {
        val a = el.selectFirst("a") ?: return null

        val name = el.selectFirst("h3")
            ?.text()
            ?.substringAfter(":")
            ?.trim()

        val date = el.select("span.line-clamp-1")
            .getOrNull(1)
            ?.text()
            ?.let {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .parse(it)?.time
            } ?: 0

        return newEpisode(a.attr("href")) {
            this.name = name
            this.date = date
            posterUrl = el.selectFirst("img")?.attr("src")
        }
    }
}
