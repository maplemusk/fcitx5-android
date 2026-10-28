/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WubiHintPressStateTest {
    @Test
    fun olderFingerReleaseAndCancelCannotAffectNewerPress() {
        val state = WubiHintPressState()
        state.down(1, 'A')
        state.down(2, 'S')
        assertNull(state.release(1))
        assertFalse(state.cancel(1))
        assertEquals('S', state.current?.letter)
        assertFalse(state.expire(state.current!!)) // still held
    }

    @Test
    fun oldLingerCannotHideNewPressEvenOfTheSameLetter() {
        val state = WubiHintPressState()
        state.down(1, 'A')
        val oldLinger = state.release(1)!!
        state.down(2, 'A')
        assertFalse(state.expire(oldLinger))
        val currentLinger = state.release(2)!!
        assertTrue(state.expire(currentLinger))
        assertNull(state.current)
    }

    @Test
    fun nonLetterPressAndLifecycleClearInvalidatePendingLinger() {
        val state = WubiHintPressState()
        state.down(1, 'Q')
        val oldLinger = state.release(1)!!
        state.down(2, null)
        assertNull(state.current)
        assertFalse(state.expire(oldLinger))
        state.down(3, 'W')
        val nextLinger = state.release(3)!!
        state.clear()
        assertFalse(state.expire(nextLinger))
        assertNull(state.current)
    }

    @Test
    fun cancellationAndDuplicateReleaseDoNotRestartLinger() {
        val state = WubiHintPressState()
        state.down(1, 'Z')
        assertTrue(state.cancel(1))
        assertNull(state.release(1))
        state.down(2, 'X')
        val ticket = state.release(2)!!
        assertNull(state.release(2))
        assertTrue(state.expire(ticket))
    }
}
