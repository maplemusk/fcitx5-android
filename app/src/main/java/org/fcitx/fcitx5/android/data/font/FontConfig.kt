/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.font

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

enum class FontRole { Global, KeyMain, KeyAlt, Candidate, Popup, Preedit }

data class FontSelection(val fontIds: List<String> = emptyList(), val sizeSp: Float? = null)

data class ImportedFont(val id: String, val name: String)

internal data class FontConfig(
    val fonts: List<ImportedFont> = emptyList(),
    val selections: Map<FontRole, FontSelection> = emptyMap()
)

/** No file paths are accepted in the settings format. IDs name managed files only. */
internal object FontConfigCodec {
    const val MAX_FONTS = 256
    const val MAX_FALLBACKS = 8
    const val MAX_CONFIG_BYTES = 256 * 1024
    private val idPattern = Regex("[0-9a-f]{32}")

    fun isValidId(id: String) = idPattern.matches(id)

    fun isValidSize(size: Float) = size.isFinite() && size in 8f..72f

    fun displayName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.trim().take(128).ifEmpty { "Imported font" }

    fun decode(raw: String): FontConfig = runCatching {
        if (raw.length > MAX_CONFIG_BYTES) return FontConfig()
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return FontConfig()
        if ((root["version"] as? JsonPrimitive)?.intOrNull != 1) return FontConfig()
        val fonts = (root["fonts"] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item ->
                val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (!isValidId(id)) return@mapNotNull null
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                ImportedFont(id, displayName(name))
            }.distinctBy { it.id }.take(MAX_FONTS).toList()
        val knownIds = fonts.mapTo(hashSetOf()) { it.id }
        val selectionsJson = root["selections"] as? JsonObject
        val selections = FontRole.entries.mapNotNull { role ->
            val item = selectionsJson?.get(role.name) as? JsonObject ?: return@mapNotNull null
            val ids = (item["fontIds"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.takeIf(knownIds::contains)
            }.distinct().take(MAX_FALLBACKS)
            val size = (item["sizeSp"] as? JsonPrimitive)?.floatOrNull?.takeIf(::isValidSize)
            role to FontSelection(ids, size)
        }.toMap()
        FontConfig(fonts, selections)
    }.getOrDefault(FontConfig())

    fun encode(config: FontConfig): String = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("fonts", JsonArray(config.fonts.map { font ->
            buildJsonObject {
                put("id", JsonPrimitive(font.id))
                put("name", JsonPrimitive(font.name))
            }
        }))
        put("selections", buildJsonObject {
            config.selections.forEach { (role, selection) ->
                put(role.name, buildJsonObject {
                    put("fontIds", JsonArray(selection.fontIds.map(::JsonPrimitive)))
                    selection.sizeSp?.let { put("sizeSp", JsonPrimitive(it)) }
                })
            }
        })
    }.toString()
}
