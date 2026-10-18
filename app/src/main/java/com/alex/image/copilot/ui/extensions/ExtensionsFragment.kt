package com.alex.image.copilot.ui.extensions

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.alex.image.copilot.CopilotApplication
import com.alex.image.copilot.R
import com.alex.image.copilot.databinding.FragmentExtensionsBinding
import com.alex.image.copilot.ui.home.GeckoSessionViewModel
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** Visar addons.mozilla.org och låter användaren installera och hantera tillägg. */
class ExtensionsFragment : Fragment() {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val sessionHolder: GeckoSessionViewModel by viewModels()

    private val controller: WebExtensionController
        get() = (requireActivity().application as CopilotApplication)
            .geckoRuntime.webExtensionController

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        controller.promptDelegate = promptDelegate

        val session = sessionHolder.ensureOpen()
        binding.geckoView.setSession(session)

        session.navigationDelegate = navigationDelegate

        if (!sessionHolder.started) {
            sessionHolder.started = true
            session.loadUri(AMO_URL)
        }

        binding.fabManage.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(ExtensionsDialogFragment.TAG) == null) {
                ExtensionsDialogFragment().show(childFragmentManager, ExtensionsDialogFragment.TAG)
            }
        }

        binding.fabManage.setOnLongClickListener {
            installGhosteryDirectly()
            true
        }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val url = request.uri
            if (url.contains("addon/ghostery")) {
                activity?.runOnUiThread {
                    installGhosteryDirectly()
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }
    }

    private fun installGhosteryDirectly() {
        Toast.makeText(context, "Hämtar och installerar Ghostery...", Toast.LENGTH_SHORT).show()

        // HÄR SKA DEN IN (Rad 73) - Ändrad från "https://mozilla.org" till direktlänken
        val ghosteryXpiUrl = "https://mozilla.org"

        controller.install(ghosteryXpiUrl).then(
            { extension ->
                activity?.runOnUiThread {
                    Toast.makeText(context, "Ghostery installerades framgångsrikt!", Toast.LENGTH_LONG).show()
                }
                GeckoResult.fromValue(extension)
            },
            { throwable ->
                activity?.runOnUiThread {
                    Toast.makeText(context, "Installationsfel: " + throwable.message, Toast.LENGTH_LONG).show()
                }
                GeckoResult.fromValue(null)
            }
        )
    }

    private var pendingDialog: AlertDialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            p0: WebExtension,
            p1: Array<out String>,
            p2: Array<out String>,
            p3: Array<out String>
        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()

            val permissionsList = p1.map { it }
            val originsList = p2.map { it }
            val dataCollectionList = p3.map { it }
            val allPerms = permissionsList + originsList + dataCollectionList

            confirm(
                p0,
                allPerms,
                onAllow = {
                    mainHandler.post {
                        result.complete(WebExtension.PermissionPromptResponse(true, false, false))
                    }
                },
                onDeny = {
                    mainHandler.post {
                        result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                    }
                }
            )
            return result
        }

        override fun onUpdatePrompt(
            p0: WebExtension,
            p1: Array<out String>,
            p2: Array<out String>,
            p3: Array<out String>
        ): GeckoResult<AllowOrDeny>? {
            val permissionsList = p1.map { it }
            val originsList = p2.map { it }
            val dataCollectionList = p3.map { it }
            val allPerms = permissionsList + originsList + dataCollectionList

            val result = GeckoResult<AllowOrDeny>()
            confirm(
                p0, allPerms,
                onAllow = { mainHandler.post { result.complete(AllowOrDeny.ALLOW) } },
                onDeny = { mainHandler.post { result.complete(AllowOrDeny.DENY) } }
            )
            return result
        }

        override fun onOptionalPrompt(
            p0: WebExtension,
            p1: Array<out String>,
            p2: Array<out String>,
            p3: Array<out String>
        ): GeckoResult<AllowOrDeny>? {
            val permissionsList = p1.map { it }
            val originsList = p2.map { it }
            val dataCollectionList = p3.map { it }
            val allPerms = permissionsList + originsList + dataCollectionList

            val result = GeckoResult<AllowOrDeny>()
            confirm(
                p0, allPerms,
                onAllow = { mainHandler.post { result.complete(AllowOrDeny.ALLOW) } },
                onDeny = { mainHandler.post { result.complete(AllowOrDeny.DENY) } }
            )
            return result
        }
    }

    private fun confirm(
        extension: WebExtension,
        perms: List<String>,
        onAllow: () -> Unit,
        onDeny: () -> Unit
    ) {
        val ctx = context
        if (ctx == null || !isAdded) {
            onDeny()
            return
        }
        val name = extension.metaData.name ?: getString(R.string.extensions_unnamed)
        val message = if (perms.isEmpty()) {
            getString(R.string.extensions_install_no_permissions)
        } else {
            getString(R.string.extensions_install_message) + "\n\n" +
                    perms.joinToString("\n") { "• $it" }
        }
        pendingDialog?.dismiss()
        pendingDialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.extensions_install_title, name))
            .setMessage(message)
            .setPositiveButton(R.string.extensions_install_add) { _, _ ->
                onAllow()
            }
            .setNegativeButton(R.string.extensions_cancel) { _, _ -> onDeny() }
            .setOnCancelListener { onDeny() }
            .create()
            .also { it.show() }
    }

    override fun onDestroyView() {
        controller.promptDelegate = null
        pendingDialog?.dismiss()
        pendingDialog = null
        mainHandler.removeCallbacksAndMessages(null)
        binding.geckoView.releaseSession()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val AMO_URL = "https://addons.mozilla.org/en-US/firefox/addon"
    }
}
