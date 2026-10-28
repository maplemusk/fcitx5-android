/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.wubi

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Wubi06SchemaResolverTest {
    @get:Rule val temp = TemporaryFolder()

    private fun schema(directory: File, id: String, nameValue: String, fileName: String = "$id.schema.yaml"): File =
        File(directory, fileName).apply {
            writeText("""
                __build_info:
                  rime_version: 1.12.0
                schema:
                  author:
                    - Example
                  schema_id: $id
                  name: $nameValue
                  version: "1.0"
                translator:
                  dictionary: private_dictionary_is_not_read
            """.trimIndent())
        }

    @Test
    fun `deployed renamed Chinese caption matches exactly`() {
        val directory = temp.newFolder()
        schema(directory, "wubi06", "新世纪五笔 [Enterfix] # deployment comment")
        schema(directory, "luna_pinyin", "朙月拼音")
        val resolver = Wubi06SchemaResolver(directory)
        assertTrue(resolver.matches("新世纪五笔 [Enterfix]"))
        assertFalse(resolver.matches("新世纪五笔"))
        assertFalse(resolver.matches(""))
        // Dictionary/user history files are outside the resolver's input set.
        File(directory, "private.userdb").writeText("not schema metadata")
        assertTrue(resolver.matches("新世纪五笔 [Enterfix]"))
    }

    @Test
    fun `single quoted apostrophes and double quoted Unicode are decoded`() {
        val directory = temp.newFolder()
        schema(directory, "wubi06", "'新世纪''五笔 # Enterfix' # trailing comment")
        assertTrue(Wubi06SchemaResolver(directory).matches("新世纪'五笔 # Enterfix"))
        schema(directory, "wubi06", "\"\\u65B0\\u4E16\\u7EAA\\u4E94\\u7B14 [Enterfix] \\U0001F600\" # comment")
        assertTrue(Wubi06SchemaResolver(directory).matches("新世纪五笔 [Enterfix] 😀"))
        schema(directory, "wubi06", "\"Wubi \\x23 \\uD83D\\uDE00\"")
        assertTrue(Wubi06SchemaResolver(directory).matches("Wubi # 😀"))
    }

    @Test
    fun `another schema sharing the caption is ambiguous`() {
        val directory = temp.newFolder()
        schema(directory, "wubi06", "新世纪五笔")
        schema(directory, "other_wubi", "'新世纪五笔'")
        assertFalse(Wubi06SchemaResolver(directory).matches("新世纪五笔"))
    }

    @Test
    fun `missing mismatched and malformed target fail closed`() {
        val directory = temp.newFolder()
        val resolver = Wubi06SchemaResolver(directory)
        assertFalse(resolver.matches("Wubi"))
        schema(directory, "other", "Wubi", "wubi06.schema.yaml")
        assertFalse(resolver.matches("Wubi"))
        for (value in listOf("|\n    Wubi", ">\n    Wubi", "*name", "&name Wubi", "\"Wubi", "Wubi\n    continuation", "\"\\uD800\"")) {
            schema(directory, "wubi06", value)
            assertFalse("Unsupported scalar: $value", resolver.matches("Wubi"))
        }
        File(directory, "wubi06.schema.yaml").writeText("schema:\n  schema_id: wubi06\n  name: Wubi\n  name: Wubi\n")
        assertFalse(resolver.matches("Wubi"))
        File(directory, "wubi06.schema.yaml").writeText("schema:\n  schema_id: wubi06\n  name: Wubi\n\"schema\": {schema_id: other, name: Wubi}\n")
        assertFalse(resolver.matches("Wubi"))
    }

    @Test
    fun `unreadable metadata in another compiled schema fails closed`() {
        val directory = temp.newFolder()
        schema(directory, "wubi06", "Wubi")
        File(directory, "other.schema.yaml").writeText("schema:\n  schema_id: other\n  name: *unknown\n")
        assertFalse(Wubi06SchemaResolver(directory).matches("Wubi"))
    }

    @Test
    fun `file count individual bytes and total bytes are bounded`() {
        val directory = temp.newFolder()
        schema(directory, "wubi06", "Wubi")
        repeat(64) { schema(directory, "other$it", "Other $it") }
        assertFalse(Wubi06SchemaResolver(directory).matches("Wubi"))
        directory.listFiles().orEmpty().filter { it.name != "wubi06.schema.yaml" }.forEach { it.delete() }
        val file = schema(directory, "wubi06", "Wubi")
        file.appendText("\n#" + "x".repeat(128 * 1024))
        assertFalse(Wubi06SchemaResolver(directory).matches("Wubi"))
        schema(directory, "wubi06", "Wubi")
        repeat(17) {
            schema(directory, "other$it", "Other $it").appendText("\n#" + "x".repeat(124 * 1024))
        }
        assertFalse(Wubi06SchemaResolver(directory).matches("Wubi"))
    }

    @Test
    fun `schema symlinks and invalid UTF8 fail closed`() {
        val directory = temp.newFolder()
        val outside = temp.newFile()
        outside.writeText("schema:\n  schema_id: wubi06\n  name: Wubi\n")
        Files.createSymbolicLink(File(directory, "wubi06.schema.yaml").toPath(), outside.toPath())
        assertFalse(Wubi06SchemaResolver(directory).matches("Wubi"))
        File(directory, "wubi06.schema.yaml").delete()
        File(directory, "wubi06.schema.yaml").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFalse(Wubi06SchemaResolver(directory).matches("Wubi"))
    }
}
