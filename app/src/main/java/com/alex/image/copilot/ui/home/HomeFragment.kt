package com.alex.image.copilot.ui.home

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.alex.image.copilot.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fileChooserCallback?.onReceiveValue(uris?.toTypedArray())
            fileChooserCallback = null
        }

    private fun initialUrl(): String {
        return arguments?.getString("url") ?: "https://github.com/copilot"
    }

    private fun isAdBlockEnabledFor(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "onlinenotepad.io" || host.endsWith(".onlinenotepad.io")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        val webView = binding.webView
        val startUrl = initialUrl()

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            useWideViewPort = true
            loadWithOverviewMode = true

            userAgentString = userAgentString + " CopilotWrapper/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // Aktivera bara adblock för anotepad
                if (!isAdBlockEnabledFor(startUrl)) {
                    return super.shouldInterceptRequest(view, request)
                }

                val host = request.url.host
                if (AdBlocker.shouldBlock(host)) {
                    return AdBlocker.emptyResponse()
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val url = uri.toString()

                if (isAllowedUrl(url)) return false

                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                super.onPageFinished(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Popup-hantering för nya fönster (t.ex. login/röstinmatning)
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val context = view.context
                val newWebView = WebView(context)
                newWebView.settings.javaScriptEnabled = true
                newWebView.settings.javaScriptCanOpenWindowsAutomatically = true
                newWebView.settings.setSupportMultipleWindows(true)
                newWebView.settings.userAgentString = view.settings.userAgentString
                CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)

                val dialog = Dialog(context)
                dialog.setContentView(newWebView)
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                dialog.show()

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                newWebView.webChromeClient = this
                newWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(wv: WebView?, url: String?) {
                        // Du kan lägga kod här om du vill automatstänga en popup vid viss URL
                    }
                }
                return true
            }

            // Filväljare (tillåter filuppladdning)
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val types = fileChooserParams.acceptTypes
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf("*/*") }
                    .toTypedArray()

                fileChooserLauncher.launch(types)
                return true
            }

            // Tillåt mikrofonåtkomst (viktig för Google/Gemini/etc)
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request?.resources ?: arrayOf())
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val binding = _binding
        binding?.webView?.saveState(outState)
    }

    private fun isAllowedUrl(url: String): Boolean {
        return true
    }

    fun onBackPressed(): Boolean {
        val wv = binding.webView
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    override fun onDestroyView() {
        runCatching {
            binding.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                webChromeClient = null
                setWebViewClient(WebViewClient())
                clearHistory()
                clearCache(false)
                destroy()
            }
        }

        _binding = null
        super.onDestroyView()
    }
}
