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
    val selections: Map<FontRole, FontSelection> = emptyMap(),
    val schemaSelections: Map<String, Map<FontRole, FontSelection>> = emptyMap()
) {
    /** Ordered choices; an unavailable schema font falls back to the ordinary role. */
    fun fontLayers(role: FontRole, schemaId: String?): List<FontSelection> = buildList {
        if (role in FontConfigCodec.SCHEMA_ROLES) schemaSelections[schemaId]?.get(role)?.let(::add)
        selections[role]?.let(::add)
        if (role != FontRole.Global) selections[FontRole.Global]?.let(::add)
    }

    /** Font sizes never inherit the global size. Schema and font inheritance are independent. */
    fun roleSize(role: FontRole, schemaId: String?): Float? =
        (if (role in FontConfigCodec.SCHEMA_ROLES) schemaSelections[schemaId]?.get(role)?.sizeSp else null)
            ?: selections[role]?.sizeSp

    fun withoutFont(id: String): FontConfig = copy(
        fonts = fonts.filterNot { it.id == id },
        selections = selections.mapValues { (_, value) ->
            value.copy(fontIds = value.fontIds.filterNot { it == id })
        },
        schemaSelections = schemaSelections.mapValues { (_, roles) ->
            roles.mapValues { (_, value) -> value.copy(fontIds = value.fontIds.filterNot { it == id }) }
                .filterValues { it != FontSelection() }
        }.filterValues { it.isNotEmpty() }
    )
}

/** No file paths are accepted in the settings format. IDs name managed files only. */
internal object FontConfigCodec {
    const val MAX_FONTS = 256
    const val MAX_FALLBACKS = 8
    const val MAX_CONFIG_BYTES = 256 * 1024
    const val MAX_SCHEMAS = 64
    const val MAX_SCHEMA_ID_LENGTH = 128
    val SCHEMA_ROLES = setOf(FontRole.Candidate, FontRole.Popup, FontRole.Preedit)
    private val idPattern = Regex("[0-9a-f]{32}")
    private val schemaIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")

    fun isValidId(id: String) = idPattern.matches(id)

    fun isValidSchemaId(id: String) = schemaIdPattern.matches(id)

    fun isValidSize(size: Float) = size.isFinite() && size in 8f..72f

    fun displayName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.trim().take(128).ifEmpty { "Imported font" }

    fun decode(raw: String): FontConfig = runCatching {
        if (raw.length > MAX_CONFIG_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) return FontConfig()
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
        fun selections(value: JsonObject?, roles: Iterable<FontRole>): Map<FontRole, FontSelection> = roles.mapNotNull { role ->
            val item = value?.get(role.name) as? JsonObject ?: return@mapNotNull null
            val ids = (item["fontIds"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.takeIf(knownIds::contains)
            }.distinct().take(MAX_FALLBACKS)
            val size = (item["sizeSp"] as? JsonPrimitive)?.floatOrNull?.takeIf(::isValidSize)
            role to FontSelection(ids, size)
        }.toMap()
        val selections = selections(root["selections"] as? JsonObject, FontRole.entries)
        val schemas = (root["schemaSelections"] as? JsonObject).orEmpty().asSequence()
            .filter { (id, _) -> isValidSchemaId(id) }
            .mapNotNull { (id, value) ->
                val roles = selections(value as? JsonObject, SCHEMA_ROLES).filterValues { it != FontSelection() }
                if (roles.isEmpty()) null else id to roles
            }.take(MAX_SCHEMAS).toMap()
        FontConfig(fonts, selections, schemas)
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
        // Keep the original version so earlier builds retain their font library
        // and ordinary choices; unknown fields are ignored on downgrade.
        put("schemaSelections", buildJsonObject {
            config.schemaSelections.forEach { (schemaId, selections) ->
                put(schemaId, buildJsonObject {
                    selections.forEach { (role, selection) ->
                        put(role.name, buildJsonObject {
                            put("fontIds", JsonArray(selection.fontIds.map(::JsonPrimitive)))
                            selection.sizeSp?.let { put("sizeSp", JsonPrimitive(it)) }
                        })
                    }
                })
            }
        })
    }.toString()
}
