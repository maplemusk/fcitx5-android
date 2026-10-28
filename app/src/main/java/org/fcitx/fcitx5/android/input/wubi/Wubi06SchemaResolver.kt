/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

import java.io.File

/** Compatibility entry point for callers that specifically need the 06 variant. */
class Wubi06SchemaResolver(directory: File) {
    private val resolver = WubiSchemaResolver(directory)

    fun matches(displayName: String): Boolean = resolver.resolve(displayName) == WubiVariant.Wubi06
}
