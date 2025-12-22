package com.anizone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    // =========================
    // MAIN PAGE CONFIG
    // =========================
    override val mainPage = mainPageOf(
        "" to "Anime List"
    )

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = Jsoup.connect("$mainUrl/anime?page=$page").get()

        val items = doc.select("a[href^=\"/anime/\"]")
            .mapNotNull { parseAnimeCard(it) }

        return newHomePageResponse(
            HomePageList(
                "Anime List",
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

        val doc = Jsoup.connect(
            "$mainUrl/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}"
        ).get()

        return doc.select("a[href^=\"/anime/\"]")
            .mapNotNull { parseAnimeCard(it) }
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
    private fun parseAnimeCard(el: Element): SearchResponse? {
        val href = el.attr("href")
        if (!href.startsWith("/anime/")) return null

        val img = el.selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { el.text() }
        if (title.isBlank()) return null

        return newMovieSearchResponse(
            title,
            mainUrl + href,
            TvType.Anime
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
