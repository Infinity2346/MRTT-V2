package com.mrtt.client

data class AppRoutingPatch(
    val excluded: String? = null,
    val whitelist: String? = null,
    val isWhitelist: Boolean? = null,
    val directFiltered: String? = null,
    val directRaw: String? = null,
)
