package com.nimegami

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

package com.nimegami

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class Berkasdrive : ExtractorApi() {
    override val name = "Berkasdrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override fun canHandle(url: String): Boolean {
        return url.contains("berkasdrive.com") ||
               url.contains("aplikasigratis.net")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // fetch halaman streaming.php
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
            }
        )
    }
}
