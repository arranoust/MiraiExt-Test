package com.arranoust

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AniZoneProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniZoneProvider())
    }
}