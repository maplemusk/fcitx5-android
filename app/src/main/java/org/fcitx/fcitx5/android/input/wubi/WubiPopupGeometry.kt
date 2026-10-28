/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

internal object WubiPopupGeometry {
    fun clampLeft(preferred: Float, cardWidth: Float, safeLeft: Float, safeRight: Float): Float {
        // Subtracting a full-width card from the right edge can round a few
        // float ULPs below safeLeft. coerceIn requires a non-inverted range.
        val rightmost = (safeRight - cardWidth).coerceAtLeast(safeLeft)
        return preferred.coerceIn(safeLeft, rightmost)
    }
}
