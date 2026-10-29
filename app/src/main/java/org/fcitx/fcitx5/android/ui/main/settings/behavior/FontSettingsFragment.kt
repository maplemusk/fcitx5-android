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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.font.FontConfigCodec
import org.fcitx.fcitx5.android.data.font.FontManager
import org.fcitx.fcitx5.android.data.font.FontRole
import org.fcitx.fcitx5.android.data.font.FontSelection
import org.fcitx.fcitx5.android.data.rime.RimeSchema
import org.fcitx.fcitx5.android.data.rime.RimeSchemaResolver
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast
import java.text.NumberFormat

/** Local font selection. Importing never grants persistent access to the source document. */
class FontSettingsFragment : PaddingPreferenceFragment() {
    private var importing = false
    private var selectedSchemaId = "wubi06"
    private var installedSchemas = emptyList<RimeSchema>()
    private var schemaLookupJob: Job? = null
    private var schemaLookupGeneration = 0

    private val importFont: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
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
        selectedSchemaId = savedInstanceState?.getString(SELECTED_SCHEMA)
            ?.takeIf(FontConfigCodec::isValidSchemaId) ?: selectedSchemaId
        rebuildPreferences()
    }

    override fun onResume() {
        super.onResume()
        rebuildPreferences()
        refreshSchemas()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SELECTED_SCHEMA, selectedSchemaId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        ++schemaLookupGeneration
        schemaLookupJob?.cancel()
        schemaLookupJob = null
        super.onDestroyView()
    }

    private fun refreshSchemas() {
        val app = context?.applicationContext ?: return
        val generation = ++schemaLookupGeneration
        schemaLookupJob?.cancel()
        schemaLookupJob = lifecycleScope.launch {
            val schemas = try {
                withContext(Dispatchers.IO) {
                    app.getExternalFilesDir(null)?.resolve("data/rime/build")
                        ?.let(::RimeSchemaResolver)?.listMenuSchemas().orEmpty()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (isAdded && generation == schemaLookupGeneration) {
                installedSchemas = schemas
                rebuildPreferences()
            }
        }
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
            val schemaLabel = schemaLabel(schemaChoices().first { it.id == selectedSchemaId })
            addCategory(R.string.font_schema_settings) {
                addPreference(Preference(ctx).apply {
                    setSummary(R.string.font_schema_hint)
                    isSelectable = false
                    isIconSpaceReserved = false
                })
                addPreference(getString(R.string.font_schema_choose), schemaLabel) { showSchemaPicker() }
                addPreference(R.string.font_schema_reset, R.string.font_schema_reset_hint) {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.font_schema_reset)
                        .setMessage(getString(R.string.font_schema_reset_confirm, schemaLabel))
                        .setPositiveButton(R.string.reset) { _, _ ->
                            update { FontManager.clearSchemaSelections(selectedSchemaId) }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            FontConfigCodec.SCHEMA_ROLES.forEach { role ->
                val schemaId = selectedSchemaId
                val selection = FontManager.getSchemaSelection(schemaId, role)
                addCategory(getString(R.string.font_schema_role, getString(titleFor(role)), schemaLabel)) {
                    addPreference(
                        getString(R.string.font_choose),
                        selection.fontIds.mapNotNull { fonts[it]?.name }.joinToString(" → ")
                            .ifEmpty { defaultLabelFor(role, schemaId) }
                    ) { showFontPicker(role, schemaId) }
                    if (selection.fontIds.size > 1) {
                        addPreference(R.string.font_order, R.string.font_order_hint) {
                            showFontOrder(role, schemaId)
                        }
                    }
                    addPreference(
                        getString(R.string.font_size),
                        selection.sizeSp?.let {
                            getString(R.string.font_size_value, NumberFormat.getNumberInstance().format(it))
                        } ?: getString(R.string.font_inherit_role, getString(titleFor(role)))
                    ) { showFontSize(role, schemaId) }
                    addPreference(FontPreviewPreference(ctx, role, schemaId))
                }
            }
            FontRole.entries.forEach { role ->
                val selection = FontManager.getSelection(role)
                addCategory(titleFor(role)) {
                    addPreference(
                        getString(R.string.font_choose),
                        selection.fontIds.mapNotNull { fonts[it]?.name }.joinToString(" → ")
                            .ifEmpty { defaultLabelFor(role) }
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
                    addPreference(FontPreviewPreference(ctx, role, null))
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

    private fun schemaChoices(): List<RimeSchema> {
        val schemas = linkedMapOf(
            "wubi06" to RimeSchema("wubi06", getString(R.string.font_schema_wubi06)),
            "wubi86" to RimeSchema("wubi86", getString(R.string.font_schema_wubi86)),
            "wubi98" to RimeSchema("wubi98", getString(R.string.font_schema_wubi98)),
            "rime_ice" to RimeSchema("rime_ice", getString(R.string.font_schema_ice))
        )
        installedSchemas.forEach { schemas[it.id] = it }
        FontManager.listConfiguredSchemas().sorted().forEach { id ->
            schemas.putIfAbsent(id, RimeSchema(id, id))
        }
        schemas.putIfAbsent(selectedSchemaId, RimeSchema(selectedSchemaId, selectedSchemaId))
        return schemas.values.toList()
    }

    private fun schemaLabel(schema: RimeSchema): String = if (schema.name == schema.id) {
        schema.id
    } else getString(R.string.font_schema_name_id, schema.name, schema.id)

    private fun showSchemaPicker() {
        val schemas = schemaChoices()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.font_schema_choose)
            .setSingleChoiceItems(
                schemas.map(::schemaLabel).toTypedArray(), schemas.indexOfFirst { it.id == selectedSchemaId }
            ) { dialog, index ->
                selectedSchemaId = schemas[index].id
                dialog.dismiss()
                rebuildPreferences()
            }
            .setNeutralButton(R.string.font_schema_other) { _, _ -> showSchemaIdInput() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSchemaIdInput() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(128))
            hint = getString(R.string.font_schema_id_hint)
            setText(selectedSchemaId)
            selectAll()
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.font_schema_other)
            .setMessage(R.string.font_schema_id_help)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().trim()
                if (!FontConfigCodec.isValidSchemaId(id)) {
                    input.error = getString(R.string.font_schema_id_invalid)
                } else {
                    selectedSchemaId = id
                    dialog.dismiss()
                    rebuildPreferences()
                }
            }
        }
        dialog.show()
    }

    private fun defaultLabelFor(role: FontRole, schemaId: String? = null): String = when {
        schemaId != null -> getString(R.string.font_inherit_role, getString(titleFor(role)))
        role == FontRole.Global -> getString(R.string.font_system_default)
        else -> getString(R.string.font_inherit_global)
    }

    private fun selectionFor(role: FontRole, schemaId: String?) = if (schemaId == null) {
        FontManager.getSelection(role)
    } else FontManager.getSchemaSelection(schemaId, role)

    private fun setSelection(role: FontRole, schemaId: String?, selection: FontSelection) {
        if (schemaId == null) FontManager.setSelection(role, selection)
        else FontManager.setSchemaSelection(schemaId, role, selection)
    }

    private fun showFontPicker(role: FontRole, schemaId: String? = null) {
        val ctx = requireContext()
        val fonts = FontManager.listFonts()
        val selected = selectionFor(role, schemaId).fontIds.toMutableList()
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
                    setSelection(role, schemaId, selectionFor(role, schemaId).copy(fontIds = selected))
                }
            }
        }
        builder.setNeutralButton(defaultLabelFor(role, schemaId)) { _, _ ->
            update {
                setSelection(role, schemaId, selectionFor(role, schemaId).copy(fontIds = emptyList()))
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showFontOrder(role: FontRole, schemaId: String? = null) {
        val ids = selectionFor(role, schemaId).fontIds
        val names = FontManager.listFonts().associate { it.id to it.name }
        if (ids.size < 2) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.font_order)
            .setItems(ids.mapIndexed { i, id -> "${i + 1}. ${names[id].orEmpty()}" }.toTypedArray()) { _, index ->
                if (index == 0) {
                    showFontOrder(role, schemaId)
                } else update {
                    val reordered = ids.toMutableList().apply { add(0, removeAt(index)) }
                    setSelection(role, schemaId, selectionFor(role, schemaId).copy(fontIds = reordered))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFontSize(role: FontRole, schemaId: String? = null) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(8))
            hint = getString(R.string.font_size_hint)
            setText(selectionFor(role, schemaId).sizeSp?.toString().orEmpty())
            selectAll()
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.font_size_for, getString(titleFor(role))))
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(if (schemaId == null) getString(R.string.font_original_size)
                else getString(R.string.font_inherit_role, getString(titleFor(role)))) { _, _ ->
                update {
                    setSelection(role, schemaId, selectionFor(role, schemaId).copy(sizeSp = null))
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
                        setSelection(role, schemaId, selectionFor(role, schemaId).copy(sizeSp = size))
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

    private class FontPreviewPreference(
        ctx: Context, private val role: FontRole, private val schemaId: String?
    ) : Preference(ctx) {
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
                FontManager.applyTo(view, role, schemaId)
            }
        }
    }

    private companion object {
        const val SELECTED_SCHEMA = "font_selected_rime_schema"
    }
}
