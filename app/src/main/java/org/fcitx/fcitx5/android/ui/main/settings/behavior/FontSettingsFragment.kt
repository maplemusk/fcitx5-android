/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.font.FontManager
import org.fcitx.fcitx5.android.data.font.FontRole
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast
import java.text.NumberFormat

/** Local font selection. Importing never grants persistent access to the source document. */
class FontSettingsFragment : PaddingPreferenceFragment() {
    private var importing = false

    private val importFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            importing = true
            rebuildPreferences()
            try {
                val font = withContext(Dispatchers.IO) { FontManager.importFont(uri) }
                context?.toast(getString(R.string.font_imported, font.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.toast(getString(R.string.font_import_failed, e.localizedMessage ?: ""))
            } finally {
                importing = false
                if (isAdded) rebuildPreferences()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        rebuildPreferences()
    }

    override fun onResume() {
        super.onResume()
        rebuildPreferences()
    }

    private fun rebuildPreferences() {
        val ctx = context ?: return
        val fonts = FontManager.listFonts().associateBy { it.id }
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addPreference(Preference(ctx).apply {
                setTitle(R.string.font_local_only)
                setSummary(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    R.string.font_fallback_hint
                } else R.string.font_fallback_legacy_hint)
                isSelectable = false
                isIconSpaceReserved = false
            })
            addCategory(R.string.font_library) {
                addPreference(R.string.font_import, R.string.font_import_hint) {
                    importFont.launch(arrayOf("*/*"))
                }
                getPreference(0).isEnabled = !importing
                addPreference(R.string.font_remove) { showRemoveFont() }
                getPreference(1).isEnabled = fonts.isNotEmpty() && !importing
            }
            FontRole.entries.forEach { role ->
                val selection = FontManager.getSelection(role)
                addCategory(titleFor(role)) {
                    addPreference(
                        getString(R.string.font_choose),
                        selection.fontIds.mapNotNull { fonts[it]?.name }.joinToString(" → ")
                            .ifEmpty { getString(defaultLabelFor(role)) }
                    ) { showFontPicker(role) }
                    if (selection.fontIds.size > 1) {
                        addPreference(R.string.font_order, R.string.font_order_hint) {
                            showFontOrder(role)
                        }
                    }
                    if (role != FontRole.Global) {
                        addPreference(
                            getString(R.string.font_size),
                            selection.sizeSp?.let {
                                getString(R.string.font_size_value, NumberFormat.getNumberInstance().format(it))
                            } ?: getString(R.string.font_original_size)
                        ) { showFontSize(role) }
                    }
                    addPreference(FontPreviewPreference(ctx, role))
                }
            }
            addPreference(R.string.font_reset, R.string.font_reset_hint) {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.font_reset)
                    .setMessage(R.string.font_reset_confirm)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        update { FontManager.reset() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun titleFor(role: FontRole): Int = when (role) {
        FontRole.Global -> R.string.font_global
        FontRole.KeyMain -> R.string.font_key_main
        FontRole.KeyAlt -> R.string.font_key_alt
        FontRole.Candidate -> R.string.font_candidate
        FontRole.Popup -> R.string.font_popup
        FontRole.Preedit -> R.string.font_preedit
    }

    private fun defaultLabelFor(role: FontRole) = if (role == FontRole.Global) {
        R.string.font_system_default
    } else R.string.font_inherit_global

    private fun showFontPicker(role: FontRole) {
        val ctx = requireContext()
        val fonts = FontManager.listFonts()
        val selected = FontManager.getSelection(role).fontIds.toMutableList()
        val builder = AlertDialog.Builder(ctx).setTitle(titleFor(role))
        if (fonts.isEmpty()) {
            builder.setMessage(R.string.font_no_imports)
                .setPositiveButton(R.string.font_import) { _, _ -> importFont.launch(arrayOf("*/*")) }
        } else {
            builder.setMultiChoiceItems(
                fonts.map { it.name }.toTypedArray(),
                fonts.map { it.id in selected }.toBooleanArray()
            ) { dialog, index, checked ->
                if (checked && selected.size >= 8) {
                    (dialog as AlertDialog).listView.setItemChecked(index, false)
                    ctx.toast(R.string.font_fallback_limit)
                } else if (checked) selected.add(fonts[index].id) else selected.remove(fonts[index].id)
            }.setPositiveButton(android.R.string.ok) { _, _ ->
                update {
                    FontManager.setSelection(role, FontManager.getSelection(role).copy(fontIds = selected))
                }
            }
        }
        builder.setNeutralButton(defaultLabelFor(role)) { _, _ ->
            update {
                FontManager.setSelection(role, FontManager.getSelection(role).copy(fontIds = emptyList()))
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showFontOrder(role: FontRole) {
        val ids = FontManager.getSelection(role).fontIds
        val names = FontManager.listFonts().associate { it.id to it.name }
        if (ids.size < 2) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.font_order)
            .setItems(ids.mapIndexed { i, id -> "${i + 1}. ${names[id].orEmpty()}" }.toTypedArray()) { _, index ->
                if (index == 0) {
                    showFontOrder(role)
                } else update {
                    val reordered = ids.toMutableList().apply { add(0, removeAt(index)) }
                    FontManager.setSelection(role, FontManager.getSelection(role).copy(fontIds = reordered))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFontSize(role: FontRole) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(8))
            hint = getString(R.string.font_size_hint)
            setText(FontManager.getSelection(role).sizeSp?.toString().orEmpty())
            selectAll()
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.font_size_for, getString(titleFor(role))))
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.font_original_size) { _, _ ->
                update {
                    FontManager.setSelection(role, FontManager.getSelection(role).copy(sizeSp = null))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val size = input.text.toString().trim().replace(',', '.').toFloatOrNull()
                if (size == null || !size.isFinite() || size !in 8f..72f) {
                    input.error = getString(R.string.font_size_hint)
                } else {
                    update {
                        FontManager.setSelection(role, FontManager.getSelection(role).copy(sizeSp = size))
                    }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showRemoveFont() {
        val fonts = FontManager.listFonts()
        if (fonts.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.font_remove)
            .setItems(fonts.map { it.name }.toTypedArray()) { _, index ->
                val font = fonts[index]
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.font_remove)
                    .setMessage(getString(R.string.font_remove_confirm, font.name))
                    .setPositiveButton(R.string.delete) { _, _ -> update { FontManager.deleteFont(font.id) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun update(action: () -> Unit) {
        try {
            action()
            rebuildPreferences()
        } catch (e: Exception) {
            context?.toast(e)
        }
    }

    private class FontPreviewPreference(ctx: Context, private val role: FontRole) : Preference(ctx) {
        init {
            setTitle(R.string.font_preview_text)
            isSelectable = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(android.R.id.title) as? TextView)?.let { view ->
                // Reset recycled holders before applying this role's optional overrides.
                view.typeface = android.graphics.Typeface.DEFAULT
                view.textSize = 20f
                FontManager.applyTo(view, role)
            }
        }
    }
}
