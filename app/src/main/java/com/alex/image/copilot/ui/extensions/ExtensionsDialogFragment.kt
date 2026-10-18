package com.alex.image.copilot.ui.extensions

import android.app.Dialog
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.image.copilot.CopilotApplication
import com.alex.image.copilot.R
import com.alex.image.copilot.databinding.DialogExtensionsBinding
import com.alex.image.copilot.databinding.ItemExtensionBinding
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** Dialog som listar installerade tillägg och låter användaren ta bort/aktivera/inaktivera dem. */
class ExtensionsDialogFragment : DialogFragment() {

    private var _binding: DialogExtensionsBinding? = null
    private val binding get() = _binding!!

    private val adapter = ExtensionsAdapter()

    private val controller: WebExtensionController
        get() = (requireActivity().application as CopilotApplication)
            .geckoRuntime.webExtensionController

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogExtensionsBinding.inflate(LayoutInflater.from(requireContext()))
        binding.extensionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.extensionsList.adapter = adapter
        refresh()
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.extensions_installed_title)
            .setView(binding.root)
            .setPositiveButton(R.string.extensions_close, null)
            .create()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refresh() {
        controller.list().accept({ list ->
            if (_binding == null) return@accept
            val items = (list ?: emptyList()).filter { !it.isBuiltIn }
            adapter.submit(items)
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }, {
            toast(R.string.extensions_load_failed)
        })
    }

    private fun toast(resId: Int) {
        context?.let { Toast.makeText(it, resId, Toast.LENGTH_LONG).show() }
    }

    private fun confirmRemove(extension: WebExtension) {
        val name = extension.metaData.name ?: getString(R.string.extensions_unnamed)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.extensions_remove_title)
            .setMessage(getString(R.string.extensions_remove_message, name))
            .setPositiveButton(R.string.extensions_remove) { _, _ ->
                controller.uninstall(extension).accept({
                    context?.let {
                        Toast.makeText(it, getString(R.string.extensions_removed_toast, name), Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                }, {
                    toast(R.string.extensions_uninstall_failed)
                    refresh()
                })
            }
            .setNegativeButton(R.string.extensions_cancel, null)
            .show()
    }

    private fun setEnabled(extension: WebExtension, enabled: Boolean) {
        val result = if (enabled) {
            controller.enable(extension, WebExtensionController.EnableSource.USER)
        } else {
            controller.disable(extension, WebExtensionController.EnableSource.USER)
        }
        result.accept({ refresh() }, {
            toast(R.string.extensions_toggle_failed)
            refresh()
        })
    }

    private inner class ExtensionsAdapter : RecyclerView.Adapter<ExtensionsAdapter.Holder>() {
        private var items: List<WebExtension> = emptyList()

        fun submit(newItems: List<WebExtension>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemExtensionBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemExtensionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ext = items[position]
            val b = holder.b
            val meta = ext.metaData
            b.extName.text = meta.name ?: getString(R.string.extensions_unnamed)
            b.extVersion.text = getString(R.string.extensions_version, meta.version)

            b.extSwitch.setOnCheckedChangeListener(null)
            b.extSwitch.isChecked = meta.enabled
            b.extSwitch.setText(if (meta.enabled) R.string.extensions_enabled else R.string.extensions_disabled)
            b.extSwitch.setOnCheckedChangeListener { _, checked -> setEnabled(ext, checked) }

            b.extRemove.setOnClickListener { confirmRemove(ext) }

            b.extIcon.setImageDrawable(null)
            b.extIcon.tag = ext.id
            meta.icon.getBitmap(ICON_SIZE_PX).accept({ bitmap ->
                if (bitmap != null && b.extIcon.tag == ext.id) {
                    b.extIcon.setImageDrawable(BitmapDrawable(b.root.resources, bitmap))
                }
            }, { })
        }
    }

    companion object {
        const val TAG = "ExtensionsDialog"
        private const val ICON_SIZE_PX = 96
    }
}
