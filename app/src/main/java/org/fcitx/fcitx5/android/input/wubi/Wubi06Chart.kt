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
 * Coordinates follow each pictured tile's border, excluding white gutters and outer JPEG fringe.
 * The rounded renderer keeps the original letters, numbers, roots and colors without reconstructing text;
 * Z is its own real image tile, just like the other 25 letters.
 */
object Wubi06Chart {
    const val IMAGE_WIDTH = 1280
    const val IMAGE_HEIGHT = 437

    // Source-pixel radius follows the original corners just inside their white JPEG fringe.
    const val TILE_CORNER_RADIUS = 11f

    val rows: List<List<WubiKeyHint>> = listOf(
        row("QWERTYUIOP", 12, 147,
            12 to 133, 138 to 259, 264 to 386, 390 to 512, 516 to 638, 642 to 764, 768 to 890, 894 to 1016, 1021 to 1142, 1147 to 1268),
        row("ASDFGHJKL", 151, 286,
            75 to 196, 201 to 322, 327 to 449, 453 to 575, 579 to 701, 705 to 827, 831 to 953, 958 to 1079, 1084 to 1205),
        row("ZXCVBNM", 290, 425,
            138 to 259, 264 to 386, 390 to 512, 516 to 638, 642 to 764, 768 to 890, 894 to 1016)
    )

    private fun row(keys: String, top: Int, bottom: Int, vararg bounds: Pair<Int, Int>): List<WubiKeyHint> {
        require(bounds.size == keys.length)
        return keys.mapIndexed { index, key ->
            val (left, right) = bounds[index]
            WubiKeyHint(key, WubiImageRect(left, top, right, bottom))
        }
    }
}
