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
    fun `tight crops exclude the original white gutter rectangles`() {
        val bands = listOf(9 to 149, 149 to 289, 289 to 428)
        Wubi06Chart.rows.forEachIndexed { index, row ->
            row.forEach { hint ->
                assertTrue(hint.image.top > bands[index].first)
                assertTrue(hint.image.bottom < bands[index].second)
                assertTrue(Wubi06Chart.TILE_CORNER_RADIUS > 0f)
                assertTrue(Wubi06Chart.TILE_CORNER_RADIUS * 2 < hint.image.width)
            }
            row.zipWithNext().forEach { (left, right) ->
                assertTrue(left.image.right < right.image.left)
            }
        }
    }

    @Test
    fun `tight rounded crops retain the measured printed letters numbers and roots`() {
        // Foreground bounds measured from the original JPEG, excluding only its outer border.
        // Minimum RGB < 155 in each original tile's inset content area identifies printed ink.
        val content = mapOf(
            'Q' to WubiImageRect(20, 20, 125, 131),
            'W' to WubiImageRect(145, 20, 252, 126),
            'E' to WubiImageRect(271, 20, 380, 126),
            'R' to WubiImageRect(398, 20, 506, 126),
            'T' to WubiImageRect(523, 20, 632, 126),
            'Y' to WubiImageRect(650, 20, 758, 125),
            'U' to WubiImageRect(777, 20, 884, 126),
            'I' to WubiImageRect(903, 20, 1010, 95),
            'O' to WubiImageRect(1028, 20, 1133, 126),
            'P' to WubiImageRect(1155, 20, 1259, 125),
            'A' to WubiImageRect(82, 160, 188, 264),
            'S' to WubiImageRect(208, 160, 314, 264),
            'D' to WubiImageRect(334, 159, 443, 264),
            'F' to WubiImageRect(461, 159, 569, 265),
            'G' to WubiImageRect(586, 160, 695, 266),
            'H' to WubiImageRect(713, 160, 821, 265),
            'J' to WubiImageRect(839, 160, 947, 264),
            'K' to WubiImageRect(966, 160, 1074, 264),
            'L' to WubiImageRect(1092, 160, 1196, 263),
            'Z' to WubiImageRect(146, 300, 215, 374),
            'X' to WubiImageRect(272, 299, 378, 403),
            'C' to WubiImageRect(398, 299, 503, 405),
            'V' to WubiImageRect(524, 299, 632, 401),
            'B' to WubiImageRect(650, 299, 758, 404),
            'N' to WubiImageRect(776, 299, 884, 405),
            'M' to WubiImageRect(903, 299, 1007, 405)
        )
        assertEquals(keys.map { it.key }.toSet(), content.keys)
        keys.forEach { hint ->
            val full = content.getValue(hint.key)
            val crop = hint.image
            assertTrue("Clipped printed content ${hint.key}",
                crop.left <= full.left && crop.top <= full.top &&
                    crop.right >= full.right && crop.bottom >= full.bottom)
            // If the whole ink bounding box fits the convex rounded crop, so do all ink pixels.
            val radius = Wubi06Chart.TILE_CORNER_RADIUS
            for (x in listOf(full.left.toFloat(), full.right.toFloat())) {
                for (y in listOf(full.top.toFloat(), full.bottom.toFloat())) {
                    val dx = maxOf(crop.left + radius - x, 0f, x - (crop.right - radius))
                    val dy = maxOf(crop.top + radius - y, 0f, y - (crop.bottom - radius))
                    assertTrue("Rounded corner clips ${hint.key}", dx * dx + dy * dy <= radius * radius)
                }
            }
        }
        assertEquals(WubiImageRect(138, 290, 259, 425), keys.single { it.key == 'Z' }.image)
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
