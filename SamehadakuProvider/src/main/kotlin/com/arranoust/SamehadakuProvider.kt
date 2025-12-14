package com.arranoust

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class SamehadakuProvider : MainAPI() {

    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "id"

    override val hasMainPage = true

    // Homepage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("a[itemprop=url]").mapNotNull { element ->
        val title = element.attr("title") ?: return@mapNotNull null
        val link = element.attr("href") ?: return@mapNotNull null
        val poster = element.selectFirst("img")?.attr("src")

        newAnimeSearchResponse(title, link, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    return newHomePageResponse("Update Terbaru", items)
}