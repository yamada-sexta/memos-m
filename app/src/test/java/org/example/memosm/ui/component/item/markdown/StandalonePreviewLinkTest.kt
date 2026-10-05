package org.example.memosm.ui.component.item.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class StandalonePreviewLinkTest {
    private fun previews(content: String): List<String> {
        val tree = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable).buildMarkdownTreeFromString(content as CharSequence)
        val references = tree.children.filter { it.type == MarkdownElementTypes.LINK_DEFINITION }
            .associate {
                it.findChildOfType(MarkdownElementTypes.LINK_LABEL)!!.getTextInNode(content).toString().lowercase() to
                    it.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)!!.getTextInNode(content).toString()
            }
        return tree.children.mapNotNull { standalonePreviewUrl(it, content, references) }
    }

    @Test
    fun `bare autolink and URL-labelled markdown links qualify`() {
        listOf(
            "https://example.com/page?q=a&b=c#section",
            " <https://example.com/page?q=a&b=c#section> ",
            "[https://example.com/page?q=a&b=c#section](https://example.com/page?q=a&b=c#section)",
            "[https://example.com/page?q=a&b=c#section](<https://example.com/page?q=a&b=c#section>)"
        ).forEach { assertEquals(it, listOf("https://example.com/page?q=a&b=c#section"), previews(it)) }
    }

    @Test
    fun `reference links qualify only when their label is the URL`() {
        listOf(
            "[https://example.com][ref]\n\n[ref]: https://example.com",
            "[https://example.com][]\n\n[https://example.com]: https://example.com",
            "[https://example.com]\n\n[https://example.com]: https://example.com"
        ).forEach { assertEquals(it, listOf("https://example.com"), previews(it)) }
        assertEquals(emptyList<String>(), previews("[Read more][ref]\n\n[ref]: https://example.com"))
    }

    @Test
    fun `ordinary markdown and non-web links stay unchanged`() {
        listOf(
            "Read https://example.com",
            "https://example.com https://other.example.com",
            "[Read more](https://example.com)",
            "[**https://example.com**](https://example.com)",
            "- https://example.com",
            "> https://example.com",
            "# https://example.com",
            "`https://example.com`",
            "```\nhttps://example.com\n```",
            "![https://example.com](https://example.com/image.png)",
            "<mailto:user@example.com>",
            "[tel:123](tel:123)",
            "[relative](/path)",
            "| Link |\n| --- |\n| https://example.com |"
        ).forEach { assertEquals(it, emptyList<String>(), previews(it)) }
    }
}
