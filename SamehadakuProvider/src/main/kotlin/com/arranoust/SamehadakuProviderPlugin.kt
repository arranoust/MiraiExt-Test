package com.arranoust

import com.lagradost.cloudstream3.APIWrapper

class SamehadakuProviderPlugin : APIWrapper() {
    override val mainAPI = SamehadakuProvider()
}