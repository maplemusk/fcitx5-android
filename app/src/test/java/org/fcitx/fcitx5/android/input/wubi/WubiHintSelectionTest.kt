/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.wubi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WubiHintSelectionTest {
    private fun letters(selection: WubiHintSelection?): List<Char?>? = selection?.let {
        listOf(it.left?.key, it.center.key, it.right?.key)
    }

    @Test
    fun `S shows physical neighbors A and D rather than code continuations`() {
        val selection = WubiHintSelection.forKey('S')
        assertEquals(listOf('A', 'S', 'D'), letters(selection))
        assertEquals(WubiImageRect(150, 302, 392, 572), selection?.left?.image)
        assertEquals(WubiImageRect(402, 302, 644, 572), selection?.center?.image)
        assertEquals(WubiImageRect(654, 302, 898, 572), selection?.right?.image)
    }

    @Test
    fun `row boundaries never wrap to another physical row`() {
        val boundaries = mapOf(
            'Q' to listOf(null, 'Q', 'W'),
            'P' to listOf('O', 'P', null),
            'A' to listOf(null, 'A', 'S'),
            'L' to listOf('K', 'L', null),
            'Z' to listOf(null, 'Z', 'X'),
            'M' to listOf('N', 'M', null)
        )
        boundaries.forEach { (key, expected) ->
            assertEquals("Neighbors for $key", expected, letters(WubiHintSelection.forKey(key)))
        }
    }

    @Test
    fun `all 26 centers reuse chart facts and lowercase selects the same key`() {
        val centers = ('A'..'Z').map { letter ->
            val selected = WubiHintSelection.forKey(letter)
            assertNotNull("Missing physical key $letter", selected)
            assertEquals(selected, WubiHintSelection.forKey(letter.lowercaseChar()))
            assertEquals(WubiCharts.forVariant(WubiVariant.Wubi06).rows.flatten().single { it.key == letter }, selected?.center)
            selected!!.center.key
        }
        assertEquals(26, centers.toSet().size)
    }

    @Test
    fun `Z selects its original image tile rather than a made up text label`() {
        val selected = WubiHintSelection.forKey('z')!!
        assertEquals('Z', selected.center.key)
        assertEquals(WubiImageRect(276, 580, 518, 850), selected.center.image)
        assertNull(selected.left)
        assertEquals('X', selected.right?.key)
    }

    @Test
    fun `nonletters and Unicode lookalikes cannot select a Latin key`() {
        listOf('0', '1', ' ', '\n', ';', '/', '木', 'Ｓ', 'ｓ', 'K', 'ſ', '\ud800').forEach {
            assertNull("Unexpected physical selection for U+${it.code.toString(16)}", WubiHintSelection.forKey(it))
        }
    }
}
