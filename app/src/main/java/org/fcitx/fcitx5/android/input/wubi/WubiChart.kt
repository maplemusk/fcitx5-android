/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

/** A supported Rime schema, independent of its configurable display caption. */
enum class WubiVariant(val schemaId: String) {
    Wubi06("wubi06"),
    Wubi86("wubi86"),
    Wubi98("wubi98");

    companion object {
        fun forSchemaId(schemaId: String): WubiVariant? = entries.singleOrNull { it.schemaId == schemaId }
    }
}

/** Original image geometry for one variant; rendered roots always come from its bitmap. */
data class WubiChart(
    val variant: WubiVariant,
    val imageWidth: Int,
    val imageHeight: Int,
    val tileCornerRadius: Float,
    val rows: List<List<WubiKeyHint>>
) {
    init {
        require(imageWidth > 0 && imageHeight > 0)
        require(tileCornerRadius.isFinite() && tileCornerRadius > 0f)
        require(rows.map { row -> row.map { it.key }.joinToString("") } == QWERTY_ROWS)
        val tiles = rows.flatten()
        require(tiles.size == 26 && tiles.map { it.key }.toSet().size == 26)
        require(tiles.all {
            it.image.right <= imageWidth && it.image.bottom <= imageHeight &&
                tileCornerRadius * 2 < minOf(it.image.width, it.image.height)
        })
        require(tiles.indices.all { index ->
            tiles.drop(index + 1).none { other ->
                val first = tiles[index].image
                val second = other.image
                first.left < second.right && second.left < first.right &&
                    first.top < second.bottom && second.top < first.bottom
            }
        })
    }

    private companion object {
        val QWERTY_ROWS = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    }
}

object WubiCharts {
    const val SOURCE_SCALE = 2
    const val IMAGE_WIDTH = Wubi06Chart.IMAGE_WIDTH * SOURCE_SCALE
    const val IMAGE_HEIGHT = Wubi06Chart.IMAGE_HEIGHT * SOURCE_SCALE

    // These are image pixels only. The popup's dp dimensions stay unchanged.
    // 86 uses the enlarged 06 frame positions. The supplied 98 cards retain
    // their narrower proportions and independent rounding/crop bounds.
    private val charts = mapOf(
        WubiVariant.Wubi06 to WubiChart(WubiVariant.Wubi06, IMAGE_WIDTH, IMAGE_HEIGHT,
            Wubi06Chart.TILE_CORNER_RADIUS * SOURCE_SCALE, enlarged06Rows()),
        WubiVariant.Wubi86 to WubiChart(WubiVariant.Wubi86, IMAGE_WIDTH, IMAGE_HEIGHT,
            Wubi06Chart.TILE_CORNER_RADIUS * SOURCE_SCALE, enlarged06Rows()),
        WubiVariant.Wubi98 to WubiChart(WubiVariant.Wubi98, IMAGE_WIDTH, IMAGE_HEIGHT,
            17f, supplied98Rows())
    )

    private fun enlarged06Rows(): List<List<WubiKeyHint>> = Wubi06Chart.rows.map { row ->
        row.map { hint ->
            val image = hint.image
            hint.copy(image = WubiImageRect(image.left * SOURCE_SCALE, image.top * SOURCE_SCALE,
                image.right * SOURCE_SCALE, image.bottom * SOURCE_SCALE))
        }
    }

    // The supplied 98 cards are narrower than the 06 slots. Their original
    // aspect ratio and bottom-positioned labels are retained in these crops.
    private fun supplied98Rows(): List<List<WubiKeyHint>> = listOf(
        row("QWERTYUIOP", 24, 294,
            35 to 255, 287 to 507, 540 to 760, 792 to 1012, 1044 to 1264,
            1296 to 1516, 1548 to 1768, 1800 to 2020, 2053 to 2273, 2305 to 2525),
        row("ASDFGHJKL", 302, 572,
            161 to 381, 413 to 633, 666 to 886, 918 to 1138, 1170 to 1390,
            1422 to 1642, 1674 to 1894, 1927 to 2147, 2179 to 2399),
        row("ZXCVBNM", 580, 850,
            276 to 518, 540 to 760, 792 to 1012, 1044 to 1264, 1296 to 1516,
            1548 to 1768, 1800 to 2020)
    )

    private fun row(keys: String, top: Int, bottom: Int, vararg bounds: Pair<Int, Int>): List<WubiKeyHint> {
        require(bounds.size == keys.length)
        return keys.mapIndexed { index, key ->
            val (left, right) = bounds[index]
            WubiKeyHint(key, WubiImageRect(left, top, right, bottom))
        }
    }

    fun forVariant(variant: WubiVariant): WubiChart = charts.getValue(variant)
}
