/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WubiHintVisibilityTest {
    @Test
    fun onlyResolvesChineseRimeModeRegardlessOfCaption() {
        assertTrue(WubiHintVisibility.canResolve("rime", "fcitx-rime"))
        assertFalse(WubiHintVisibility.canResolve("wubi", "fcitx-rime"))
        assertFalse(WubiHintVisibility.canResolve("keyboard-us", "fcitx-rime"))
        assertFalse(WubiHintVisibility.canResolve("rime", "fcitx_rime_latin"))
        assertFalse(WubiHintVisibility.canResolve("rime", "fcitx_rime_latin_upper"))
        assertFalse(WubiHintVisibility.canResolve("rime", "fcitx_rime_disable"))
    }

    @Test
    fun showsForEnabledWubi06KeyboardWithoutNeedingCandidates() {
        assertTrue(WubiHintVisibility.shouldShow(true, true, true, false))
    }

    @Test
    fun hidesWhenDisabledOrAnotherSchemeOrWindowIsActive() {
        assertFalse(WubiHintVisibility.shouldShow(false, true, true, false))
        assertFalse(WubiHintVisibility.shouldShow(true, false, true, false))
        assertFalse(WubiHintVisibility.shouldShow(true, true, false, false))
    }

    @Test
    fun hidesForPasswordEditors() {
        assertFalse(WubiHintVisibility.shouldShow(true, true, true, true))
    }
}
