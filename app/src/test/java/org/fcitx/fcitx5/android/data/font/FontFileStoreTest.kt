/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.font

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    // This exercises container bounds only, not Android's separate font rasterizer validation.
    private fun sfnt(tableOffset: Int = 28, tableLength: Int = 4): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x00010000)
            output.writeShort(1)
            output.writeShort(16)
            output.writeShort(0)
            output.writeShort(0)
            output.writeInt(0x68656164) // head
            output.writeInt(0)
            output.writeInt(tableOffset)
            output.writeInt(tableLength)
            output.writeInt(0)
        }
    }.toByteArray()

    @Test
    fun `bounded import preserves bytes for subsequent Android validation`() {
        val directory = temp.newFolder()
        val bytes = sfnt()
        val copied = FontFileStore.copyImport(bytes.inputStream(), directory)
        assertEquals(directory.canonicalFile, requireNotNull(copied.parentFile).canonicalFile)
        assertArrayEquals(bytes, copied.readBytes())
    }

    @Test
    fun `oversized and nonfont imports leave no temporary files`() {
        val directory = temp.newFolder()
        expectFailure { FontFileStore.copyImport(ByteArray(33).inputStream(), directory, 32) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        expectFailure { FontFileStore.copyImport("not a font at all".byteInputStream(), directory) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `read errors clean up the temporary file`() {
        val directory = temp.newFolder()
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("interrupted document provider")
        }
        expectFailure { FontFileStore.copyImport(failing, directory) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `out of range SFNT table and TTC face offsets are rejected`() {
        val directory = temp.newFolder()
        expectFailure { FontFileStore.copyImport(sfnt(0xfffffff0.toInt(), 4).inputStream(), directory) }
        expectFailure { FontFileStore.copyImport(sfnt(28, Int.MAX_VALUE).inputStream(), directory) }
        val collection = ByteArrayOutputStream().also {
            DataOutputStream(it).use { output ->
                output.writeInt(0x74746366)
                output.writeInt(0x00010000)
                output.writeInt(1)
                output.writeInt(Int.MAX_VALUE)
            }
        }.toByteArray()
        expectFailure { FontFileStore.copyImport(collection.inputStream(), directory) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `font IDs cannot traverse or follow links outside storage`() {
        val directory = temp.newFolder()
        val id = "0123456789abcdef0123456789abcdef"
        expectFailure { FontFileStore.managedFile(directory, "../$id") }
        expectFailure { FontFileStore.managedFile(directory, "/$id") }
        assertEquals(directory.canonicalFile, FontFileStore.managedFile(directory, id).parentFile)
        val outside = temp.newFile()
        Files.createSymbolicLink(File(directory, "$id.font").toPath(), outside.toPath())
        expectFailure { FontFileStore.managedFile(directory, id) }
    }

    private fun expectFailure(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue("Expected the import or path check to reject the input", failed)
    }
}
