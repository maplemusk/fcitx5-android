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
}
