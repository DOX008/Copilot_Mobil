package com.alex.image.copilot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class ExtensionInstaller(private val app: CopilotApplication) {
    private val prefs: SharedPreferences =
        app.getSharedPreferences("extension_bootstrap", Context.MODE_PRIVATE)

    private val controller: WebExtensionController
        get() = app.geckoRuntime.webExtensionController

    fun installRequiredOnFirstLaunch() {
        if (prefs.getBoolean(KEY_INSTALL_DONE, false)) return

        val urls = listOf(
            "https://addons.mozilla.org/firefox/downloads/file/5055786/darkreader-4.9.133.xpi",
            "https://addons.mozilla.org/firefox/downloads/file/5122014/ghostery-10.6.8.xpi"
        )

        installSequentially(urls, 0, onComplete = {
            prefs.edit { putBoolean(KEY_INSTALL_DONE, true) }
        })
    }

    fun installKnownExtensions(): GeckoResult<List<WebExtension>> {
        val installResult = GeckoResult<List<WebExtension>>()
        val urls = listOf(
            "https://addons.mozilla.org/firefox/downloads/file/5055786/darkreader-4.9.133.xpi",
            "https://addons.mozilla.org/firefox/downloads/file/5122014/ghostery-10.6.8.xpi"
        )

        val installed = mutableListOf<WebExtension>()
        installSequentiallyWithCollector(urls, 0, installed) {
            installResult.complete(installed)
        }
        return installResult
    }

    private fun installSequentially(
        urls: List<String>,
        index: Int,
        onComplete: () -> Unit,
    ) {
        if (index >= urls.size) {
            onComplete()
            return
        }

        val url = urls[index]
        controller.install(url).then(
            { extension ->
                Log.i(TAG, "Installed extension from $url: ${extension.metaData.name ?: extension.id}")
                installSequentially(urls, index + 1, onComplete)
            },
            { throwable ->
                Log.w(TAG, "Unable to install $url", throwable)
                installSequentially(urls, index + 1, onComplete)
            }
        )
    }

    private fun installSequentiallyWithCollector(
        urls: List<String>,
        index: Int,
        collector: MutableList<WebExtension>,
        onComplete: () -> Unit,
    ) {
        if (index >= urls.size) {
            onComplete()
            return
        }

        val url = urls[index]
        controller.install(url).then(
            { extension ->
                collector.add(extension)
                installSequentiallyWithCollector(urls, index + 1, collector, onComplete)
            },
            { throwable ->
                Log.w(TAG, "Unable to install $url", throwable)
                installSequentiallyWithCollector(urls, index + 1, collector, onComplete)
            }
        )
    }

    companion object {
        private const val TAG = "ExtensionInstaller"
        private const val KEY_INSTALL_DONE = "bootstrap_install_done"
    }
}
