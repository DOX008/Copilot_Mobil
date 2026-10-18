package com.alex.image.copilot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.alex.image.copilot.CopilotApplication
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/** Håller GeckoSession vid liv över konfigurationsändringar (t.ex. rotation). */
class GeckoSessionViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = (application as CopilotApplication).geckoRuntime

    var session: GeckoSession = newSession()
        private set

    /** True när startsidan redan har laddats i sessionen. */
    var started = false

    private fun newSession(): GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .allowJavascript(true)
            .build()
    )

    /** Öppnar sessionen om den inte redan är öppen. */
    fun ensureOpen(): GeckoSession {
        if (!session.isOpen) session.open(runtime)
        return session
    }

    /** Skapar en ny session efter krasch/kill. */
    fun recreate(): GeckoSession {
        session.close()
        session = newSession()
        started = false
        return session
    }

    override fun onCleared() {
        session.close()
        super.onCleared()
    }
}
