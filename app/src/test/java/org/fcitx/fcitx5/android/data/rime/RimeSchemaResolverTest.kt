/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.rime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RimeSchemaResolverTest {
    @get:Rule val temp = TemporaryFolder()

    private fun schema(directory: File, id: String, name: String, fileName: String = "$id.schema.yaml") =
        File(directory, fileName).apply {
            writeText("schema:\n  schema_id: $id\n  name: $name\n")
        }

    @Test
    fun `stable IDs resolve renamed generic schemas and menu order excludes auxiliary schemas`() {
        val directory = temp.newFolder()
        schema(directory, "wubi06", "新世纪五笔")
        schema(directory, "wubi98", "五笔98")
        schema(directory, "rime_ice", "雾凇拼音")
        schema(directory, "my.double-pinyin", "'My custom # Pinyin'")
        schema(directory, "pinyin_aux", "拼音反查辅助")
        File(directory, "default.yaml").writeText("""
            schema_list:
              - schema: wubi98
              - schema: wubi06
              - schema: my.double-pinyin
              - schema: rime_ice
            switcher:
              caption: 方案
        """.trimIndent())
        val resolver = RimeSchemaResolver(directory)
        assertEquals(RimeSchema("my.double-pinyin", "My custom # Pinyin"), resolver.resolve("My custom # Pinyin"))
        assertEquals(listOf("wubi98", "wubi06", "my.double-pinyin", "rime_ice"),
            resolver.listMenuSchemas().map { it.id })
        assertEquals(5, resolver.listSchemas().size)
        schema(directory, "wubi06", "\"Renamed \\u4E94\\u7B14\"")
        assertEquals(RimeSchema("wubi06", "Renamed 五笔"), resolver.resolve("Renamed 五笔"))
        assertNull(resolver.resolve("新世纪五笔"))
    }

    @Test
    fun `duplicate captions and noncanonical aliases cannot select a font scope`() {
        val directory = temp.newFolder()
        schema(directory, "one", "Same")
        val other = schema(directory, "two", "Same")
        val resolver = RimeSchemaResolver(directory)
        assertNull(resolver.resolve("Same"))
        assertTrue(resolver.listSchemas().isEmpty())
        other.delete()
        assertEquals("one", resolver.resolve("Same")?.id)
        schema(directory, "one", "Same", "alias.schema.yaml")
        assertNull(resolver.resolve("Same"))
        assertTrue(resolver.listSchemas().isEmpty())
        schema(directory, "custom", "Alias only", "other.schema.yaml")
        assertNull(resolver.resolve("Alias only"))
    }

    @Test
    fun `missing unsupported or unsafe menu data falls back to canonical deployed metadata`() {
        val directory = temp.newFolder()
        schema(directory, "one", "One")
        schema(directory, "two", "Two")
        val resolver = RimeSchemaResolver(directory)
        assertEquals(listOf("one", "two"), resolver.listMenuSchemas().map { it.id })
        val menu = File(directory, "default.yaml")
        for (text in listOf("schema_list: [one, two]", "schema_list:\n  - schema: ../one\n")) {
            menu.writeText(text)
            assertEquals(listOf("one", "two"), resolver.listMenuSchemas().map { it.id })
        }
        menu.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(2, resolver.listMenuSchemas().size)
        menu.delete()
        val outside = temp.newFile().apply { writeText("schema_list:\n  - schema: two\n") }
        Files.createSymbolicLink(menu.toPath(), outside.toPath())
        assertEquals(listOf("one", "two"), resolver.listMenuSchemas().map { it.id })
        menu.delete()
        menu.writeText("schema_list: []\n")
        assertTrue(resolver.listMenuSchemas().isEmpty())
    }

    @Test
    fun `malformed metadata unsafe IDs and schema symlinks fail closed`() {
        val directory = temp.newFolder()
        val target = schema(directory, "one", "One")
        val resolver = RimeSchemaResolver(directory)
        for (text in listOf(
            "schema:\n  schema_id: ../one\n  name: One\n",
            "schema:\n  schema_id: one\n  name: *alias\n",
            "schema:\n  schema_id: one\n  schema_id: other\n  name: One\n"
        )) {
            target.writeText(text)
            assertNull(resolver.resolve("One"))
            assertTrue(resolver.listSchemas().isEmpty())
        }
        target.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertNull(resolver.resolve("One"))
        target.delete()
        val outside = temp.newFile().apply { writeText("schema:\n  schema_id: one\n  name: One\n") }
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        assertNull(resolver.resolve("One"))
        assertTrue(resolver.listSchemas().isEmpty())
    }

    @Test
    fun `schema count file size and total size limits are enforced`() {
        val directory = temp.newFolder()
        val resolver = RimeSchemaResolver(directory)
        val target = schema(directory, "one", "One")
        target.appendText("#".repeat(128 * 1024))
        assertNull(resolver.resolve("One"))
        target.delete()
        repeat(65) { schema(directory, "id$it", "Name$it") }
        assertTrue(resolver.listSchemas().isEmpty())
        directory.listFiles()!!.forEach(File::delete)
        repeat(17) {
            schema(directory, "id$it", "Name$it").appendText("#".repeat(125 * 1024))
        }
        assertTrue(resolver.listSchemas().isEmpty())
    }
}
