package com.anizone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnizoneProvider : MainAPI() {

    override var name = "AniZone"
    override var mainUrl = "https://anizone.to"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Anime)

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

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

        val doc = app.get("$mainUrl/anime?page=$page").document

        val items = doc.select("div.bg-slate-900.rounded-lg")
            .mapNotNull { parseCard(it) }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val doc = app.get(
            "$mainUrl/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}"
        ).document

        return doc.select("div.bg-slate-900.rounded-lg")
            .mapNotNull { parseCard(it) }
    }

    // =========================
    // LOAD DETAIL
    // =========================
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title =
            doc.selectFirst("h1")?.text()
                ?: throw ErrorLoadingException("Title not found")

        val poster =
            fixUrlNull(doc.selectFirst("main img")?.attr("src"))

        val plot =
            doc.selectFirst(".sr-only + div")?.text()

        val info =
            doc.select("span.inline-block").map { it.text() }

        val year =
            info.firstOrNull { it.matches(Regex("\\d{4}")) }?.toIntOrNull()

        val status = when {
            info.any { it.equals("Completed", true) } -> ShowStatus.Completed
            info.any { it.equals("Ongoing", true) } -> ShowStatus.Ongoing
            else -> null
        }

        val genres =
            doc.select("a[href*=\"/tag/\"]").map { it.text() }

        val episodes =
            doc.select("li[x-data]").mapNotNull { parseEpisode(it) }

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
            val src = it.attr("src")
            if (src.isNotBlank()) {
                subtitleCallback(
                    SubtitleFile(
                        it.attr("label"),
                        fixUrl(src)
                    )
                )
            }
        }

        callback(
            ExtractorLink(
                name,
                name,
                fixUrl(player.attr("src")),
                "",
                Qualities.Unknown,
                true // isM3u8
            )
        )

        return true
    }

    // =========================
    // HELPERS
    // =========================
    private fun parseCard(el: Element): SearchResponse? {
        val link = el.selectFirst("a[href*=\"/anime/\"]") ?: return null
        val img = el.selectFirst("img")

        val title =
            img?.attr("alt")
                ?.ifBlank { link.attr("title") }
                ?: return null

        return newAnimeSearchResponse(
            title,
            fixUrl(link.attr("href"))
        ) {
            posterUrl = fixUrlNull(img?.attr("src"))
        }
    }

    private fun parseEpisode(el: Element): Episode? {
        val a = el.selectFirst("a") ?: return null

        val name =
            el.selectFirst("h3")
                ?.text()
                ?.substringAfter(":")
                ?.trim()

        val date =
            el.select("span.line-clamp-1")
                .getOrNull(1)
                ?.text()
                ?.let {
                    runCatching {
                        SimpleDateFormat(
                            "yyyy-MM-dd",
                            Locale.getDefault()
                        ).parse(it)?.time
                    }.getOrNull()
                }

        return newEpisode(fixUrl(a.attr("href"))) {
            this.name = name
            this.date = date
            this.posterUrl =
                fixUrlNull(el.selectFirst("img")?.attr("src"))
        }
    }
}
