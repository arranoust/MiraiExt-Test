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
        // URL sesuai page
        val pageUrl = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(pageUrl).document

        // Ambil container utama homepage (hanya anime terbaru)
        val items = document.select("div.latest-anime a[itemprop=url]").mapNotNull { element ->
            val img = element.selectFirst("img")?.attr("src") ?: return@mapNotNull null
            val title = element.attr("title").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val link = element.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            // Buat anime card CloudStream
            newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = img
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = "Update Terbaru",
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty() 
        )
    }
}
