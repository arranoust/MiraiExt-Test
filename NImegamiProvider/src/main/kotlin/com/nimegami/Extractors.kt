package com.nimegami

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray

class Berkasdrive : ExtractorApi() {

    override val name = "Nimegami"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override suspend fun getExtractorLink(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1️⃣ Decode Base64 → JSON
            val decoded = String(
                Base64.decode(url, Base64.DEFAULT)
            )

            val jsonArray = JSONArray(decoded)

            // 2️⃣ Loop kualitas (360p, 480p, 720p, 1080p)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val qualityText = obj.getString("format")
                val streamUrl = obj
                    .getJSONArray("url")
                    .getString(0)

                // 3️⃣ Request ke streaming.php (WAJIB REFERER)
                val response = app.get(
                    streamUrl,
                    headers = mapOf(
                        "Referer" to "https://dl.berkasdrive.com/"
                    ),
                    allowRedirects = true
                )

                // 4️⃣ Ambil URL FINAL MP4
                val finalUrl = response.url

                if (!finalUrl.endsWith(".mp4") && !finalUrl.contains(".mp4")) continue

                val quality = when {
                    qualityText.contains("1080") -> Qualities.P1080.value
                    qualityText.contains("720") -> Qualities.P720.value
                    qualityText.contains("480") -> Qualities.P480.value
                    else -> Qualities.P360.value
                }

                callback(
                    ExtractorLink(
                        source = "Nimegami",
                        name = qualityText,
                        url = finalUrl,
                        referer = "https://dl.berkasdrive.com/",
                        quality = quality,
                        isM3u8 = false
                    )
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}