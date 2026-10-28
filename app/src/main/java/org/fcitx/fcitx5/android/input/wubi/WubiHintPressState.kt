/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

/** Keeps an old finger release or delayed dismissal from changing the latest key's hint. */
internal class WubiHintPressState {
    data class Ticket(val token: Long, val letter: Char, val generation: Long)

    var current: Ticket? = null
        private set
    private var held = false
    private var generation = 0L

    fun down(token: Long, letter: Char?) {
        ++generation
        current = letter?.let { Ticket(token, it, generation) }
        held = current != null
    }

    fun release(token: Long): Ticket? {
        val ticket = current ?: return null
        if (!held || ticket.token != token) return null
        held = false
        return ticket
    }

    fun cancel(token: Long): Boolean {
        if (current?.token != token) return false
        clear()
        return true
    }

    fun expire(ticket: Ticket): Boolean {
        if (held || current != ticket) return false
        clear()
        return true
    }

    fun clear() {
        ++generation
        current = null
        held = false
    }
}
