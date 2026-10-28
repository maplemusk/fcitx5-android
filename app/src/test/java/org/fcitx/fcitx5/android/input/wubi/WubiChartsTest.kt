/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.wubi

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WubiChartsTest {
    @Test
    fun `each supported schema owns a complete physical chart`() {
        WubiVariant.entries.forEach { variant ->
            assertEquals(variant, WubiVariant.forSchemaId(variant.schemaId))
            val chart = WubiCharts.forVariant(variant)
            assertEquals(variant, chart.variant)
            assertEquals(2560, chart.imageWidth)
            assertEquals(874, chart.imageHeight)
            assertEquals(listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"),
                chart.rows.map { row -> row.map { it.key }.joinToString("") })
            assertEquals(26, chart.rows.flatten().map { it.key }.toSet().size)
            assertTrue(chart.rows.flatten().all {
                it.image.right <= chart.imageWidth && it.image.bottom <= chart.imageHeight
            })
        }
        listOf("wubi", "wubi_86", "Wubi98", "wubi06_extra", "rime_ice", "").forEach {
            assertNull(WubiVariant.forSchemaId(it))
        }
    }

    @Test
    fun `enlarged 06 source pixels preserve its original crop and rounding proportions`() {
        val chart = WubiCharts.forVariant(WubiVariant.Wubi06)
        assertEquals(Wubi06Chart.TILE_CORNER_RADIUS * 2, chart.tileCornerRadius, 0f)
        chart.rows.flatten().zip(Wubi06Chart.rows.flatten()).forEach { (enlarged, original) ->
            assertEquals(original.key, enlarged.key)
            val source = original.image
            assertEquals(WubiImageRect(source.left * 2, source.top * 2,
                source.right * 2, source.bottom * 2), enlarged.image)
        }
    }

    @Test
    fun `supplied 98 cards use their native narrower proportions and independent rounding`() {
        val chart = WubiCharts.forVariant(WubiVariant.Wubi98)
        assertEquals(17f, chart.tileCornerRadius, 0f)
        val selected = WubiHintSelection.forKey('S', chart)!!
        assertEquals(WubiImageRect(413, 302, 633, 572), selected.center.image)
        assertEquals(WubiImageRect(161, 302, 381, 572), selected.left?.image)
        assertEquals(WubiImageRect(666, 302, 886, 572), selected.right?.image)
        assertNotEquals(WubiHintSelection.forKey('S')!!.center.image, selected.center.image)
    }

    @Test
    fun `switching a held key selects the new chart and never reuses its former image identity`() {
        val selections = listOf(WubiVariant.Wubi06, WubiVariant.Wubi86,
            WubiVariant.Wubi98, WubiVariant.Wubi06).map { variant ->
            WubiHintSelection.forKey('s', WubiCharts.forVariant(variant))!!
        }
        assertEquals(listOf(WubiVariant.Wubi06, WubiVariant.Wubi86,
            WubiVariant.Wubi98, WubiVariant.Wubi06), selections.map { it.chart.variant })
        selections.forEach { assertEquals(listOf('A', 'S', 'D'), listOf(it.left?.key, it.center.key, it.right?.key)) }
        assertNotEquals(selections[0], selections[1])
        assertNotEquals(selections[1], selections[2])
        assertEquals(selections[0], selections[3])
    }

    @Test
    fun `a chart with different crops uses those crops rather than 06 coordinates`() {
        val original = WubiCharts.forVariant(WubiVariant.Wubi86)
        val shifted = original.copy(imageWidth = original.imageWidth + 20,
            imageHeight = original.imageHeight + 10, tileCornerRadius = 9f,
            rows = original.rows.map { row -> row.map { hint ->
                val rect = hint.image
                hint.copy(image = WubiImageRect(rect.left + 5, rect.top + 3, rect.right + 5, rect.bottom + 3))
            } })
        val selected = WubiHintSelection.forKey('S', shifted)!!
        assertEquals(shifted, selected.chart)
        fun shiftedCrop(key: Char): WubiImageRect {
            val image = original.rows.flatten().single { it.key == key }.image
            return WubiImageRect(image.left + 5, image.top + 3, image.right + 5, image.bottom + 3)
        }
        assertEquals(shiftedCrop('S'), selected.center.image)
        assertEquals(shiftedCrop('A'), selected.left?.image)
        assertEquals(shiftedCrop('D'), selected.right?.image)
    }

    @Test
    fun `every variant keeps row boundaries and rejects nonletters`() {
        WubiVariant.entries.forEach { variant ->
            val chart = WubiCharts.forVariant(variant)
            assertNull(WubiHintSelection.forKey('q', chart)!!.left)
            assertNull(WubiHintSelection.forKey('p', chart)!!.right)
            assertNull(WubiHintSelection.forKey('a', chart)!!.left)
            assertNull(WubiHintSelection.forKey('l', chart)!!.right)
            assertNull(WubiHintSelection.forKey('z', chart)!!.left)
            assertNull(WubiHintSelection.forKey('m', chart)!!.right)
            listOf('0', ' ', '木', 'Ｓ', 'K', 'ſ').forEach { assertNull(WubiHintSelection.forKey(it, chart)) }
        }
    }

    @Test
    fun `all runtime charts are distinct high resolution PNGs with their descriptor dimensions`() {
        val hashes = WubiVariant.entries.map { variant ->
            val name = if (variant == WubiVariant.Wubi06) "wubi06_root_chart_2x.png"
                else "${variant.schemaId}_root_chart.png"
            val image = listOf(File("src/main/res/drawable-nodpi/$name"),
                File("app/src/main/res/drawable-nodpi/$name")).firstOrNull { it.isFile }
                ?: error("Missing bundled chart $name")
            val bytes = image.readBytes()
            DataInputStream(bytes.inputStream()).use { input ->
                assertEquals(0x89504e470d0a1a0aUL.toLong(), input.readLong())
                assertEquals(13, input.readInt())
                assertEquals(0x49484452, input.readInt()) // IHDR
                val chart = WubiCharts.forVariant(variant)
                assertEquals(chart.imageWidth, input.readInt())
                assertEquals(chart.imageHeight, input.readInt())
            }
            MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        }
        assertEquals(3, hashes.toSet().size)
    }
}
