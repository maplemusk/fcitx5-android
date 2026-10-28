/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

/** Physical neighbors in the same QWERTY row, not Wubi code completions. */
data class WubiHintSelection(
    val left: WubiKeyHint?,
    val center: WubiKeyHint,
    val right: WubiKeyHint?
) {
    companion object {
        /** Accept ASCII letters only; in particular, do not case-fold Unicode into Latin keys. */
        fun forKey(letter: Char): WubiHintSelection? {
            val key = when (letter) {
                in 'A'..'Z' -> letter
                in 'a'..'z' -> letter - ('a' - 'A')
                else -> return null
            }
            for (row in Wubi06Chart.rows) {
                val index = row.indexOfFirst { it.key == key }
                if (index >= 0) {
                    return WubiHintSelection(row.getOrNull(index - 1), row[index], row.getOrNull(index + 1))
                }
            }
            return null
        }
    }
}
