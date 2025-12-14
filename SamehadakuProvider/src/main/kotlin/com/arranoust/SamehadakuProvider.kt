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
    override fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        val items = document.select("a.series").mapNotNull { element ->
            val title = element.selectFirst("span.judul")?.text() ?: return@mapNotNull null
            val link = element.attr("href")
            val poster = element.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = poster
            }
        }

        return HomePageResponse(
            listOf(HomePageList("Update Terbaru", items)),
            hasNext = false
        )
    }
}
