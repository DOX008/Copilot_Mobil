package com.alex.image.copilot.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.alex.image.copilot.databinding.FragmentHomeBinding
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.WebRequestError

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val sessionHolder: GeckoSessionViewModel by viewModels()

    private var canGoBack = false

    // Filväljare (filuppladdning)
    private var pendingFilePrompt: PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<PromptDelegate.PromptResponse>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val prompt = pendingFilePrompt
            val result = pendingFileResult
            pendingFilePrompt = null
            pendingFileResult = null
            val ctx = context
            if (prompt != null && result != null) {
                if (uris.isNullOrEmpty() || ctx == null) {
                    result.complete(prompt.dismiss())
                } else {
                    result.complete(prompt.confirm(ctx, uris.toTypedArray()))
                }
            }
        }

    // Android-runtimebehörigheter (mikrofon, kamera, plats)
    private var pendingAndroidPermissionCallback: PermissionDelegate.Callback? = null
    private val androidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val callback = pendingAndroidPermissionCallback
            pendingAndroidPermissionCallback = null
            if (grants.isNotEmpty() && grants.values.all { it }) callback?.grant() else callback?.reject()
        }

    private fun initialUrl(): String {
        return arguments?.getString("url") ?: "https://github.com/copilot"
    }

    // ---------------------------------------------------------------------
    // BankID / externa länkar
    // ---------------------------------------------------------------------

    /** Scheman som GeckoView själv ska hantera. Allt annat skickas till Android. */
    private fun isInternalScheme(scheme: String?): Boolean {
        return when (scheme?.lowercase()) {
            "http", "https", "about", "data", "blob", "file", "javascript", "view-source" -> true
            else -> false
        }
    }

    private fun isBankIdWebLink(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "app.bankid.com" || host.endsWith(".bankid.com") && uri.getQueryParameter("autostarttoken") != null
    }

    /**
     * Försöker hantera URI:n utanför GeckoView (BankID, intent://, mailto:, tel: osv).
     * Returnerar true om URI:n ska blockeras i GeckoView (dvs. vi tog hand om den).
     */
    private fun handleExternalUri(uriString: String): Boolean {
        val ctx = context ?: return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()

        // 1) BankID via https://app.bankid.com/?autostarttoken=...  -> bankid:///?autostarttoken=...
        if (isBankIdWebLink(uri)) {
            val token = uri.getQueryParameter("autostarttoken")
            if (token != null) {
                val redirect = uri.getQueryParameter("redirect") ?: "null"
                val bankIdUri = Uri.parse("bankid:///?autostarttoken=$token&redirect=$redirect")
                if (startExternal(ctx, Intent(Intent.ACTION_VIEW, bankIdUri))) return true
            }
            // Fallback: låt Android försöka med originallänken (App Link till BankID-appen)
            if (startExternal(ctx, Intent(Intent.ACTION_VIEW, uri))) return true
            showBankIdMissing(ctx)
            return true
        }

        // 2) Direkt bankid://-länk
        if (scheme == "bankid") {
            if (!startExternal(ctx, Intent(Intent.ACTION_VIEW, uri))) {
                showBankIdMissing(ctx)
            }
            return true
        }

        // 3) intent://-länkar (Chrome-stil)
        if (scheme == "intent") {
            val intent = runCatching { Intent.parseUri(uriString, Intent.URI_INTENT_SCHEME) }.getOrNull()
            if (intent != null) {
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                if (startExternal(ctx, intent)) return true

                // Fallback-URL om appen saknas
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank() && isInternalScheme(Uri.parse(fallback).scheme)) {
                    sessionHolder.session.loadUri(fallback)
                    return true
                }
            }
            Toast.makeText(ctx, "Kunde inte öppna appen för länken", Toast.LENGTH_LONG).show()
            return true
        }

        // 4) Övriga appscheman (mailto:, tel:, market: osv.)
        if (!isInternalScheme(scheme)) {
            if (!startExternal(ctx, Intent(Intent.ACTION_VIEW, uri))) {
                Toast.makeText(ctx, "Ingen app kan öppna länken", Toast.LENGTH_LONG).show()
            }
            return true
        }

        return false
    }

    private fun startExternal(ctx: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Ingen aktivitet kunde hantera: ${intent.data}", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Säkerhetsfel vid start av: ${intent.data}", e)
            false
        }
    }

    private fun showBankIdMissing(ctx: Context) {
        Toast.makeText(
            ctx,
            "Kunde inte öppna BankID. Kontrollera att BankID-appen är installerad.",
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------------------------------------------------------------------

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        val session = sessionHolder.ensureOpen()
        attachDelegates(session)
        binding.geckoView.setSession(session)

        if (!sessionHolder.started) {
            sessionHolder.started = true
            session.loadUri(initialUrl())
        }

        return binding.root
    }

    private fun attachDelegates(session: GeckoSession) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@HomeFragment.canGoBack = canGoBack
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                Log.d(TAG, "onLoadRequest: $uri (redirect=${request.isRedirect})")

                // BankID / appscheman: skicka till Android och blockera i GeckoView
                if (handleExternalUri(uri)) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                Log.d(TAG, "onNewSession: $uri")

                // BankID / appscheman i nytt fönster
                if (handleExternalUri(uri)) return null

                // Vanliga sidor öppnas i samma session (aldrig extern webbläsare)
                session.loadUri(uri)
                return null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                Log.e(TAG, "onLoadError: code=${error.code}, category=${error.category}, uri=$uri")

                // Om en appscheme ändå hamnade här (t.ex. bankid://), försök öppna via Android
                if (uri != null && !isInternalScheme(runCatching { Uri.parse(uri).scheme }.getOrNull())) {
                    handleExternalUri(uri)
                }
                return null
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                Log.d(TAG, "Title: $title")
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "Content process crashed")
                reopenSession()
            }

            override fun onKill(session: GeckoSession) {
                Log.e(TAG, "Content process killed")
                reopenSession()
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                Log.d(TAG, "Page start: $url")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                Log.d(TAG, "Page stop: success=$success")
            }
        }

        session.permissionDelegate = object : PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: PermissionDelegate.Callback
            ) {
                if (permissions.isNullOrEmpty() || !isAdded) {
                    callback.reject()
                    return
                }
                pendingAndroidPermissionCallback?.reject()
                pendingAndroidPermissionCallback = callback
                androidPermissionLauncher.launch(arrayOf(*permissions))
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                val allow = perm.permission == PermissionDelegate.PERMISSION_GEOLOCATION
                return GeckoResult.fromValue(
                    if (allow) PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else PermissionDelegate.ContentPermission.VALUE_DENY
                )
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out PermissionDelegate.MediaSource>?,
                audio: Array<out PermissionDelegate.MediaSource>?,
                callback: PermissionDelegate.MediaCallback
            ) {
                val v = video?.firstOrNull()
                val a = audio?.firstOrNull()
                if (v == null && a == null) callback.reject() else callback.grant(v, a)
            }
        }

        session.promptDelegate = object : PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: PromptDelegate.FilePrompt
            ): GeckoResult<PromptDelegate.PromptResponse>? {
                if (!isAdded) return GeckoResult.fromValue(prompt.dismiss())
                val result = GeckoResult<PromptDelegate.PromptResponse>()
                pendingFileResult?.complete(pendingFilePrompt?.dismiss())
                pendingFilePrompt = prompt
                pendingFileResult = result

                val types = (prompt.mimeTypes ?: emptyArray())
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf("*/*") }
                    .toTypedArray()
                fileChooserLauncher.launch(types)
                return result
            }

            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: PromptDelegate.AlertPrompt
            ): GeckoResult<PromptDelegate.PromptResponse>? {
                val ctx = context ?: return GeckoResult.fromValue(prompt.dismiss())
                val result = GeckoResult<PromptDelegate.PromptResponse>()
                AlertDialog.Builder(ctx)
                    .setTitle(prompt.title)
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: PromptDelegate.ButtonPrompt
            ): GeckoResult<PromptDelegate.PromptResponse>? {
                val ctx = context ?: return GeckoResult.fromValue(prompt.dismiss())
                val result = GeckoResult<PromptDelegate.PromptResponse>()
                AlertDialog.Builder(ctx)
                    .setTitle(prompt.title)
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                    }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
                return result
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: PromptDelegate.AuthPrompt
            ): GeckoResult<PromptDelegate.PromptResponse>? {
                val ctx = context ?: return GeckoResult.fromValue(prompt.dismiss())
                val result = GeckoResult<PromptDelegate.PromptResponse>()
                val user = EditText(ctx).apply { hint = "Användarnamn" }
                val pass = EditText(ctx).apply {
                    hint = "Lösenord"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                val form = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = dp(ctx, 20)
                    setPadding(pad, pad, pad, 0)
                    addView(user)
                    addView(pass)
                }
                AlertDialog.Builder(ctx)
                    .setTitle(prompt.title)
                    .setMessage(prompt.message)
                    .setView(form)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.complete(
                            prompt.confirm(user.text.toString(), pass.text.toString())
                        )
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
                return result
            }
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    /** Öppnar en ny session och laddar om efter krasch/kill. */
    private fun reopenSession() {
        val currentBinding = _binding ?: return
        val url = initialUrl()
        currentBinding.geckoView.releaseSession()
        val session = sessionHolder.recreate()
        sessionHolder.ensureOpen()
        attachDelegates(session)
        currentBinding.geckoView.setSession(session)
        sessionHolder.started = true
        session.loadUri(url)
    }

    fun onBackPressed(): Boolean {
        return if (_binding != null && canGoBack) {
            sessionHolder.session.goBack()
            true
        } else {
            false
        }
    }

    override fun onDestroyView() {
        val session = sessionHolder.session
        session.navigationDelegate = null
        session.contentDelegate = null
        session.progressDelegate = null
        session.permissionDelegate = null
        session.promptDelegate = null
        pendingFileResult?.complete(pendingFilePrompt?.dismiss())
        pendingFilePrompt = null
        pendingFileResult = null
        pendingAndroidPermissionCallback?.reject()
        pendingAndroidPermissionCallback = null

        // Frikoppla vyn; sessionen lever kvar i ViewModel och stängs i onCleared()
        binding.geckoView.releaseSession()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "GeckoView"
    }
}