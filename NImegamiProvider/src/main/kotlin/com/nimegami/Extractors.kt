package com.nimegami

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Berkasdrive : ExtractorApi() {

    override val name = "Berkasdrive"
    override val mainUrl = "https://aplikasigratis.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // URL SUDAH MP4 → LANGSUNG KIRIM
        callback.invoke(
            newExtractorLink(
                name,
                name,
                url,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://dl.berkasdrive.com/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}