/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.font

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

/** Pure file checks are kept separate so malformed and oversized imports are testable on the JVM. */
internal object FontFileStore {
    const val MAX_FONT_BYTES = 64L * 1024 * 1024

    fun managedFile(directory: File, id: String): File {
        require(FontConfigCodec.isValidId(id)) { "Invalid font ID" }
        val root = directory.canonicalFile
        val target = File(root, "$id.font")
        require(target.canonicalFile.parentFile == root) { "Font path escapes its directory" }
        return target
    }

    fun copyImport(source: InputStream, directory: File, maxBytes: Long = MAX_FONT_BYTES): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create font directory" }
        val temp = File.createTempFile("import-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= maxBytes) { "Font exceeds the 64 MiB import limit" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            validateSfnt(temp)
            return temp
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    /** Check SFNT/TTC directories before passing data to Android's font parser. */
    fun validateSfnt(file: File) {
        require(file.length() in 12L..MAX_FONT_BYTES) { "Empty, truncated or oversized font" }
        RandomAccessFile(file, "r").use { input ->
            val signature = input.readInt()
            if (signature == 0x74746366) { // ttcf
                val version = input.readInt()
                require(version == 0x00010000 || version == 0x00020000) { "Invalid font collection version" }
                val count = input.readInt().toLong() and 0xffffffffL
                require(count in 1L..1024L && 12 + count * 4 <= input.length()) { "Invalid font collection directory" }
                val offsets = LongArray(count.toInt()) { input.readInt().toLong() and 0xffffffffL }
                offsets.forEach { validateFace(input, it) }
            } else {
                validateFace(input, 0)
            }
        }
    }

    private fun validateFace(input: RandomAccessFile, offset: Long) {
        val length = input.length()
        require(offset >= 0 && offset <= length - 12) { "Invalid font face offset" }
        input.seek(offset)
        val signature = input.readInt()
        require(signature == 0x00010000 || signature == 0x4f54544f || signature == 0x74727565) {
            "Select a TrueType, OpenType or TrueType Collection font"
        }
        val tableCount = input.readUnsignedShort()
        require(tableCount in 1..4096 && offset + 12 + tableCount * 16L <= length) { "Invalid font table directory" }
        input.skipBytes(6)
        repeat(tableCount) {
            input.skipBytes(8) // tag, checksum
            val tableOffset = input.readInt().toLong() and 0xffffffffL
            val tableLength = input.readInt().toLong() and 0xffffffffL
            require(tableOffset <= length && tableLength <= length - tableOffset) { "Truncated font table" }
        }
    }
}
