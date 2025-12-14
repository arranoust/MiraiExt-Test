package com.arranoust

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    // Homepage hanya Terbaru
    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d" to "Anime Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data.format(page)}"
        val document = app.get(url).document

        // Ambil semua anime terbaru
        val items = document.select("li[itemtype='http://schema.org/CreativeWork']").mapNotNull { el ->
            val a = el.selectFirst("div.thumb a") ?: return@mapNotNull null
            val title = el.selectFirst("h2.entry-title a")?.text()?.trim() ?: a.attr("title") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val poster = fixUrlNull(a.selectFirst("img")?.attr("src")) ?: return@mapNotNull null

            // buat anime card
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty()
        )
    }
}
