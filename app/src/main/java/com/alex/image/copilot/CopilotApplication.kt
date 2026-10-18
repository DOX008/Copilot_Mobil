package com.alex.image.copilot

import android.app.Application
import android.content.pm.ApplicationInfo
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class CopilotApplication : Application() {

    // Endast en GeckoRuntime får finnas per process
    val geckoRuntime: GeckoRuntime by lazy {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(debuggable)
            .javaScriptEnabled(true)
            // Krävs för att kunna installera tillägg från addons.mozilla.org
            .extensionsWebAPIEnabled(true)
            .build()

        GeckoRuntime.create(this, settings).also { runtime ->
            // Tillåtande content blocking så att Medvind-inloggning inte bryts
            runtime.settings.contentBlocking.apply {
                setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
                setAntiTracking(ContentBlocking.AntiTracking.NONE)
                setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
            }
        }
    }
}
