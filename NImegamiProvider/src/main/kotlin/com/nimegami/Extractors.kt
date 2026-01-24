package com.nimegami

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Berkasdrive : ExtractorApi() {

    override val name = "Berkasdrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // streaming.php → redirect ke CDN mp4
        val response = app.get(
            url,
            referer = referer ?: "https://dl.berkasdrive.com/"
        )

        val finalUrl = response.url
        if (!finalUrl.endsWith(".mp4")) return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://dl.berkasdrive.com/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}