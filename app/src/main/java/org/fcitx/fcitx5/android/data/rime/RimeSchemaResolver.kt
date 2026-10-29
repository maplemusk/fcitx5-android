/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.rime

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A deployed schema's stable identifier and user-facing caption. */
data class RimeSchema(val id: String, val name: String)

/**
 * Resolve Fcitx's displayed Rime caption against bounded, compiled schema metadata.
 * This is a caption lookup, not a native query of the active Rime schema ID.
 * File I/O belongs on an I/O dispatcher. Invalid or ambiguous metadata fails closed.
 */
class RimeSchemaResolver(private val directory: File) {
    fun resolve(displayName: String): RimeSchema? = runCatching {
        if (displayName.isBlank()) return null
        val metadata = readSchemas() ?: return null
        val selected = metadata.entries.singleOrNull { it.value.name == displayName } ?: return null
        selected.value.takeIf { selected.key == "${it.id}.schema.yaml" }
    }.getOrNull()

    /** List canonical, unambiguous deployed schemas, including auxiliary schemas. */
    fun listSchemas(): List<RimeSchema> = runCatching {
        val metadata = readSchemas() ?: return emptyList()
        canonicalSchemas(metadata)
    }.getOrDefault(emptyList())

    /** Prefer the configured Rime menu order; fall back to deployed schemas if unavailable. */
    fun listMenuSchemas(): List<RimeSchema> = runCatching {
        val metadata = readSchemas() ?: return emptyList()
        val schemas = canonicalSchemas(metadata)
        val menu = runCatching { readMenuIds() }.getOrNull() ?: return schemas
        val byId = schemas.associateBy { it.id }
        menu.mapNotNull(byId::get)
    }.getOrDefault(emptyList())

    private fun canonicalSchemas(metadata: Map<String, RimeSchema>): List<RimeSchema> {
        val names = metadata.values.groupingBy { it.name }.eachCount()
        return metadata.entries.asSequence()
            .filter { (file, schema) -> file == "${schema.id}.schema.yaml" && names[schema.name] == 1 }
            .map { it.value }.sortedBy { it.id }.toList()
    }

    private fun readSchemas(): Map<String, RimeSchema>? {
        val root = directory.canonicalFile
        val files = root.listFiles { file -> file.name.endsWith(".schema.yaml") } ?: return null
        if (files.isEmpty() || files.size > MAX_FILES) return null
        var totalBytes = 0L
        return files.associate { file ->
            val length = file.length()
            totalBytes += length
            if (totalBytes > MAX_TOTAL_BYTES) return null
            val text = readText(root, file, length) ?: return null
            val schema = parseSchema(text) ?: return null
            if (!schemaIdPattern.matches(schema.id)) return null
            file.name to schema
        }
    }

    private fun readText(root: File, file: File, expectedLength: Long = file.length()): String? {
        // Do not follow a schema/default-shaped symlink outside the selected folder.
        if (!file.isFile || file.canonicalFile != File(root, file.name)) return null
        if (expectedLength <= 0 || expectedLength > MAX_FILE_BYTES) return null
        val bytes = file.inputStream().use { input ->
            val data = ByteArray(MAX_FILE_BYTES + 1)
            var used = 0
            while (used < data.size) {
                val count = input.read(data, used, data.size - used)
                if (count < 0) break
                if (count == 0) continue
                used += count
            }
            if (used > MAX_FILE_BYTES || used.toLong() != expectedLength) return null
            data.copyOf(used)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }

    /** Read the simple block-list format emitted by Rime's deployed yaml-cpp config. */
    private fun readMenuIds(): List<String>? {
        val root = directory.canonicalFile
        val file = File(root, "default.yaml")
        val text = readText(root, file) ?: return null
        var inList = false
        var sawList = false
        val ids = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (line.take(indent).any { it != ' ' }) return null
            val content = line.substring(indent)
            if (indent == 0) {
                inList = false
                val mapping = mappingEntry(content)
                if (mapping?.first == "schema_list") {
                    if (sawList) return null
                    sawList = true
                    val value = stripComment(mapping.second)
                    if (value == "[]") continue
                    if (value.isNotEmpty()) return null
                    inList = true
                }
                continue
            }
            if (!inList) continue
            if (!content.startsWith("- ")) return null
            val mapping = mappingEntry(content.substring(2)) ?: return null
            if (mapping.first != "schema") return null
            val id = scalar(mapping.second)?.takeIf(schemaIdPattern::matches) ?: return null
            if (id in ids || ids.size >= MAX_FILES) return null
            ids.add(id)
        }
        return ids.takeIf { sawList }
    }

    /** Read only direct children of the root schema mapping; ignore the rest of the schema. */
    private fun parseSchema(text: String): RimeSchema? {
        var inSchema = false
        var sawSchema = false
        var childIndent: Int? = null
        var lastDirectKey: String? = null
        var id: String? = null
        var name: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (line.take(indent).any { it != ' ' }) return null
            val content = line.substring(indent)
            if (indent == 0) {
                inSchema = false
                if (content.startsWith('\'') || content.startsWith('"')) return null
                val mapping = mappingEntry(content)
                if (mapping?.first == "schema") {
                    if (sawSchema || stripComment(mapping.second).isNotEmpty()) return null
                    sawSchema = true
                    inSchema = true
                }
                continue
            }
            if (!inSchema) continue
            val level = childIndent ?: indent.also { childIndent = it }
            if (indent < level) return null
            if (indent > level) {
                if (lastDirectKey == "schema_id" || lastDirectKey == "name") return null
                continue
            }
            val entry = mappingEntry(content) ?: return null
            lastDirectKey = entry.first
            when (entry.first) {
                "schema_id" -> {
                    if (id != null) return null
                    id = scalar(entry.second) ?: return null
                }
                "name" -> {
                    if (name != null) return null
                    name = scalar(entry.second) ?: return null
                }
            }
        }
        return if (sawSchema && !id.isNullOrBlank() && !name.isNullOrBlank()) RimeSchema(id, name) else null
    }

    private fun mappingEntry(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0 || (colon + 1 < line.length && !line[colon + 1].isWhitespace())) return null
        val key = line.substring(0, colon)
        if (!key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return key to line.substring(colon + 1).trimStart()
    }

    private fun stripComment(value: String): String {
        val comment = value.indices.firstOrNull { value[it] == '#' && (it == 0 || value[it - 1].isWhitespace()) }
        return (comment?.let { value.substring(0, it) } ?: value).trim()
    }

    /** A deliberately small, fail-closed subset of YAML scalar syntax emitted by yaml-cpp. */
    private fun scalar(raw: String): String? {
        val value = raw.trimStart()
        if (value.isEmpty()) return null
        if (value[0] != '\'' && value[0] != '"') {
            val plain = stripComment(value)
            if (plain.isEmpty() || plain[0] in "!&*|>{[" || plain in setOf("~", "null", "Null", "NULL") ||
                plain.indices.any { plain[it] == ':' && (it + 1 == plain.length || plain[it + 1].isWhitespace()) }
            ) return null
            return plain.takeIf(::validScalar)
        }
        val quote = value[0]
        val result = StringBuilder()
        var index = 1
        while (index < value.length) {
            val character = value[index++]
            if (character == quote) {
                if (quote == '\'' && index < value.length && value[index] == '\'') {
                    result.append('\'')
                    index++
                    continue
                }
                val rest = value.substring(index)
                if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
                if (stripComment(rest).isNotEmpty()) return null
                return result.toString().takeIf(::validScalar)
            }
            if (character != '\\' || quote == '\'') {
                result.append(character)
                continue
            }
            if (index >= value.length) return null
            when (val escape = value[index++]) {
                '\\', '"', '/', ' ' -> result.append(escape)
                '0' -> result.append('\u0000')
                'a' -> result.append('\u0007')
                'b' -> result.append('\b')
                't' -> result.append('\t')
                'n' -> result.append('\n')
                'v' -> result.append('\u000B')
                'f' -> result.append('\u000C')
                'r' -> result.append('\r')
                'e' -> result.append('\u001B')
                'N' -> result.append('\u0085')
                '_' -> result.append('\u00A0')
                'L' -> result.append('\u2028')
                'P' -> result.append('\u2029')
                'x', 'u', 'U' -> {
                    val digits = when (escape) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    if (index + digits > value.length) return null
                    val hex = value.substring(index, index + digits)
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    val code = hex.toLongOrNull(16) ?: return null
                    if (code > Character.MAX_CODE_POINT) return null
                    if (escape == 'U' && code in 0xD800L..0xDFFFL) return null
                    result.append(String(Character.toChars(code.toInt())))
                    index += digits
                }
                else -> return null
            }
        }
        return null // Multiline quoted values are outside this metadata reader's subset.
    }

    private fun validScalar(value: String): Boolean {
        if (value.isBlank() || value.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return false
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (Character.isHighSurrogate(character)) {
                if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(character)) return false
        }
        return true
    }

    private companion object {
        val schemaIdPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        const val MAX_FILES = 64
        const val MAX_FILE_BYTES = 128 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024
    }
}
