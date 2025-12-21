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

    // ========= METADATA =========
    override var name = "AniZone"
    override var mainUrl = "https://anizone.to"
    override var lang = "en"
    override var version = 1

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "2" to "Latest TV Series",
        "4" to "Latest Movies",
        "6" to "Latest Web"
    )

    // ========= SESSION =========
    private val session by lazy { LivewireSession(mainUrl) }

    // ========= MAIN PAGE =========
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = session.requestHtml(
            updates = mapOf("type" to request.data),
            calls = session.loadMoreCall
        )

        val cards = doc.select("div[wire:key]")
            .let { if (page == 1) it else it.takeLast(12) }

        return newHomePageResponse(
            HomePageList(
                request.name,
                cards.map(::mapCard),
                isHorizontalImages = false
            ),
            hasNext = session.hasMore(doc)
        )
    }

    // ========= SEARCH =========
    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String): List<SearchResponse> =
        session.requestHtml(
            updates = mapOf("search" to query),
            remember = false
        ).select("div[wire:key]")
            .map(::mapCard)

    // ========= LOAD DETAIL =========
    override suspend fun load(url: String): LoadResponse {
        val res = Jsoup.connect(url).get()
        val doc = res.parse()

        val localSession = session.fork(doc, res.cookies())

        val meta = extractMeta(doc)

        val episodeDoc = localSession.consumeAllPages()

        val episodes = episodeDoc
            .select("li[x-data]")
            .map(::mapEpisode)

        return newAnimeLoadResponse(meta.title, url, TvType.Anime) {
            posterUrl = meta.poster
            plot = meta.plot
            tags = meta.genres
            year = meta.year
            showStatus = meta.status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    // ========= STREAM =========
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

    // ========= MAPPERS =========
    private fun mapCard(el: Element): SearchResponse =
        newMovieSearchResponse(
            el.selectFirst("img")?.attr("alt") ?: "",
            el.selectFirst("a")?.attr("href") ?: "",
            TvType.Movie
        ) {
            posterUrl = el.selectFirst("img")?.attr("src")
        }

    private fun mapEpisode(el: Element) =
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

    // ========= META =========
    private fun extractMeta(doc: Document): MetaData {
        val info = doc.select("span.inline-block").map { it.text() }

        return MetaData(
            title = doc.selectFirst("h1")!!.text(),
            poster = doc.selectFirst("main img")?.attr("src"),
            plot = doc.selectFirst(".sr-only + div")?.text().orEmpty(),
            year = info.getOrNull(3)?.toIntOrNull(),
            status = when (info.getOrNull(1)) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> null
            },
            genres = doc.select("a[wire:navigate][wire:key]").map { it.text() }
        )
    }

    // ========= DATA =========
    private data class MetaData(
        val title: String,
        val poster: String?,
        val plot: String,
        val year: Int?,
        val status: ShowStatus?,
        val genres: List<String>
    )

    // ========= LIVEWIRE ENGINE =========
    private class LivewireSession(private val baseUrl: String) {

        private var cookies = mutableMapOf<String, String>()
        private var token = ""
        private var snapshot = ""

        val loadMoreCall = listOf(
            mapOf("path" to "", "method" to "loadMore", "params" to emptyList<String>())
        )

        init {
            val res = Jsoup.connect("$baseUrl/anime").get()
            cookies.putAll(res.cookies())
            val doc = res.parse()
            token = doc.select("script[data-csrf]").attr("data-csrf")
            snapshot = extractSnapshot(doc)
        }

        fun fork(doc: Document, newCookies: Map<String, String>) =
            LivewireSession(baseUrl).also {
                it.cookies = newCookies.toMutableMap()
                it.token = token
                it.snapshot = extractSnapshot(doc)
            }

        fun requestHtml(
            updates: Map<String, String>,
            calls: List<Map<String, Any>> = emptyList(),
            remember: Boolean = true
        ): Document {
            val json = post(updates, calls, remember)
            return Jsoup.parse(
                json.getJSONArray("components")
                    .getJSONObject(0)
                    .getJSONObject("effects")
                    .getString("html")
            )
        }

        fun consumeAllPages(): Document {
            var doc = requestHtml(emptyMap(), loadMoreCall)
            while (hasMore(doc)) {
                doc = requestHtml(emptyMap(), loadMoreCall)
            }
            return doc
        }

        fun hasMore(doc: Document) =
            doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]") != null

        private fun post(
            updates: Map<String, String>,
            calls: List<Map<String, Any>>,
            remember: Boolean
        ): JSONObject {

            val payload = mapOf(
                "_token" to token,
                "components" to listOf(
                    mapOf(
                        "snapshot" to snapshot,
                        "updates" to updates,
                        "calls" to calls
                    )
                )
            )

            val res = Jsoup.connect("$baseUrl/livewire/update")
                .method(Connection.Method.POST)
                .cookies(cookies)
                .ignoreContentType(true)
                .header("Content-Type", "application/json")
                .requestBody(payload.toJson())
                .execute()

            if (remember) {
                cookies.putAll(res.cookies())
                snapshot = JSONObject(res.body())
                    .getJSONArray("components")
                    .getJSONObject(0)
                    .getString("snapshot")
            }

            return JSONObject(res.body())
        }

        private fun extractSnapshot(doc: Document): String =
            doc.select("main div[wire:snapshot]")
                .attr("wire:snapshot")
                .replace("&quot;", "\"")
    }
}
