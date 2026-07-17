package com.kingzcheung.xime.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

private fun createCustomPhraseFile(rimeDir: File, schemaId: String) {
    val dictName = PersonalDictManager.getCustomPhraseDictName(rimeDir, schemaId)
    File(rimeDir, "$dictName.txt").writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
hello	hw
123	abc
""")
}

private fun mockContext(): Context {
    val tmpDir = File.createTempFile("mock_context", "").apply { delete(); mkdirs() }
    return mock {
        on { filesDir } doReturn tmpDir
    }
}

class PersonalDictManagerTest {

    private val defaultHeader = """# Rime dict
---
name: user_simp_pinyin
version: '1.0'
sort: original
use_preset_vocabulary: false
...
"""

    @Test
    fun `default header has correct Rime format`() {
        assertTrue(defaultHeader.startsWith("# Rime dict"))
        assertTrue(defaultHeader.contains("name: user_simp_pinyin"))
        assertTrue(defaultHeader.contains("version: '1.0'"))
        assertTrue(defaultHeader.contains("sort: original"))
        assertTrue(defaultHeader.contains("use_preset_vocabulary: false"))
        assertTrue(defaultHeader.endsWith("...\n"))
    }

    @Test
    fun `extractHeader returns header before marker`() {
        val text = """# Rime dict
---
name: user_simp
version: '1.0'
...
粗鄙之语	cu bi zhi yu
"""
        val file = createTempFile(text)
        val result = PersonalDictManager.extractHeader(file, defaultHeader)
        assertTrue(result.startsWith("# Rime dict"))
        assertTrue(result.endsWith("...\n"))
        assertEquals("""# Rime dict
---
name: user_simp
version: '1.0'
...
""", result)
    }

    @Test
    fun `extractHeader returns default for non-existent file`() {
        val file = File.createTempFile("test_nonexistent", ".tmp")
        file.delete()
        val result = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, result)
    }

    @Test
    fun `extractHeader returns default for file without marker`() {
        val file = createTempFile("no marker here\njust text\n")
        val result = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, result)
    }

    @Test
    fun `extractHeaderFromText handles marker in middle`() {
        val text = "prefix\n...\nsuffix"
        val result = PersonalDictManager.extractHeaderFromText(text, defaultHeader)
        assertEquals("prefix\n...\n", result)
    }

    @Test
    fun `extractHeaderFromText handles no preceding newline`() {
        val text = "...\nsuffix"
        val result = PersonalDictManager.extractHeaderFromText(text, defaultHeader)
        assertEquals("...\n", result)
    }

    @Test
    fun `extractHeaderFromText returns default when no marker`() {
        assertEquals(defaultHeader, PersonalDictManager.extractHeaderFromText("no marker", defaultHeader))
    }

    @Test
    fun `buildDictText preserves header and formats entries`() {
        val entries = listOf(
            DictEntry("测试", "ce shi"),
            DictEntry("词条", "ci tiao")
        )
        val result = PersonalDictManager.buildDictText(defaultHeader, entries)
        val expected = """# Rime dict
---
name: user_simp_pinyin
version: '1.0'
sort: original
use_preset_vocabulary: false
...
测试	ce shi
词条	ci tiao
"""
        assertEquals(expected, result)
    }

    @Test
    fun `buildDictText output uses tab delimiter not space`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, listOf(DictEntry("你好", "ni hao")))
        assertTrue(result.contains("你好\tni hao"))
        assertFalse(result.contains("你好 ni hao"))
    }

    @Test
    fun `buildDictText ends each entry with newline`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, listOf(DictEntry("a", "b")))
        assertTrue(result.endsWith("a\tb\n"))
    }

    @Test
    fun `buildDictText handles empty entries`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, emptyList())
        assertEquals(defaultHeader, result)
    }

    @Test
    fun `round trip preserves entries with multi-word codes`() {
        val originalEntries = listOf(
            DictEntry("你好", "ni hao"),
            DictEntry("世界", "shi jie"),
            DictEntry("测试", "ce shi")
        )
        val text = PersonalDictManager.buildDictText(defaultHeader, originalEntries)
        val parsedEntries = PersonalDictManager.parsePersonalDictEntries(text)
        assertEquals(originalEntries.size, parsedEntries.size)
        assertEquals(originalEntries, parsedEntries)
    }

    @Test
    fun `full file round-trip preserves entries`() {
        val file = File.createTempFile("personal_dict_roundtrip", ".tmp")
        file.deleteOnExit()
        val originalEntries = listOf(
            DictEntry("你好", "ni hao"),
            DictEntry("世界", "shi jie")
        )
        PersonalDictManager.buildDictText(defaultHeader, originalEntries).let { file.writeText(it, Charsets.UTF_8) }
        val newHeader = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, newHeader)
        val parsed = PersonalDictManager.parsePersonalDictEntries(file.readText(Charsets.UTF_8))
        assertEquals(originalEntries, parsed)
    }

    @Test
    fun `file created from scratch has correct format`() {
        val file = File.createTempFile("personal_dict_scratch", ".tmp")
        file.deleteOnExit()
        file.delete()
        val h = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, h)
        val entries = listOf(DictEntry("a", "b"))
        val text = PersonalDictManager.buildDictText(h, entries)
        file.writeText(text, Charsets.UTF_8)
        val content = file.readText(Charsets.UTF_8)
        assertTrue(content.startsWith("# Rime dict"))
        assertTrue(content.contains("name: user_simp_pinyin"))
        assertTrue(content.contains("...\n"))
        assertTrue(content.contains("a\tb"))
    }

    @Test
    fun `parsePersonalDictEntries reads entries after marker tab-delimited`() {
        val text = "# comment\n---\nname: x\n...\n日\ta\n曰\ta\n郎\tivnl\n"
        assertEquals(
            listOf(DictEntry("日", "a"), DictEntry("曰", "a"), DictEntry("郎", "ivnl")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries preserves spaces in code`() {
        val text = "...\n你好\tni hao\n世界\tshi jie\n"
        assertEquals(
            listOf(DictEntry("你好", "ni hao"), DictEntry("世界", "shi jie")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries falls back to space delimiter if no tab`() {
        val text = "...\n你好 ni hao\n世界 shi jie\n"
        assertEquals(
            listOf(DictEntry("你好", "ni hao"), DictEntry("世界", "shi jie")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries ignores comments and blank lines`() {
        val text = "...\n\n# comment\n测试\tce shi\n"
        assertEquals(
            listOf(DictEntry("测试", "ce shi")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries returns empty for header-only file`() {
        val text = "name: user_simp\nversion: '1.0'\n...\n"
        assertTrue(PersonalDictManager.parsePersonalDictEntries(text).isEmpty())
    }

    @Test
    fun `parsePersonalDictEntries skips lines before marker`() {
        val text = "junk\nbefore\n...\nreal\tentry\n"
        assertEquals(
            listOf(DictEntry("real", "entry")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `round trip with tab-only codes`() {
        val original = listOf(
            DictEntry("粗鄙之语", "cu bi zhi yu")
        )
        val text = PersonalDictManager.buildDictText(defaultHeader, original)
        val parsed = PersonalDictManager.parsePersonalDictEntries(text)
        assertEquals(original, parsed)
    }

    @Test
    fun `round trip with unicode entries`() {
        val original = listOf(
            DictEntry("日本語", "ni hon go"),
            DictEntry("한국어", "han gu g eo"),
            DictEntry("😊", "biao qing")
        )
        val text = PersonalDictManager.buildDictText(defaultHeader, original)
        val parsed = PersonalDictManager.parsePersonalDictEntries(text)
        assertEquals(original, parsed)
    }

    @Test
    fun `buildDictText generates correct output`() {
        val entries = listOf(DictEntry("你好", "ni hao"))
        val result = PersonalDictManager.buildDictText(defaultHeader, entries)
        assertEquals("""# Rime dict
---
name: user_simp_pinyin
version: '1.0'
sort: original
use_preset_vocabulary: false
...
你好	ni hao
""", result)
    }

    @Test
    fun `buildDictText handles header without data entries`() {
        val result = PersonalDictManager.buildDictText(defaultHeader, listOf(DictEntry("a", "b")))
        assertTrue(result.contains("a\tb\n"))
    }

    @Test
    fun `multiple saves do not corrupt header`() {
        val file = File.createTempFile("personal_dict_multi", ".tmp")
        file.deleteOnExit()
        val entries1 = listOf(DictEntry("a", "b"))
        PersonalDictManager.buildDictText(defaultHeader, entries1).let { file.writeText(it, Charsets.UTF_8) }
        val h1 = PersonalDictManager.extractHeader(file, defaultHeader)
        val entries2 = listOf(DictEntry("a", "b"), DictEntry("c", "d"))
        PersonalDictManager.buildDictText(h1, entries2).let { file.writeText(it, Charsets.UTF_8) }
        val h2 = PersonalDictManager.extractHeader(file, defaultHeader)
        assertEquals(defaultHeader, h2)
        val parsed = PersonalDictManager.parsePersonalDictEntries(file.readText(Charsets.UTF_8))
        assertEquals(entries2, parsed)
    }

    @Test
    fun `output is valid RIME dict format`() {
        val entries = listOf(DictEntry("测试", "ce shi"))
        val result = PersonalDictManager.buildDictText(defaultHeader, entries)
        val lines = result.split('\n')
        assertTrue(lines.any { it.startsWith("name:") })
        val dataStart = lines.indexOfFirst { it == "..." }
        assertTrue(dataStart >= 0)
        val dataLines = lines.drop(dataStart + 1).filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(dataLines.any { it.contains('\t') })
    }

    // ── 自定义短语 ──

    private val stubHeader = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

    @Test
    fun `parseStableDbEntries reads entries skipping header`() {
        val text = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
测试	ce shi
词条	ci tiao
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(
            listOf(DictEntry("测试", "ce shi"), DictEntry("词条", "ci tiao")),
            result
        )
    }

    @Test
    fun `parseStableDbEntries reads weight field`() {
        val text = """#
a	b	99
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(listOf(DictEntry("a", "b", 99)), result)
    }

    @Test
    fun `parseStableDbEntries handles optional weight`() {
        val text = """#
a	b	99
c	d
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(listOf(DictEntry("a", "b", 99), DictEntry("c", "d")), result)
    }

    @Test
    fun `buildStableDbText preserves header and appends entries`() {
        val entries = listOf(
            DictEntry("联系一下", "lxyx"),
            DictEntry("等等", "dd")
        )
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.startsWith("# Rime table"))
        assertTrue(result.contains("联系一下\tlxyx\n"))
        assertTrue(result.contains("等等\tdd\n"))
    }

    @Test
    fun `buildStableDbText includes weight when present`() {
        val entries = listOf(DictEntry("a", "b", 99))
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.contains("a\tb\t99\n"))
    }

    @Test
    fun `buildStableDbText omits weight when null`() {
        val entries = listOf(DictEntry("a", "b"))
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.contains("a\tb\n"))
    }

    @Test
    fun `stabledb round trip preserves weight`() {
        val original = listOf(DictEntry("联系一下", "lxyx", 99))
        val text = PersonalDictManager.buildStableDbText(stubHeader, original)
        val parsed = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(original, parsed)
    }

    @Test
    fun `loadCustomPhrases when file missing returns empty`() {
        val context = mockContext()
        assertTrue(PersonalDictManager.loadCustomPhrases(context).isEmpty())
    }

    @Test
    fun `saveCustomPhrases writes to custom_phrase dot txt`() {
        val context = mockContext()
        val entries = listOf(DictEntry("kingzcheung@gmail.com", "yxdz", 99))
        PersonalDictManager.saveCustomPhrases(context, null, entries)
        val file = PersonalDictManager.getCustomPhraseFile(context)
        assertTrue(file.exists())
        val loaded = PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "kingzcheung@gmail.com" })
    }

    @Test
    fun `applyCustomPhraseTranslator adds translator to custom yaml`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        val customFile = File(rimeDir, "wubi86.custom.yaml")
        assertTrue(customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        assertTrue(text.contains("db_class: stabledb"))
    }

    @Test
    fun `applyCustomPhraseTranslator is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `applyCustomPhraseTranslator with custom dictName uses that dictName in config`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertTrue(text.contains("user_dict: custom_phrase_double"))
    }

    @Test
    fun `applyCustomPhraseTranslator with custom dictName is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `getCustomPhraseDictName reads user_dict from custom yaml`() {
        val rimeDir = createTempDir()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "custom_phrase":
    user_dict: custom_phrase_double
""")
        val rimeDirFinal = rimeDir
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDirFinal, "wubi86") }
        assertEquals("custom_phrase_double", result)
    }

    @Test
    fun `getCustomPhraseDictName returns default when no custom yaml`() {
        val rimeDir = createTempDir()
        val rimeDirFinal = rimeDir
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDirFinal, "wubi86") }
        assertEquals("custom_phrase", result)
    }

    @Test
    fun `getCustomPhraseDictName returns default when no custom_phrase section`() {
        val rimeDir = createTempDir()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "translator/packs": ["user_simp_pinyin"]
""")
        val rimeDirFinal = rimeDir
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDirFinal, "wubi86") }
        assertEquals("custom_phrase", result)
    }

    @Test
    fun `saveCustomPhrases with schemaId writes to the correct custom dict file`() {
        val context = mockContext()
        val rimeDir = File(context.filesDir, "rime")
        rimeDir.mkdirs()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "custom_phrase":
    user_dict: custom_phrase_double
""")
        val entries = listOf(DictEntry("test", "ce", 1))
        PersonalDictManager.saveCustomPhrases(context, "wubi86", entries)
        val file = File(rimeDir, "custom_phrase_double.txt")
        assertTrue(file.exists())
        val loaded = PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "test" })
    }

    @Test
    fun `saveCustomPhrases without schemaId writes to default custom_phrase dot txt`() {
        val context = mockContext()
        val entries = listOf(DictEntry("hello", "hw"))
        PersonalDictManager.saveCustomPhrases(context, null, entries)
        val file = PersonalDictManager.getCustomPhraseFile(context)
        assertEquals("custom_phrase.txt", file.name)
        assertTrue(file.exists())
    }

    // ── 方案补丁 ──

    @Test
    fun `hasSpellerAlgebra returns true when algebra present`() {
        val schema = """speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
"""
        val file = createTempFile(schema)
        assertTrue(PersonalDictManager.hasSpellerAlgebra(file))
    }

    @Test
    fun `hasSpellerAlgebra returns false without algebra`() {
        val schema = """speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  max_code_length: 4
"""
        val file = createTempFile(schema)
        assertFalse(PersonalDictManager.hasSpellerAlgebra(file))
    }

    // ── ensureSchemaPack 按需创建 ──

    @Test
    fun `ensureSchemaPack creates custom yaml for schema with algebra`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        createCustomPhraseFile(rimeDir, "pinyin_simp")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        val customFile = java.io.File(rimeDir, "pinyin_simp.custom.yaml")
        assertTrue("custom.yaml should be created for schema with algebra", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("translator/packs"))
        assertTrue("custom_phrase should also be added", text.contains("table_translator@custom_phrase"))
    }

    @Test
    fun `ensureSchemaPack creates merged dict for schema without algebra`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "wubi86.schema.yaml").writeText("""
speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  max_code_length: 4
""".trimIndent())
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "wubi86") }
        assertTrue("merged dict should be created", java.io.File(rimeDir, "wubi86_merged.dict.yaml").exists())
        val customFile = java.io.File(rimeDir, "wubi86.custom.yaml")
        assertTrue("custom.yaml should be created", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("translator/dictionary"))
    }

    @Test
    fun `ensureSchemaPack skips reverse_lookup schemas`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "wubi86_pinyin.schema.yaml").writeText("""
translator:
  dictionary: wubi86_pinyin
  reverse_lookup_translator: true
""".trimIndent())
        createCustomPhraseFile(rimeDir, "wubi86_pinyin")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "wubi86_pinyin") }
        val customFile = java.io.File(rimeDir, "wubi86_pinyin.custom.yaml")
        assertTrue("custom.yaml should be created (custom_phrase only)", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        // reverse_lookup schemas should NOT get packs or merged dict
        assertFalse("should not have packs", text.contains("translator/packs"))
        assertFalse("should not have merged dict", text.contains("translator/dictionary"))
    }

    @Test
    fun `ensureSchemaPack is no-op when schema file missing`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        // no schema file created
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "nonexistent") }
        val customFile = java.io.File(rimeDir, "nonexistent.custom.yaml")
        assertFalse("no custom.yaml should be created for missing schema", customFile.exists())
    }

    @Test
    fun `ensureSchemaPack is idempotent`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        createCustomPhraseFile(rimeDir, "pinyin_simp")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        val text = java.io.File(rimeDir, "pinyin_simp.custom.yaml").readText(Charsets.UTF_8)
        // packs line should appear only once
        assertEquals(1, text.split("user_pinyin_simp").size - 1)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `applyPackConfig creates custom yaml with packs for schema WITH algebra`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyPackConfig(rimeDir, "pinyin_simp")
        val customFile = File(rimeDir, "pinyin_simp.custom.yaml")
        assertTrue(customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("translator/packs"))
        assertTrue(text.contains("user_pinyin_simp"))
    }

    @Test
    fun `applyPackConfig is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyPackConfig(rimeDir, "pinyin_simp")
        PersonalDictManager.applyPackConfig(rimeDir, "pinyin_simp")
        val customFile = File(rimeDir, "pinyin_simp.custom.yaml")
        val text = customFile.readText(Charsets.UTF_8)
        assertEquals(1, text.split("user_pinyin_simp").size - 1)
    }

    @Test
    fun `applyMergedDictConfig creates merged dict and custom for schema WITHOUT algebra`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyMergedDictConfig(rimeDir, "wubi86")
        val dictFile = File(rimeDir, "wubi86_merged.dict.yaml")
        assertTrue(dictFile.exists())
        val dictText = dictFile.readText(Charsets.UTF_8)
        assertTrue(dictText.contains("import_tables:"))
        assertTrue(dictText.contains("- wubi86"))
        assertTrue(dictText.contains("- user_wubi86"))
        val customFile = File(rimeDir, "wubi86.custom.yaml")
        assertTrue(customFile.exists())
        val customText = customFile.readText(Charsets.UTF_8)
        assertTrue(customText.contains("translator/dictionary"))
        assertTrue(customText.contains("wubi86_merged"))
    }

    @Test
    fun `applyMergedDictConfig is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyMergedDictConfig(rimeDir, "wubi86")
        PersonalDictManager.applyMergedDictConfig(rimeDir, "wubi86")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("wubi86_merged").size - 1)
    }

    // ── insertUnderPatch 合入逻辑 ──

    @Test
    fun `insertUnderPatch creates patch when file missing`() {
        val dir = createTempDir()
        // applyPackConfig creates <schemaId>.custom.yaml
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        val file = File(dir, "pinyin_simp.custom.yaml")
        assertTrue("file should exist", file.exists())
        val text = file.readText(Charsets.UTF_8)
        assertTrue("should start with patch:", text.startsWith("patch:"))
        assertTrue(text.contains("translator/packs"))
    }

    @Test
    fun `insertUnderPatch merges into existing patch block`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""# comment
patch:
  engine/translators/+:
    - lua_translator@my_script
  menu/page_size: 6
""", Charsets.UTF_8)
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("existing lua translator preserved", text.contains("lua_translator@my_script"))
        assertTrue("new pack config added", text.contains("translator/packs"))
        assertTrue("existing menu/page_size preserved", text.contains("menu/page_size: 6"))
        assertTrue("pack name present", text.contains("user_pinyin_simp"))
    }

    @Test
    fun `insertUnderPatch creates patch when file has no patch section`() {
        val dir = createTempDir()
        // applyCustomPhraseTranslator creates <schemaId>.custom.yaml
        val file = File(dir, "wubi86.custom.yaml")
        file.writeText("# just a comment\n", Charsets.UTF_8)
        PersonalDictManager.applyCustomPhraseTranslator(dir, "wubi86", "custom_phrase")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("patch: should be created", text.contains("patch:"))
        assertTrue("content should be added under patch", text.contains("table_translator@custom_phrase"))
    }

    @Test
    fun `insertUnderPatch handles document end marker`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""patch:
  existing_key: value
...
""", Charsets.UTF_8)
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        val text = file.readText(Charsets.UTF_8)
        assertFalse("doc end marker should be stripped", text.contains("..."))
        assertTrue("existing key preserved", text.contains("existing_key: value"))
        assertTrue("new entry added", text.contains("translator/packs"))
    }

    @Test
    fun `insertUnderPatch preserves existing entries when merging`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@custom_filter1
    - lua_filter@custom_filter2
  menu/page_size: 8
""", Charsets.UTF_8)
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("engine/filters/@before/0:"))
        assertTrue(text.contains("lua_filter@custom_filter1"))
        assertTrue(text.contains("lua_filter@custom_filter2"))
        assertTrue(text.contains("menu/page_size: 8"))
        assertTrue(text.contains("translator/packs"))
    }

    @Test
    fun `applyPackConfig preserves existing lua patches in custom yaml`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@custom_filter
  menu/page_size: 8
""", Charsets.UTF_8)
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("Lua filter preserved", text.contains("lua_filter@custom_filter"))
        assertTrue("page_size preserved", text.contains("menu/page_size: 8"))
        assertTrue("packs config merged", text.contains("translator/packs"))
    }

    @Test
    fun `applyMergedDictConfig preserves existing content in custom yaml`() {
        val dir = createTempDir()
        val file = File(dir, "wubi86.custom.yaml")
        file.writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@my_filter
""", Charsets.UTF_8)
        PersonalDictManager.applyMergedDictConfig(dir, "wubi86")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("existing lua filter preserved", text.contains("lua_filter@my_filter"))
        assertTrue("merged dict config added", text.contains("translator/dictionary"))
        assertTrue("merged dict name present", text.contains("wubi86_merged"))
    }

    @Test
    fun `applyCustomPhraseTranslator preserves existing patches`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@custom_filter
  menu/page_size: 8
""", Charsets.UTF_8)
        PersonalDictManager.applyCustomPhraseTranslator(dir, "pinyin_simp", "custom_phrase")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("Lua filter preserved", text.contains("lua_filter@custom_filter"))
        assertTrue("page_size preserved", text.contains("menu/page_size: 8"))
        assertTrue("custom_phrase added", text.contains("table_translator@custom_phrase"))
        assertTrue("custom_phrase config complete", text.contains("db_class: stabledb"))
    }

    @Test
    fun `ensureSchemaPack preserves existing custom yaml with lua patches`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        // 模拟第三方方案：有 schema.yaml + 预置 custom.yaml（含 Lua 翻译器）
        java.io.File(rimeDir, "flypy.schema.yaml").writeText("""
speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  algebra:
    - erase/^xx$/
""".trimIndent())
        java.io.File(rimeDir, "flypy.custom.yaml").writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@my_filter
  menu/page_size: 8
""", Charsets.UTF_8)
        createCustomPhraseFile(rimeDir, "flypy")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "flypy") }
        val text = java.io.File(rimeDir, "flypy.custom.yaml").readText(Charsets.UTF_8)
        // Lua 配置保留
        assertTrue(text.contains("lua_filter@my_filter"))
        assertTrue(text.contains("menu/page_size: 8"))
        // Xime 配置合入
        assertTrue(text.contains("translator/packs"))
        assertTrue(text.contains("table_translator@custom_phrase"))
        // 确保幂等
        assertEquals("custom_phrase appears once", 1, text.split("table_translator@custom_phrase").size - 1)
        assertEquals("packs appears once", 1, text.split("user_flypy").size - 1)
    }

    @Test
    fun `multiple apply calls produce valid yaml with all entries once`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""patch:
  existing_key: value
""", Charsets.UTF_8)
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        PersonalDictManager.applyCustomPhraseTranslator(dir, "pinyin_simp", "custom_phrase")
        PersonalDictManager.applyCustomPhraseTranslator(dir, "pinyin_simp", "custom_phrase")
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("existing key preserved", text.contains("existing_key: value"))
        assertTrue("packs added", text.contains("translator/packs"))
        assertTrue("custom_phrase added", text.contains("table_translator@custom_phrase"))
        assertTrue("existing appears once", text.indexOf("existing_key: value") == text.lastIndexOf("existing_key: value"))
        assertTrue("packs appears once", text.indexOf("translator/packs") == text.lastIndexOf("translator/packs"))
        assertTrue("custom_phrase appears once", text.indexOf("table_translator@custom_phrase") == text.lastIndexOf("table_translator@custom_phrase"))
    }

    @Test
    fun `ensureSchemaPack merged dict path preserves existing content`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        // 模拟无 speller/algebra 的第三方方案（如五笔）的预置 custom.yaml
        java.io.File(rimeDir, "wubi86.schema.yaml").writeText("""
speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  max_code_length: 4
""".trimIndent())
        java.io.File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@custom_filter
  menu/page_size: 8
""", Charsets.UTF_8)
        createCustomPhraseFile(rimeDir, "wubi86")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "wubi86") }
        val text = java.io.File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertTrue("Lua filter preserved", text.contains("lua_filter@custom_filter"))
        assertTrue("page_size preserved", text.contains("menu/page_size: 8"))
        assertTrue("merged dict config added", text.contains("translator/dictionary"))
        assertTrue("custom_phrase added", text.contains("table_translator@custom_phrase"))
        assertEquals("merged dict appears once", 1, text.split("wubi86_merged").size - 1)
        assertEquals("custom_phrase appears once", 1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `ensureSchemaPack reverse lookup path preserves existing content`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "wubi86_pinyin.schema.yaml").writeText("""
translator:
  dictionary: wubi86_pinyin
  reverse_lookup_translator: true
""".trimIndent())
        java.io.File(rimeDir, "wubi86_pinyin.custom.yaml").writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@custom_filter
  menu/page_size: 8
""", Charsets.UTF_8)
        createCustomPhraseFile(rimeDir, "wubi86_pinyin")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "wubi86_pinyin") }
        val text = java.io.File(rimeDir, "wubi86_pinyin.custom.yaml").readText(Charsets.UTF_8)
        assertTrue("Lua filter preserved", text.contains("lua_filter@custom_filter"))
        assertTrue("page_size preserved", text.contains("menu/page_size: 8"))
        assertTrue("custom_phrase added", text.contains("table_translator@custom_phrase"))
        assertFalse("no packs for reverse_lookup", text.contains("translator/packs"))
        assertFalse("no merged dict for reverse_lookup", text.contains("translator/dictionary"))
    }

    @Test
    fun `applyPackConfig creates file when custom yaml is empty`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.createNewFile()
        file.writeText("")
        PersonalDictManager.applyPackConfig(dir, "pinyin_simp")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("patch created even for empty file", text.contains("patch:"))
        assertTrue("packs added", text.contains("translator/packs"))
    }

    private fun createTempFile(content: String): File {
        val file = File.createTempFile("personal_dict_test", ".tmp")
        file.writeText(content, Charsets.UTF_8)
        file.deleteOnExit()
        return file
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("personal_dict_test_dir", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
