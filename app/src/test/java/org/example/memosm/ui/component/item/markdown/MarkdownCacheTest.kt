package org.example.memosm.ui.component.item.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownCacheTest {
    @Test fun `the same source reuses its tree and reference definitions`() {
        val content = "[Example][REF]\n\n[REF]: https://example.com"
        val parsed = MarkdownCache.parse(content)
        assertSame(parsed, MarkdownCache.parse(content))
        assertSame(parsed, MarkdownCache.cached(content))
        assertEquals("https://example.com", parsed.references["[ref]"])
    }

    @Test fun `edited content gets a matching tree and updated references`() {
        val original = "[Example][REF]\n\n[REF]: https://example.com/old"
        val edited = original.replace("/old", "/new-longer")
        val first = MarkdownCache.parse(original)
        val second = MarkdownCache.parse(edited)
        assertNotSame(first, second)
        assertEquals(edited.length, second.tree.endOffset)
        assertEquals("https://example.com/new-longer", second.references["[ref]"])
        assertEquals("https://example.com/old", first.references["[ref]"])
    }

    @Test fun `checkbox edits do not reuse the old tree`() {
        val unchecked = MarkdownCache.parse("- [ ] A task")
        val checked = MarkdownCache.parse("- [x] A task")
        assertNotSame(unchecked.tree, checked.tree)
        assertSame(unchecked, MarkdownCache.parse("- [ ] A task"))
    }

    @Test fun `syntax aliases share highlights but theme changes do not`() {
        val code = "val cachedExample = 42"
        val light = CodeHighlighter.highlightCode(code, "kotlin", false)
        assertSame(light, CodeHighlighter.highlightCode(code, " kt ", false))
        assertNotSame(light, CodeHighlighter.highlightCode(code, "kotlin", true))
        assertEquals(code, light.text)
    }

    @Test fun `code edits do not reuse previous highlighted text`() {
        val before = CodeHighlighter.highlightCode("val before = 1", "kotlin")
        val after = CodeHighlighter.highlightCode("val after = 2", "kotlin")
        assertNotSame(before, after)
        assertEquals("val before = 1", before.text)
        assertEquals("val after = 2", after.text)
    }
}
