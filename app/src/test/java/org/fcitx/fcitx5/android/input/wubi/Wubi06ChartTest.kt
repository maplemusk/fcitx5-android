/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.wubi

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wubi06ChartTest {
    private val keys get() = Wubi06Chart.rows.flatten()

    @Test
    fun `the atlas includes every letter once in physical QWERTY order`() {
        assertEquals(
            listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"),
            Wubi06Chart.rows.map { row -> row.joinToString("") { it.key.toString() } }
        )
        assertEquals(26, keys.size)
        assertEquals(('A'..'Z').toSet(), keys.map { it.key }.toSet())
    }

    @Test
    fun `the bundled JPEG is the unmodified supplied image with the expected dimensions`() {
        val source = listOf(
            File("src/main/res/drawable-nodpi/wubi06_root_chart.jpg"),
            File("app/src/main/res/drawable-nodpi/wubi06_root_chart.jpg")
        ).firstOrNull { it.isFile } ?: error("Cannot find the bundled Wubi chart")
        val bytes = source.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(102007, bytes.size)
        assertEquals("ee5e7d14dc8f60756db1d0c2b4c01c10379b990270447b133017d7269b0bc9db", sha256)
        assertEquals(1280 to 437, jpegDimensions(bytes))
        assertEquals(1280, Wubi06Chart.IMAGE_WIDTH)
        assertEquals(437, Wubi06Chart.IMAGE_HEIGHT)
    }

    @Test
    fun `tiles have positive area stay in the atlas and never overlap`() {
        keys.forEach { hint ->
            val rect = hint.image
            assertTrue("Left edge of ${hint.key}", rect.left >= 0)
            assertTrue("Top edge of ${hint.key}", rect.top >= 0)
            assertTrue("Right edge of ${hint.key}", rect.right <= Wubi06Chart.IMAGE_WIDTH)
            assertTrue("Bottom edge of ${hint.key}", rect.bottom <= Wubi06Chart.IMAGE_HEIGHT)
            assertTrue("Width of ${hint.key}", rect.width > 0)
            assertTrue("Height of ${hint.key}", rect.height > 0)
            assertEquals(rect.right - rect.left, rect.width)
            assertEquals(rect.bottom - rect.top, rect.height)
        }
        keys.forEachIndexed { index, first ->
            keys.drop(index + 1).forEach { second ->
                val a = first.image
                val b = second.image
                val overlaps = a.left < b.right && b.left < a.right &&
                    a.top < b.bottom && b.top < a.bottom
                assertFalse("Source tiles ${first.key} and ${second.key} overlap", overlaps)
            }
        }
    }

    @Test
    fun `the three source bands use continuous white gutter cuts`() {
        val bands = listOf(9 to 149, 149 to 289, 289 to 428)
        Wubi06Chart.rows.forEachIndexed { index, row ->
            row.forEach { hint ->
                assertEquals(bands[index].first, hint.image.top)
                assertEquals(bands[index].second, hint.image.bottom)
            }
            row.zipWithNext().forEach { (left, right) ->
                assertEquals(left.image.right, right.image.left)
            }
        }
    }

    @Test
    fun `every tile contains the full measured border labels numbers and roots`() {
        // Independently measured content bounds in the original JPEG (minimum RGB channel < 245).
        // These encompass the complete colored cards and printed content, not just the main root.
        val content = mapOf(
            'Q' to WubiImageRect(11, 11, 134, 147),
            'W' to WubiImageRect(137, 11, 260, 147),
            'E' to WubiImageRect(264, 11, 386, 147),
            'R' to WubiImageRect(390, 11, 512, 147),
            'T' to WubiImageRect(516, 11, 638, 147),
            'Y' to WubiImageRect(642, 12, 764, 147),
            'U' to WubiImageRect(768, 11, 890, 147),
            'I' to WubiImageRect(894, 12, 1016, 147),
            'O' to WubiImageRect(1020, 11, 1143, 147),
            'P' to WubiImageRect(1146, 12, 1269, 147),
            'A' to WubiImageRect(74, 150, 197, 287),
            'S' to WubiImageRect(200, 150, 323, 287),
            'D' to WubiImageRect(327, 151, 449, 286),
            'F' to WubiImageRect(453, 150, 575, 287),
            'G' to WubiImageRect(579, 150, 701, 287),
            'H' to WubiImageRect(705, 150, 828, 287),
            'J' to WubiImageRect(831, 150, 953, 286),
            'K' to WubiImageRect(955, 150, 1080, 286),
            'L' to WubiImageRect(1083, 150, 1206, 287),
            'Z' to WubiImageRect(137, 290, 260, 426),
            'X' to WubiImageRect(263, 290, 386, 426),
            'C' to WubiImageRect(389, 290, 512, 426),
            'V' to WubiImageRect(516, 290, 638, 426),
            'B' to WubiImageRect(642, 290, 765, 426),
            'N' to WubiImageRect(768, 290, 891, 426),
            'M' to WubiImageRect(894, 290, 1016, 426)
        )
        assertEquals(keys.map { it.key }.toSet(), content.keys)
        keys.forEach { hint ->
            val full = content.getValue(hint.key)
            val crop = hint.image
            assertTrue("Clipped source card ${hint.key}",
                crop.left <= full.left && crop.top <= full.top &&
                    crop.right >= full.right && crop.bottom >= full.bottom)
        }
        assertEquals(WubiImageRect(136, 289, 262, 428), keys.single { it.key == 'Z' }.image)
    }

    @Test
    fun `invalid geometry and nonLatin keys are rejected before rendering`() {
        listOf(
            intArrayOf(-1, 0, 1, 1), intArrayOf(0, -1, 1, 1),
            intArrayOf(0, 0, 0, 1), intArrayOf(0, 0, 1, 0),
            intArrayOf(2, 0, 1, 1), intArrayOf(0, 2, 1, 1)
        ).forEach { bounds ->
            rejects { WubiImageRect(bounds[0], bounds[1], bounds[2], bounds[3]) }
        }
        listOf('a', '0', ' ', 'Ｓ', '木').forEach { key ->
            rejects { WubiKeyHint(key, WubiImageRect(0, 0, 1, 1)) }
        }
    }

    /** Read JPEG frame dimensions without Android APIs, an image decoder or extra dependencies. */
    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int> {
        val input = DataInputStream(bytes.inputStream())
        require(input.readUnsignedShort() == 0xffd8) { "Missing JPEG start marker" }
        val frameMarkers = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
            0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
        while (true) {
            require(input.readUnsignedByte() == 0xff) { "Invalid JPEG marker" }
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            require(marker != 0 && marker != 0xda && marker != 0xd9) { "Missing JPEG frame" }
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            val length = input.readUnsignedShort()
            require(length >= 2) { "Invalid JPEG segment length" }
            if (marker in frameMarkers) {
                require(length >= 8) { "Truncated JPEG frame" }
                input.readUnsignedByte() // Sample precision.
                val height = input.readUnsignedShort()
                val width = input.readUnsignedShort()
                return width to height
            }
            require(input.skipBytes(length - 2) == length - 2) { "Truncated JPEG segment" }
        }
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Expected invalid chart data to be rejected", rejected)
    }
}
