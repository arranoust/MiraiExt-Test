package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSailProviderPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(AnimeSailProvider())
    }
}
