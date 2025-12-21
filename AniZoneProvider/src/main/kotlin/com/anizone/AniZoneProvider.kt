package com.anizone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    override val mainPage = mainPageOf(
        "2" to "Latest TV Series",
        "4" to "Latest Movies",
        "6" to "Latest Web"
    )

    // ===== Livewire State =====
    private var cookies = mutableMapOf<String, String>()
    private var csrfToken = ""
    private var wireSnapshot = ""

    init {
        val res = Jsoup.connect("$mainUrl/anime")
            .method(Connection.Method.GET)
            .execute()

        cookies.putAll(res.cookies())
        val doc = res.parse()

        csrfToken = doc.select("script[data-csrf]").attr("data-csrf")
        wireSnapshot = extractSnapshot(doc)
    }

    // ===== MAIN PAGE =====
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = livewireHtml(
            updates = mapOf("type" to request.data),
            loadMore = true
        )

        val items = doc.select("div[wire:key]")
            .let { if (page == 1) it else it.takeLast(12) }

        return newHomePageResponse(
            HomePageList(
                request.name,
                items.map { parseCard(it) },
                isHorizontalImages = false
            ),
            hasNext = hasNextPage(doc)
        )
    }

    // ===== SEARCH =====
    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = livewireHtml(
            updates = mapOf("search" to query),
            remember = false
        )
        return doc.select("div[wire:key]").map { parseCard(it) }
    }

    // ===== LOAD DETAIL =====
    override suspend fun load(url: String): LoadResponse {

        var doc = Jsoup.connect(url).get()

        csrfToken = doc.select("script[data-csrf]").attr("data-csrf")
        wireSnapshot = extractSnapshot(doc)

        val title = doc.selectFirst("h1")!!.text()
        val poster = doc.selectFirst("main img")?.attr("src")
        val plot = doc.selectFirst(".sr-only + div")?.text().orEmpty()

        val info = doc.select("span.inline-block").map { it.text() }
        val year = info.getOrNull(3)?.toIntOrNull()
        val status = when (info.getOrNull(1)) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> null
        }

        val genres = doc.select("a[wire:navigate][wire:key]").map { it.text() }

        while (hasNextPage(doc)) {
            doc = livewireHtml(
                updates = emptyMap(),
                loadMore = true
            )
        }

        val episodes = doc.select("li[x-data]").map { parseEpisode(it) }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            tags = genres
            this.year = year
            showStatus = status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    // ===== STREAM =====
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
                SubtitleFile(it.attr("label"), it.attr("src"))
            )
        }

        callback(
            newExtractorLink(
                doc.selectFirst("span.truncate")?.text() ?: name,
                name,
                player.attr("src"),
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }

    // ===== HELPERS =====
    private fun parseCard(el: Element): SearchResponse =
        newMovieSearchResponse(
            el.selectFirst("img")?.attr("alt") ?: "",
            el.selectFirst("a")?.attr("href") ?: "",
            TvType.Movie
        ) {
            posterUrl = el.selectFirst("img")?.attr("src")
        }

    private fun parseEpisode(el: Element) =
        newEpisode(el.selectFirst("a")?.attr("href") ?: "") {
            name = el.selectFirst("h3")?.text()?.substringAfter(":")?.trim()
            posterUrl = el.selectFirst("img")?.attr("src")
            season = 0
            date = el.select("span.line-clamp-1")
                .getOrNull(1)?.text()?.let {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .parse(it)?.time
                } ?: 0
        }

    private fun livewireHtml(
        updates: Map<String, String>,
        loadMore: Boolean = false,
        remember: Boolean = true
    ): Document {

        val calls =
            if (loadMore)
                listOf(mapOf("path" to "", "method" to "loadMore", "params" to emptyList<String>()))
            else emptyList()

        val payload = mapOf(
            "_token" to csrfToken,
            "components" to listOf(
                mapOf(
                    "snapshot" to wireSnapshot,
                    "updates" to updates,
                    "calls" to calls
                )
            )
        )

        val res = Jsoup.connect("$mainUrl/livewire/update")
            .method(Connection.Method.POST)
            .cookies(cookies)
            .ignoreContentType(true)
            .header("Content-Type", "application/json")
            .requestBody(payload.toJson())
            .execute()

        if (remember) {
            cookies.putAll(res.cookies())
            wireSnapshot = JSONObject(res.body())
                .getJSONArray("components")
                .getJSONObject(0)
                .getString("snapshot")
        }

        return Jsoup.parse(
            JSONObject(res.body())
                .getJSONArray("components")
                .getJSONObject(0)
                .getJSONObject("effects")
                .getString("html")
        )
    }

    private fun extractSnapshot(doc: Document): String =
        doc.select("main div[wire:snapshot]")
            .attr("wire:snapshot")
            .replace("&quot;", "\"")

    private fun hasNextPage(doc: Document): Boolean =
        doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]") != null
}
