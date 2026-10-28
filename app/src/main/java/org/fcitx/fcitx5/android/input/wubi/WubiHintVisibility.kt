/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

internal object WubiHintVisibility {
    fun canResolve(uniqueName: String, subModeIcon: String): Boolean =
        uniqueName == "rime" && subModeIcon != "fcitx_rime_disable" &&
            !subModeIcon.startsWith("fcitx_rime_latin")

    fun shouldShow(
        enabled: Boolean,
        isWubi: Boolean,
        keyboardAttached: Boolean,
        password: Boolean
    ): Boolean = enabled && isWubi && keyboardAttached && !password
}
