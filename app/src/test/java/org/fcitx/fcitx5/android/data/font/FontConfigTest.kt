/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontConfigTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun `persisted paths and dangling font references are discarded`() {
        val decoded = FontConfigCodec.decode("""{
            "version":1,
            "fonts":[{"id":"../private","name":"evil.ttf"},{"id":"$id","name":"/storage/fonts/normal.ttf"}],
            "selections":{"Candidate":{"fontIds":["../private","missing","$id","$id"],"sizeSp":19}}
        }""")
        assertEquals(listOf(ImportedFont(id, "normal.ttf")), decoded.fonts)
        assertEquals(FontSelection(listOf(id), 19f), decoded.selections[FontRole.Candidate])
        assertFalse(FontConfigCodec.isValidId("../$id"))
        assertFalse(FontConfigCodec.isValidId("/$id"))
    }

    @Test
    fun `fallback order and independent role sizes survive persistence`() {
        val other = "abcdef0123456789abcdef0123456789"
        val expected = FontConfig(
            listOf(ImportedFont(id, "CJK.ttc"), ImportedFont(other, "Latin.otf")),
            mapOf(
                FontRole.Global to FontSelection(listOf(other), 40f),
                FontRole.Candidate to FontSelection(listOf(id, other), 18.5f),
                FontRole.KeyAlt to FontSelection(sizeSp = 12f)
            )
        )
        assertEquals(expected, FontConfigCodec.decode(FontConfigCodec.encode(expected)))
        assertEquals(null, FontConfigCodec.decode(FontConfigCodec.encode(expected)).selections[FontRole.Preedit])
    }

    @Test
    fun `corrupt or unsupported settings safely use defaults`() {
        listOf("", "{", "[]", "null", "{\"version\":2}", "x".repeat(FontConfigCodec.MAX_CONFIG_BYTES + 1)).forEach {
            assertEquals(FontConfig(), FontConfigCodec.decode(it))
        }
    }

    @Test
    fun `nonfinite and extreme sizes are ignored`() {
        for (size in listOf("7", "73", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"")) {
            val decoded = FontConfigCodec.decode("""{"version":1,"selections":{"Popup":{"sizeSp":$size}}}""")
            assertEquals(null, decoded.selections[FontRole.Popup]?.sizeSp)
        }
        assertTrue(FontConfigCodec.isValidSize(8f))
        assertTrue(FontConfigCodec.isValidSize(72f))
        assertFalse(FontConfigCodec.isValidSize(Float.NaN))
    }

    @Test
    fun `settings cap ordered fallback chains`() {
        val fonts = (0 until 10).map { ImportedFont(it.toString(16).padStart(32, '0'), "font$it.ttf") }
        val decoded = FontConfigCodec.decode(FontConfigCodec.encode(FontConfig(
            fonts, mapOf(FontRole.Candidate to FontSelection(fonts.map { it.id }))
        )))
        assertEquals(fonts.take(8).map { it.id }, decoded.selections[FontRole.Candidate]?.fontIds)
    }

    @Test
    fun `schema choices preserve independent roles and old version one choices`() {
        val other = "abcdef0123456789abcdef0123456789"
        val expected = FontConfig(
            fonts = listOf(ImportedFont(id, "HeiTi.ttf"), ImportedFont(other, "NVSans.ttf")),
            selections = mapOf(FontRole.Global to FontSelection(listOf(other))),
            schemaSelections = mapOf(
                "wubi06" to mapOf(
                    FontRole.Candidate to FontSelection(listOf(id), 22f),
                    FontRole.Popup to FontSelection(listOf(id, other)),
                    FontRole.Preedit to FontSelection(sizeSp = 16f)
                ),
                "wubi86" to mapOf(FontRole.Candidate to FontSelection(listOf(other))),
                "wubi98" to mapOf(FontRole.Candidate to FontSelection(listOf(other)))
            )
        )
        val encoded = FontConfigCodec.encode(expected)
        assertTrue(encoded.contains("\"version\":1"))
        assertEquals(expected, FontConfigCodec.decode(encoded))
        val old = FontConfigCodec.decode("""{"version":1,"fonts":[{"id":"$id","name":"old.ttf"}],
            "selections":{"Candidate":{"fontIds":["$id"],"sizeSp":18}}}""")
        assertTrue(old.schemaSelections.isEmpty())
        assertEquals(FontSelection(listOf(id), 18f), old.selections[FontRole.Candidate])
    }

    @Test
    fun `schema settings reject paths oversized identifiers and keyboard roles`() {
        val decoded = FontConfigCodec.decode("""{
            "version":1,"fonts":[{"id":"$id","name":"font.ttf"}],
            "schemaSelections":{
                "../wubi06":{"Candidate":{"fontIds":["$id"]}},
                "/wubi06":{"Candidate":{"fontIds":["$id"]}},
                "${"a".repeat(129)}":{"Candidate":{"fontIds":["$id"]}},
                "wubi06":{
                    "Global":{"fontIds":["$id"]},
                    "KeyMain":{"fontIds":["$id"]},
                    "KeyAlt":{"fontIds":["$id"]},
                    "Candidate":{"fontIds":["missing","$id","$id"],"sizeSp":20},
                    "Popup":{"fontIds":["missing"],"sizeSp":100},
                    "Preedit":{"sizeSp":16}
                }
            }
        }""")
        assertEquals(setOf("wubi06"), decoded.schemaSelections.keys)
        assertEquals(mapOf(
            FontRole.Candidate to FontSelection(listOf(id), 20f),
            FontRole.Preedit to FontSelection(sizeSp = 16f)
        ), decoded.schemaSelections["wubi06"])
        listOf("", ".", "..", "_private", "a/b", "a\\b", "a\nb", "字", "a".repeat(129)).forEach {
            assertFalse(it, FontConfigCodec.isValidSchemaId(it))
        }
        listOf("wubi06", "double_pinyin-flypy", "my.schema", "9schema", "a".repeat(128)).forEach {
            assertTrue(it, FontConfigCodec.isValidSchemaId(it))
        }
    }

    @Test
    fun `font and size inherit separately without changing keyboard label choices`() {
        val other = "abcdef0123456789abcdef0123456789"
        val global = FontSelection(listOf(other), 48f)
        val candidate = FontSelection(listOf(id), 18f)
        val scoped = FontSelection(sizeSp = 23f)
        val config = FontConfig(
            selections = mapOf(FontRole.Global to global, FontRole.Candidate to candidate),
            schemaSelections = mapOf("wubi06" to mapOf(
                FontRole.Candidate to scoped,
                FontRole.Preedit to FontSelection(listOf(id)),
                // Even malformed in-memory settings cannot affect ordinary key labels.
                FontRole.KeyMain to FontSelection(listOf(id), 30f)
            ))
        )
        assertEquals(listOf(scoped, candidate, global), config.fontLayers(FontRole.Candidate, "wubi06"))
        assertEquals(23f, config.roleSize(FontRole.Candidate, "wubi06"))
        assertEquals(listOf(candidate, global), config.fontLayers(FontRole.Candidate, "wubi98"))
        assertEquals(18f, config.roleSize(FontRole.Candidate, null))
        assertEquals(listOf(global), config.fontLayers(FontRole.KeyMain, "wubi06"))
        assertEquals(null, config.roleSize(FontRole.KeyMain, "wubi06"))
        assertEquals(null, config.roleSize(FontRole.Preedit, "wubi06"))
        assertEquals(null, config.roleSize(FontRole.Popup, "wubi06"))
    }

    @Test
    fun `deleting an imported font clears all schema references and keeps remaining sizes`() {
        val other = "abcdef0123456789abcdef0123456789"
        val config = FontConfig(
            fonts = listOf(ImportedFont(id, "HeiTi.ttf"), ImportedFont(other, "NVSans.ttf")),
            selections = mapOf(FontRole.Candidate to FontSelection(listOf(id, other), 18f)),
            schemaSelections = mapOf(
                "wubi06" to mapOf(FontRole.Candidate to FontSelection(listOf(id), 20f)),
                "wubi86" to mapOf(FontRole.Popup to FontSelection(listOf(id))),
                "wubi98" to mapOf(FontRole.Preedit to FontSelection(listOf(id, other)))
            )
        ).withoutFont(id)
        assertEquals(listOf(ImportedFont(other, "NVSans.ttf")), config.fonts)
        assertEquals(FontSelection(listOf(other), 18f), config.selections[FontRole.Candidate])
        assertEquals(FontSelection(sizeSp = 20f), config.schemaSelections["wubi06"]?.get(FontRole.Candidate))
        assertFalse(config.schemaSelections.containsKey("wubi86"))
        assertEquals(FontSelection(listOf(other)), config.schemaSelections["wubi98"]?.get(FontRole.Preedit))
        assertEquals(config, FontConfigCodec.decode(FontConfigCodec.encode(config)))
    }

    @Test
    fun `schema and fallback limits apply to restored settings`() {
        val fonts = (0 until 10).map { ImportedFont(it.toString(16).padStart(32, '0'), "font$it.ttf") }
        val decoded = FontConfigCodec.decode(FontConfigCodec.encode(FontConfig(
            fonts = fonts,
            schemaSelections = (0 until FontConfigCodec.MAX_SCHEMAS + 2).associate {
                "schema$it" to mapOf(FontRole.Popup to FontSelection(fonts.map { it.id }))
            }
        )))
        assertEquals(FontConfigCodec.MAX_SCHEMAS, decoded.schemaSelections.size)
        assertTrue(decoded.schemaSelections.values.all {
            it[FontRole.Popup]?.fontIds == fonts.take(FontConfigCodec.MAX_FALLBACKS).map { font -> font.id }
        })
    }

    @Test
    fun `configuration byte limit also applies to multibyte restored text`() {
        val raw = """{"version":1,"ignored":"${"汉".repeat(FontConfigCodec.MAX_CONFIG_BYTES / 2)}"}"""
        assertTrue(raw.length < FontConfigCodec.MAX_CONFIG_BYTES)
        assertEquals(FontConfig(), FontConfigCodec.decode(raw))
    }
}
