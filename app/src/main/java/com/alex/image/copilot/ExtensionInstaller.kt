package com.alex.image.copilot

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.FileProvider
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExtensionInstaller(private val app: CopilotApplication) {

    private val prefs = app.getSharedPreferences("extension_bootstrap", Context.MODE_PRIVATE)

    private val controller: WebExtensionController
        get() = app.geckoRuntime.webExtensionController

    fun installRequiredOnFirstLaunch() {
        if (prefs.getBoolean(KEY_INSTALL_DONE, false)) {
            Log.i(TAG, "Bootstrap: already run, skipping")
            return
        }
        Thread {
            val urls = listOf(
                "https://addons.mozilla.org/firefox/downloads/file/5055786/darkreader-4.9.133.xpi",
                "https://addons.mozilla.org/firefox/downloads/file/5122014/ghostery-10.6.8.xpi"
            )
            Log.i(TAG, "Bootstrap: starting installation of ${urls.size} extensions")
            installSequentially(urls)
            prefs.edit { putBoolean(KEY_INSTALL_DONE, true) }
            Log.i(TAG, "Bootstrap: finished")
        }.start()
    }

    fun installMissingUrls(urls: List<String>) {
        if (urls.isEmpty()) return
        Thread { installSequentially(urls) }.start()
    }

    private fun installSequentially(urls: List<String>) {
        for (url in urls) {
            try {
                Log.i(TAG, "Bootstrap: attempt install from URL: $url")
                val directOk = attemptInstallBlocking(url)
                if (directOk) {
                    Log.i(TAG, "Bootstrap: install from URL succeeded: $url")
                    continue
                }

                Log.i(TAG, "Bootstrap: direct install failed, attempting download fallback for $url")
                val local = downloadXpiToCache(url)
                if (local != null) {
                    val fileUri = local.toURI().toString()
                    Log.i(TAG, "Bootstrap: trying install from local file: $fileUri")
                    val localOk = attemptInstallBlocking(fileUri)
                    if (localOk) {
                        Log.i(TAG, "Bootstrap: install from local file succeeded: ${local.absolutePath}")
                        continue
                    } else {
                        Log.w(TAG, "Bootstrap: install from local file also failed: ${local.absolutePath}")
                    }

                    // Try FileProvider/content:// fallback
                    try {
                        val contentUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", local)
                        Log.i(TAG, "Bootstrap: trying install from content URI: $contentUri")
                        val contentOk = attemptInstallBlocking(contentUri.toString())
                        if (contentOk) {
                            Log.i(TAG, "Bootstrap: install from content URI succeeded: $contentUri")
                            continue
                        } else {
                            Log.w(TAG, "Bootstrap: install from content URI failed: $contentUri")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Bootstrap: FileProvider fallback failed: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "Bootstrap: download fallback failed for $url (could not download)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bootstrap: exception while installing $url: ${e.message}", e)
            }
        }
    }

    private fun attemptInstallBlocking(uri: String, timeoutSeconds: Long = 30): Boolean {
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)

        try {
            controller.install(uri).then(
                { extension: WebExtension? ->
                    if (extension != null) {
                        val name = extension.metaData.name ?: extension.id ?: "<unknown>"
                        Log.i(TAG, "install.then: succeeded for $uri -> $name")
                        success.set(true)

                        // Ensure enabled
                        try {
                            val enableLatch = CountDownLatch(1)
                            controller.enable(extension, WebExtensionController.EnableSource.USER).accept({
                                Log.i(TAG, "Enabled extension: $name")
                                enableLatch.countDown()
                            }, { err ->
                                Log.w(TAG, "Enable failed for $name: ${err?.message}")
                                enableLatch.countDown()
                            })
                            enableLatch.await(10, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error while enabling extension $name: ${e.message}", e)
                        }
                    } else {
                        Log.w(TAG, "install.then: returned null extension for $uri")
                        success.set(false)
                    }
                    latch.countDown()
                    GeckoResult.fromValue(extension)
                },
                { throwable: Throwable ->
                    Log.w(TAG, "install.then: failed for $uri: ${throwable.message}", throwable)
                    success.set(false)
                    latch.countDown()
                    GeckoResult.fromValue(null)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "install() call threw for $uri: ${e.message}", e)
            return false
        }

        try {
            val awaited = latch.await(timeoutSeconds, TimeUnit.SECONDS)
            if (!awaited) {
                Log.w(TAG, "install() timed out for $uri after ${timeoutSeconds}s")
                return false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "install() wait interrupted for $uri", e)
            return false
        }
        return success.get()
    }

    private fun downloadXpiToCache(urlString: String): File? {
        var conn: HttpURLConnection? = null
        var input: BufferedInputStream? = null
        var out: FileOutputStream? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Download failed, HTTP $code for $urlString")
                return null
            }
            val contentType = conn.contentType ?: ""
            Log.i(TAG, "Downloading XPI from $urlString (content-type=$contentType)")

            val fileName = guessFileName(urlString, conn) ?: "extension_${System.currentTimeMillis()}.xpi"
            val outFile = File(app.cacheDir, fileName)
            input = BufferedInputStream(conn.inputStream)
            out = FileOutputStream(outFile)
            val buffer = ByteArray(8 * 1024)
            var read: Int
            while (true) {
                read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.flush()
            Log.i(TAG, "Downloaded XPI to ${outFile.absolutePath}")
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download XPI from $urlString: ${e.message}", e)
            null
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    private fun guessFileName(urlString: String, conn: HttpURLConnection): String? {
        try {
            val disp = conn.getHeaderField("Content-Disposition")
            if (!disp.isNullOrEmpty()) {
                val idx = disp.indexOf("filename=")
                if (idx >= 0) {
                    var name = disp.substring(idx + 9).trim('"', ' ', ';')
                    if (name.isNotBlank()) return name
                }
            }
        } catch (e: Exception) {
            // ignored
        }
        return try {
            val path = URL(urlString).path
            val last = path.substringAfterLast('/')
            if (last.isNotBlank()) last else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ExtensionInstaller"
        private const val KEY_INSTALL_DONE = "bootstrap_install_done"
    }
}
