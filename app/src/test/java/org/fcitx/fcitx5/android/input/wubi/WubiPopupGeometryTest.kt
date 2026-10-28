/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.wubi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WubiPopupGeometryTest {
    @Test
    fun fullWidthCardDoesNotInvertItsRangeAtFractionalDeviceDensities() {
        val density = 419f / 160f
        val safeLeft = 8f * density
        val safeRight = 1080f - 8f * density
        val cardWidth = safeRight - safeLeft
        assertTrue("The reported 419dpi case must reproduce the rounding edge",
            safeRight - cardWidth < safeLeft)
        val left = WubiPopupGeometry.clampLeft(540f - cardWidth / 2f, cardWidth, safeLeft, safeRight)
        assertEquals(safeLeft, left, 0f)

        for (dpi in listOf(160, 240, 320, 397, 399, 402, 419, 480, 640)) {
            val scale = dpi / 160f
            val start = 8f * scale
            val end = 1080f - 8f * scale
            val width = minOf(420f * scale, end - start)
            for (preferred in listOf(-100f, start, 540f - width / 2f, 1200f)) {
                val result = WubiPopupGeometry.clampLeft(preferred, width, start, end)
                assertTrue(result >= start)
                assertTrue(result + width <= end + 0.001f)
            }
        }
    }

    @Test
    fun smallerCardsKeepCenteringAndClampAtBothEdges() {
        assertEquals(10f, WubiPopupGeometry.clampLeft(10f, 80f, 0f, 100f), 0f)
        assertEquals(0f, WubiPopupGeometry.clampLeft(-20f, 80f, 0f, 100f), 0f)
        assertEquals(20f, WubiPopupGeometry.clampLeft(80f, 80f, 0f, 100f), 0f)
    }
}
