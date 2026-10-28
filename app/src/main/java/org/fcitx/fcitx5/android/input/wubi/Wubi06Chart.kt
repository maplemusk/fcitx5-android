/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

/** Pixel coordinates in the original chart, with exclusive right and bottom bounds. */
data class WubiImageRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && left < right && top < bottom) {
            "Image rectangles must have nonnegative coordinates and positive area"
        }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class WubiKeyHint(val key: Char, val image: WubiImageRect) {
    init {
        require(key in 'A'..'Z') { "Expected an uppercase Latin key" }
    }
}

/**
 * Complete key tiles from the supplied New Century Wubi chart, in physical QWERTY order.
 *
 * Coordinates include each tile's original letters, numbers, roots, colors and rounded border.
 * Cuts fall in white gutters. The renderer uses the original bitmap without reconstructing text;
 * Z is its own real image tile, just like the other 25 letters.
 */
object Wubi06Chart {
    const val IMAGE_WIDTH = 1280
    const val IMAGE_HEIGHT = 437

    val rows: List<List<WubiKeyHint>> = listOf(
        row("QWERTYUIOP", 9, 149, intArrayOf(9, 136, 262, 388, 514, 640, 766, 892, 1018, 1145, 1271)),
        row("ASDFGHJKL", 149, 289, intArrayOf(72, 199, 325, 451, 577, 703, 829, 955, 1082, 1208)),
        row("ZXCVBNM", 289, 428, intArrayOf(136, 262, 388, 514, 640, 766, 892, 1018))
    )

    private fun row(keys: String, top: Int, bottom: Int, edges: IntArray): List<WubiKeyHint> {
        require(edges.size == keys.length + 1)
        return keys.mapIndexed { index, key ->
            WubiKeyHint(key, WubiImageRect(edges[index], top, edges[index + 1], bottom))
        }
    }
}
