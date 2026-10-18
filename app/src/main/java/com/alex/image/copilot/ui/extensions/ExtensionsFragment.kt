package com.alex.image.copilot.ui.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.alex.image.copilot.CopilotApplication
import com.alex.image.copilot.R
import com.alex.image.copilot.databinding.FragmentExtensionsBinding
import com.alex.image.copilot.ui.home.GeckoSessionViewModel
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
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
        if (!sessionHolder.started) {
            sessionHolder.started = true
            session.loadUri(AMO_URL)
        }

        binding.fabManage.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(ExtensionsDialogFragment.TAG) == null) {
                ExtensionsDialogFragment().show(childFragmentManager, ExtensionsDialogFragment.TAG)
            }
        }
    }

    private var pendingDialog: AlertDialog? = null

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            confirm(
                extension, permissions + origins,
                onAllow = { result.complete(WebExtension.PermissionPromptResponse(true, false)) },
                onDeny = { result.complete(WebExtension.PermissionPromptResponse(false, false)) }
            )
            return result
        }

        override fun onUpdatePrompt(
            currentlyInstalled: WebExtension,
            updatedExtension: WebExtension,
            newPermissions: Array<String>,
            newOrigins: Array<String>
        ): GeckoResult<AllowOrDeny> = allowOrDeny(updatedExtension, newPermissions + newOrigins)

        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>
        ): GeckoResult<AllowOrDeny> = allowOrDeny(extension, permissions + origins)
    }

    private fun allowOrDeny(extension: WebExtension, perms: Array<String>): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        confirm(
            extension, perms,
            onAllow = { result.complete(AllowOrDeny.ALLOW) },
            onDeny = { result.complete(AllowOrDeny.DENY) }
        )
        return result
    }

    private fun confirm(
        extension: WebExtension,
        perms: Array<String>,
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
        binding.geckoView.releaseSession()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val AMO_URL = "https://addons.mozilla.org/en-US/firefox/"
    }
}
