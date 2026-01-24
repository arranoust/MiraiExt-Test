package com.nimegami

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class Berkasdrive : ExtractorApi() {
    override val name = "Berkasdrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // fetch streaming.php page
        val res = app.get(url, referer = referer)

        // find mp4 URL + token
        val videoUrl = Regex(
            "(https?:\\\\?/\\\\?/[^\"']+\\.mp4\\?token=[^\"'\\s]+)"
        ).find(res.text)
            ?.groupValues
            ?.get(1)
            ?.replace("\\/", "/")
            ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                videoUrl,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = url
            }
        )
    }
}