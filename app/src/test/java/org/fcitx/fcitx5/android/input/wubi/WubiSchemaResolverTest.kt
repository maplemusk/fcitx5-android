/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.wubi

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WubiSchemaResolverTest {
    @get:Rule val temp = TemporaryFolder()

    private fun schema(directory: File, id: String, name: String, fileName: String = "$id.schema.yaml") =
        File(directory, fileName).apply {
            writeText("schema:\n  schema_id: $id\n  name: $name\n")
        }

    @Test
    fun `all three exact deployed captions select their own chart with Ice alongside them`() {
        val directory = temp.newFolder()
        val captions = mapOf(WubiVariant.Wubi06 to "新世纪五笔", WubiVariant.Wubi86 to "五笔86",
            WubiVariant.Wubi98 to "五笔98")
        captions.forEach { (variant, name) -> schema(directory, variant.schemaId, name) }
        schema(directory, "rime_ice", "雾凇拼音")
        val resolver = WubiSchemaResolver(directory)
        captions.forEach { (variant, name) -> assertEquals(variant, resolver.resolve(name)) }
        assertNull(resolver.resolve("雾凇拼音"))
        assertNull(resolver.resolve("五笔"))
        assertNull(resolver.resolve(""))
    }

    @Test
    fun `quoted renamed captions and deployment changes are read afresh`() {
        val directory = temp.newFolder()
        val resolver = WubiSchemaResolver(directory)
        schema(directory, "wubi86", "'我的86 # 字根' # comment")
        assertEquals(WubiVariant.Wubi86, resolver.resolve("我的86 # 字根"))
        schema(directory, "wubi86", "\"86\\u4E94\\u7B14\"")
        assertEquals(WubiVariant.Wubi86, resolver.resolve("86五笔"))
        assertNull(resolver.resolve("我的86 # 字根"))
        schema(directory, "wubi98", "98五笔")
        assertEquals(WubiVariant.Wubi98, resolver.resolve("98五笔"))
        assertEquals(WubiVariant.Wubi86, resolver.resolve("86五笔"))
    }

    @Test
    fun `duplicate captions across variants unrelated schemas and aliases fail closed`() {
        val directory = temp.newFolder()
        val resolver = WubiSchemaResolver(directory)
        schema(directory, "wubi86", "五笔")
        val duplicate = schema(directory, "wubi98", "五笔")
        assertNull(resolver.resolve("五笔"))
        duplicate.delete()
        val other = schema(directory, "rime_ice", "五笔")
        assertNull(resolver.resolve("五笔"))
        other.delete()
        schema(directory, "wubi86", "五笔", "alias.schema.yaml")
        assertNull(resolver.resolve("五笔"))
    }

    @Test
    fun `supported IDs require the canonical matching filename and unsupported schemas cannot impersonate them`() {
        WubiVariant.entries.forEach { variant ->
            val directory = temp.newFolder()
            val resolver = WubiSchemaResolver(directory)
            val alias = schema(directory, variant.schemaId, "五笔", "alias.schema.yaml")
            assertNull(resolver.resolve("五笔"))
            alias.delete()
            schema(directory, "other", "五笔", "${variant.schemaId}.schema.yaml")
            assertNull(resolver.resolve("五笔"))
        }
    }

    @Test
    fun `invalid UTF8 scalar aliases and escaped schema files remain rejected for new variants`() {
        WubiVariant.entries.filter { it != WubiVariant.Wubi06 }.forEach { variant ->
            val directory = temp.newFolder()
            val resolver = WubiSchemaResolver(directory)
            val target = schema(directory, variant.schemaId, "*unknown")
            assertNull(resolver.resolve("五笔"))
            target.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertNull(resolver.resolve("五笔"))
            target.delete()
            val outside = temp.newFile().apply { writeText("schema:\n  schema_id: ${variant.schemaId}\n  name: 五笔\n") }
            Files.createSymbolicLink(target.toPath(), outside.toPath())
            assertNull(resolver.resolve("五笔"))
        }
    }
}
