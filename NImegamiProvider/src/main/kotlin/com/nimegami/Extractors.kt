package com.nimegami

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class Berkasdrive : ExtractorApi() {
    override val name = "Berkasdrive"
    override val mainUrl = "https://aplikasigratis.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // pastikan hanya handle domain ini
        if (
            !url.contains("aplikasigratis.net") &&
            !url.contains("berkasdrive.com")
        ) return

        val res = app.get(url, referer = referer)

        val videoUrl = Regex(
            "https://[^\"'\\s]+\\.mp4\\?token=[^\"'\\s]+"
        ).find(res.text)?.value ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                videoUrl,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}