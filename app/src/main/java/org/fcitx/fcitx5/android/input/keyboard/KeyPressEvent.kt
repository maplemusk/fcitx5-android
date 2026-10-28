/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import java.util.concurrent.atomic.AtomicLong

/** Passive touch feedback; these events never commit text or consume a keyboard gesture. */
data class KeyPressEvent(val token: Long, val phase: Phase, val letter: Char?) {
    enum class Phase { Down, Release, Cancel }

    companion object {
        private val sequence = AtomicLong()

        internal fun nextToken(): Long = sequence.incrementAndGet()
    }
}
