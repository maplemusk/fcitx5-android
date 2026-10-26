/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.font

import android.content.ContentResolver
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Local, application-private fonts, imported through document reads without new manifest permissions. */
object FontManager {
    fun interface OnChangeListener {
        fun onFontsChanged()
    }

    private val lock = Any()
    private var config: FontConfig? = null
    private val typefaces = mutableMapOf<List<String>, Typeface?>()
    private val changes = AtomicLong()
    private val listeners = CopyOnWriteArraySet<OnChangeListener>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val revision: Long get() = changes.get()

    private data class ViewDefaults(
        var typeface: Typeface?,
        var sizePx: Float,
        var appliedTypeface: Typeface? = typeface,
        var appliedSizePx: Float = sizePx
    )

    // Values do not hold their TextView keys alive.
    private val viewDefaults = WeakHashMap<TextView, ViewDefaults>()

    private fun unlocked(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
        (appContext.getSystemService(Context.USER_SERVICE) as? UserManager)?.isUserUnlocked == true

    private fun requireUnlocked() = check(unlocked()) { "Unlock the device before managing fonts" }

    private fun directory(): File = File(appContext.filesDir, "fonts").also {
        check(it.isDirectory || it.mkdirs()) { "Cannot create font directory" }
    }

    private fun settingsFile() = AtomicFile(File(directory(), "selections.json"))

    private fun loadLocked(): FontConfig {
        config?.let { return it }
        val loaded = runCatching {
            val file = settingsFile()
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@runCatching FontConfig()
            file.openRead().use { input ->
                val buffer = ByteArray(FontConfigCodec.MAX_CONFIG_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                }
                if (count > FontConfigCodec.MAX_CONFIG_BYTES) FontConfig()
                else FontConfigCodec.decode(String(buffer, 0, count, Charsets.UTF_8))
            }
        }.getOrDefault(FontConfig())
        return loaded.also { config = it }
    }

    private fun saveLocked(updated: FontConfig) {
        val file = settingsFile()
        val output = file.startWrite()
        try {
            output.write(FontConfigCodec.encode(updated).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        config = updated
        typefaces.clear()
        changes.incrementAndGet()
    }

    private fun notifyChanged() {
        val notify = Runnable { listeners.forEach { listener -> runCatching { listener.onFontsChanged() } } }
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else mainHandler.post(notify)
    }

    fun addOnChangeListener(listener: OnChangeListener) {
        listeners.add(listener)
    }

    fun removeOnChangeListener(listener: OnChangeListener) {
        listeners.remove(listener)
    }

    fun getSelection(role: FontRole): FontSelection {
        if (!unlocked()) return FontSelection()
        return synchronized(lock) {
            val selection = loadLocked().selections[role] ?: FontSelection()
            selection.copy(fontIds = selection.fontIds.toList())
        }
    }

    fun setSelection(role: FontRole, selection: FontSelection) {
        requireUnlocked()
        require(selection.fontIds.size <= FontConfigCodec.MAX_FALLBACKS) { "Select at most eight fallback fonts" }
        require(selection.sizeSp == null || FontConfigCodec.isValidSize(selection.sizeSp)) { "Font size must be between 8 and 72 sp" }
        val normalized = selection.copy(fontIds = selection.fontIds.distinct().toList())
        val changed = synchronized(lock) {
            val current = loadLocked()
            val known = current.fonts.mapTo(hashSetOf()) { it.id }
            require(normalized.fontIds.all { it in known && FontFileStore.managedFile(directory(), it).isFile }) {
                "Selected font is unavailable"
            }
            if ((current.selections[role] ?: FontSelection()) == normalized) return@synchronized false
            val selections = current.selections.toMutableMap()
            if (normalized == FontSelection()) selections.remove(role) else selections[role] = normalized
            saveLocked(current.copy(selections = selections))
            true
        }
        if (changed) notifyChanged()
    }

    fun listFonts(): List<ImportedFont> {
        if (!unlocked()) return emptyList()
        return synchronized(lock) {
            loadLocked().fonts.filter { font ->
                runCatching { FontFileStore.managedFile(directory(), font.id).isFile }.getOrDefault(false)
            }.sortedBy { it.name.lowercase() }
        }
    }

    /** Blocking: callers should use Dispatchers.IO. The URI is read once, never retained. */
    fun importFont(uri: Uri): ImportedFont {
        requireUnlocked()
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a font with the document picker" }
        val resolver = appContext.contentResolver
        val originalName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: throw IllegalArgumentException("Cannot read the font's file name")
        val name = FontConfigCodec.displayName(originalName)
        require(name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc", "otc")) {
            "Choose a .ttf, .otf, .ttc or .otc font"
        }
        val root = directory()
        val temp = resolver.openInputStream(uri)?.use { FontFileStore.copyImport(it, root) }
            ?: throw IllegalArgumentException("Cannot read the selected font")
        try {
            val validated = loadTypeface(temp) ?: throw IllegalArgumentException("Android cannot load this font")
            val imported = synchronized(lock) {
                requireUnlocked()
                val current = loadLocked()
                require(current.fonts.size < FontConfigCodec.MAX_FONTS) { "Remove an imported font before adding more" }
                var id: String
                var destination: File
                do {
                    id = UUID.randomUUID().toString().replace("-", "")
                    destination = FontFileStore.managedFile(root, id)
                } while (destination.exists())
                check(temp.renameTo(destination)) { "Cannot publish imported font" }
                val font = ImportedFont(id, name)
                try {
                    saveLocked(current.copy(fonts = current.fonts + font))
                } catch (error: Throwable) {
                    destination.delete()
                    throw error
                }
                typefaces[listOf(id)] = validated
                font
            }
            notifyChanged()
            return imported
        } finally {
            temp.delete()
        }
    }

    fun deleteFont(id: String) {
        requireUnlocked()
        require(FontConfigCodec.isValidId(id)) { "Invalid font ID" }
        val changed = synchronized(lock) {
            val current = loadLocked()
            if (current.fonts.none { it.id == id }) return@synchronized false
            val updated = current.copy(
                fonts = current.fonts.filterNot { it.id == id },
                selections = current.selections.mapValues { (_, value) ->
                    value.copy(fontIds = value.fontIds.filterNot { it == id })
                }
            )
            saveLocked(updated)
            // If cleanup fails, the orphan remains private and is no longer used.
            runCatching { FontFileStore.managedFile(directory(), id).delete() }
            true
        }
        if (changed) notifyChanged()
    }

    /** Restore system choices and sizes; imported files stay available. */
    fun reset() {
        requireUnlocked()
        val changed = synchronized(lock) {
            val current = loadLocked()
            if (current.selections.isEmpty()) return@synchronized false
            saveLocked(current.copy(selections = emptyMap()))
            true
        }
        if (changed) notifyChanged()
    }

    fun resolveTypeface(role: FontRole, default: Typeface?): Typeface? {
        if (!unlocked()) return default
        return synchronized(lock) {
            val current = loadLocked()
            val roleTypeface = resolveChainLocked(current.selections[role]?.fontIds.orEmpty())
            val globalTypeface = if (roleTypeface == null && role != FontRole.Global)
                resolveChainLocked(current.selections[FontRole.Global]?.fontIds.orEmpty()) else null
            val custom = roleTypeface ?: globalTypeface ?: return@synchronized default
            Typeface.create(custom, default?.style ?: Typeface.NORMAL)
        }
    }

    private fun resolveChainLocked(ids: List<String>): Typeface? {
        if (ids.isEmpty()) return null
        typefaces[ids]?.let { return it }
        if (typefaces.containsKey(ids)) return null
        val files = ids.mapNotNull { id ->
            runCatching {
                FontFileStore.managedFile(directory(), id).takeIf { it.isFile }?.also {
                    // Backups can restore files without going through the document importer.
                    FontFileStore.validateSfnt(it)
                }
            }.getOrNull()
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29.build(files)
        } else {
            files.firstNotNullOfOrNull { loadTypeface(it) }
        }
        typefaces[ids.toList()] = resolved
        return resolved
    }

    private fun loadTypeface(file: File): Typeface? = runCatching {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Api29.build(listOf(file))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> Typeface.Builder(file).build()
            else -> Typeface.createFromFile(file)
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun build(files: List<File>): Typeface? {
            val families = files.mapNotNull { file ->
                runCatching {
                    android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(file).build()).build()
                }.getOrNull()
            }
            if (families.isEmpty()) return null
            return runCatching {
                Typeface.CustomFallbackBuilder(families.first()).apply {
                    families.drop(1).forEach { addCustomFallback(it) }
                }.build()
            }.getOrNull()
        }
    }

    /** Call after the view's ordinary defaults are set. Clearing a selection restores those defaults. */
    fun applyTo(view: TextView, role: FontRole) {
        val baseline = viewDefaults.getOrPut(view) { ViewDefaults(view.typeface, view.textSize) }
        if (view.typeface != baseline.appliedTypeface) baseline.typeface = view.typeface
        if (abs(view.textSize - baseline.appliedSizePx) > 0.01f) baseline.sizePx = view.textSize
        val typeface = resolveTypeface(role, baseline.typeface)
        if (view.typeface != typeface) view.typeface = typeface
        // A global font choice is inherited; its size never overrides a role's own default.
        val size = getSelection(role).sizeSp
        val sizePx = if (size != null) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, size, view.resources.displayMetrics
        ) else baseline.sizePx
        if (abs(view.textSize - sizePx) > 0.01f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        baseline.appliedTypeface = view.typeface
        baseline.appliedSizePx = view.textSize
    }
}
