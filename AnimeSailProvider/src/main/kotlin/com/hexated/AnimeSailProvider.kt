package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.SubtitleFile
import kotlin.text.Regex

class AnimeSail : MainAPI() {
    // NOTE: if you have a domain, prefer it here instead of raw IP. Keep IP for fallback if needed.
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t.trim()) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        // Add a stable user-agent and sensible headers + referer/cookies.
        val headers = mutableMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0 Safari/537.36"
        )
        val referer = ref ?: mainUrl
        return app.get(url, headers = headers, cookies = mapOf("_as_ipin_ct" to "ID"), referer = referer)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
        "$mainUrl/genres/donghua/page/" to "Donghua",
        "$mainUrl/genres/action/page/" to "Action",
        // ... truncated for brevity; keep the rest from your original list if desired
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").mapNotNull { it.safeToSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.safeToSearchResult(): AnimeSearchResponse? {
        return try {
            val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
            val title = this.selectFirst(".tt > h2")?.text()?.trim() ?: return null
            val posterUrl = fixUrl(this.selectFirst("div.limit img")?.attr("src") ?: "")
            val epNum = Regex("Episode\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addSub(epNum)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=${query.encodeUrlPath()}"
        val document = request(link).document
        return document.select("div.listupd article").mapNotNull { it.safeToSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val titleRaw = document.selectFirst("h1.entry-title")?.text() ?: ""
        val title = titleRaw.replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val typeRaw = document.select("tbody th:contains(Tipe)").firstOrNull()?.nextElementSibling()?.text() ?: ""
        val type = getType(typeRaw)
        val year = document.select("tbody th:contains(Dirilis)").firstOrNull()?.nextElementSibling()?.text()?.trim()?.toIntOrNull()

        // Episodes - robust parsing and safety checks
        val episodes = document.select("ul.daftar > li").mapNotNull { li ->
            val anchor = li.selectFirst("a") ?: return@mapNotNull null
            val episodeLink = fixUrl(anchor.attr("href"))
            val episodeName = anchor.text().trim()
            val episodeNumber = Regex("Episode\\s?(\\d+)").find(episodeName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(episodeLink) {
                this.name = episodeName
                this.episode = episodeNumber
            }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(document.select("tbody th:contains(Status)").firstOrNull()?.nextElementSibling()?.text()?.trim() ?: "")
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags = document.select("tbody th:contains(Genre)").firstOrNull()?.nextElementSibling()?.select("a")?.map { it.text() } ?: listOf()
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document

        // Concurrently process mirrors safely. Each async is protected by safeApiCall.
        coroutineScope {
            val options = document.select(".mobius > .mirror > option")

            options.mapNotNull { element ->
                // parse element safely, return null to skip
                val encoded = element.attr("data-em")
                if (encoded.isNullOrBlank()) return@mapNotNull null
                element to encoded
            }.map { (element, encoded) ->
                async {
                    safeApiCall {
                        val iframeSrc = runCatching {
                            val decoded = base64Decode(encoded)
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: ""
                        }.getOrElse { "" }

                        if (iframeSrc.isBlank()) return@safeApiCall

                        val quality = getIndexQuality(element.text())

                        when {
                            iframeSrc.startsWith("$mainUrl/utils/player/arch/") || iframeSrc.startsWith("$mainUrl/utils/player/race/") -> {
                                // direct player pages that contain <source>
                                val doc = request(iframeSrc, ref = data).document
                                val src = doc.selectFirst("source")?.attr("src") ?: return@safeApiCall
                                val sourceName = when {
                                    iframeSrc.contains("/arch/") -> "Arch"
                                    iframeSrc.contains("/race/") -> "Race"
                                    else -> this@AnimeSail.name
                                }
                                callback.invoke(
                                    ExtractorLink(
                                        source = sourceName,
                                        name = sourceName,
                                        url = src,
                                        referer = mainUrl,
                                        quality = quality,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                            }

                            iframeSrc.startsWith("https://aghanim.xyz/tools/redirect/") -> {
                                // special redirect pattern
                                val id = iframeSrc.substringAfter("id=").substringBefore("&token")
                                val link = "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                                loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                            }

                            iframeSrc.startsWith("$mainUrl/utils/player/framezilla/") || iframeSrc.startsWith("https://uservideo.xyz") -> {
                                val nested = request(iframeSrc, ref = data).document.selectFirst("iframe")?.attr("src") ?: ""
                                if (nested.isNotBlank()) loadFixedExtractor(fixUrl(nested), quality, mainUrl, subtitleCallback, callback)
                            }

                            else -> {
                                loadFixedExtractor(iframeSrc, quality, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return true
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // loadExtractor is assumed to internally handle extractor logic and call link callback when found.
        // We avoid creating new CoroutineScopes here to keep lifecycle aligned with Cloudstream.
        loadExtractor(url, referer, subtitleCallback) { link ->
            // directly invoke callback on the same coroutine context. Avoid launching new scopes.
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = link.url,
                    referer = link.referer,
                    quality = link.quality.takeIf { it != 0 } ?: quality,
                    type = link.type,
                    extractorData = link.extractorData,
                    headers = link.headers
                )
            )
        }
    }
}