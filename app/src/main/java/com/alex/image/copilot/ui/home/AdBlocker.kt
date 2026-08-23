package com.alex.image.copilot.ui.home

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlocker {

    // Minimal blocklista. Du kan fylla på efterhand.
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "adsystem.com",
        "adsystem.google.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com"
    )

    fun shouldBlock(host: String?): Boolean {
        val h = host?.lowercase(Locale.US) ?: return false
        return blockedHosts.any { h == it || h.endsWith(".$it") }
    }

    fun emptyResponse(): WebResourceResponse {
        // Tomt svar (0 bytes). Funkar för många ad-requests.
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
