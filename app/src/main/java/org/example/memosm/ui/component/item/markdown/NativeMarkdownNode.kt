package org.example.memosm.ui.component.item.markdown

import android.content.Context
import android.view.View.MeasureSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.agog.mathdisplay.MTMathView
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import java.util.UUID

// Helper data class for styles
data class MarkdownStyles(
    val codeBackground: Color,
    val linkColor: Color,
    val strikethroughStyle: SpanStyle,
    val boldStyle: SpanStyle,
    val italicStyle: SpanStyle,
    val codeFontFamily: FontFamily
)

@Composable
fun MarkdownText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    inlineContent: Map<String, InlineTextContent> = emptyMap()
) {
    LocalUriHandler.current
    val defaultColor = LocalContentColor.current
    val textColor = if (style.color.isSpecified) style.color else defaultColor

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val drawModifier = modifier.drawBehind {
        layoutResult?.let { layout ->
            text.getStringAnnotations("ROUNDED_BG_COLOR", 0, text.length).forEach { range ->
                try {
                    val color = Color(range.item.toLong(16))
                    val path = layout.getPathForRange(range.start, range.end)
                    val bounds = path.getBounds()
                    // Draw slightly inflated rounded rect for better visuals
                    drawRoundRect(
                        color = color,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
            }
        }
    }

    Text(
        text = text,
        style = style.copy(color = textColor),
        modifier = drawModifier,
        onTextLayout = { layoutResult = it },
        textAlign = textAlign,
        inlineContent = inlineContent
    )
}

// Local provider for content and callbacks to avoid passing them deep
val LocalMarkdownContent = compositionLocalOf { "" }
val LocalOnContentChange = compositionLocalOf<((String) -> Unit)?> { null }
val LocalForceNoTopPadding = compositionLocalOf { false }
val LocalOnHashtagClick = compositionLocalOf<((String) -> Unit)?> { null }
val LocalHeaderScale = compositionLocalOf { 1.0f }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeMarkdownNode(
    modifier: Modifier = Modifier,
    node: ASTNode,
    content: String,
    headerScale: Float,
    onContentChange: ((String) -> Unit)? = null,
    onHashtagClick: ((String) -> Unit)? = null
) {
    CompositionLocalProvider(
        LocalMarkdownContent provides content,
        LocalOnContentChange provides onContentChange,
        LocalOnHashtagClick provides onHashtagClick,
        LocalHeaderScale provides headerScale
    ) {
        Column(modifier = modifier) {
            NativeMarkdownNodeRecursive(node)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeMarkdownNodeRecursive(node: ASTNode, allowPreview: Boolean = false) {
    val previews = LocalLinkPreviews.current
    val previewContent = LocalMarkdownContent.current
    val previewReferences = LocalMarkdownReferences.current
    val previewUrl = if (previews != null && allowPreview) {
        remember(node, previewContent, previewReferences) {
            standalonePreviewUrl(node, previewContent, previewReferences)
        }
    } else null
    if (previewUrl != null && previews != null) {
        NativeLinkPreview(previewUrl, previews.repository, previews.isOnline)
        return
    }
    val content = LocalMarkdownContent.current
    val onContentChange = LocalOnContentChange.current
    val onHashtagClick = LocalOnHashtagClick.current
    val headerScale = LocalHeaderScale.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { typography.bodyLarge.fontSize.toPx() }

    val styles = MarkdownStyles(
        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        linkColor = MaterialTheme.colorScheme.primary,
        strikethroughStyle = SpanStyle(textDecoration = TextDecoration.LineThrough),
        boldStyle = SpanStyle(fontWeight = FontWeight.Bold),
        italicStyle = SpanStyle(fontStyle = FontStyle.Italic),
        codeFontFamily = FontFamily.Monospace
    )

    when (node.type) {
        MarkdownElementTypes.MARKDOWN_FILE -> {
            // Render all children
            node.children.forEach { child ->
                NativeMarkdownNodeRecursive(child, allowPreview = true)
            }
        }

        MarkdownElementTypes.PARAGRAPH -> {
            val noTopPadding = LocalForceNoTopPadding.current
            val topPadding = if (noTopPadding) 0.dp else 4.dp

            Column(modifier = Modifier.padding(top = topPadding, bottom = 4.dp)) {
                val children = node.children
                var lastIndex = 0

                children.forEachIndexed { index, child ->
                    if (child.type == GFMElementTypes.BLOCK_MATH || child.type == MarkdownElementTypes.IMAGE) {
                        // Render previous text chunk
                        if (index > lastIndex) {
                            val textNodes = children.subList(lastIndex, index)

                            val (styledText, inlineContentMap) = remember(textNodes, content) {
                                val map = mutableMapOf<String, InlineTextContent>()
                                val text = buildAnnotatedString {
                                    textNodes.forEach { textNode ->
                                        visitInlineChild(
                                            textNode,
                                            content,
                                            styles,
                                            context,
                                            density,
                                            fontSizePx,
                                            this,
                                            map,
                                            onHashtagClick
                                        )
                                    }
                                }
                                text to map
                            }

                            if (styledText.isNotEmpty()) {
                                MarkdownText(
                                    text = styledText,
                                    style = typography.bodyLarge,
                                    inlineContent = inlineContentMap
                                )
                            }
                        }

                        if (child.type == GFMElementTypes.BLOCK_MATH) {
                            // Render Block Math
                            val rawText =
                                child.children.joinToString("") { it.getTextInNode(content) }
                            val latex = rawText.trim().removePrefix("$$").removeSuffix("$$").trim()

                            NativeMarkdownLatex(
                                latex = latex,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                inline = false
                            )
                        } else if (child.type == MarkdownElementTypes.IMAGE) {
                            // Render Image
                            NativeMarkdownAttachmentImage(content, child)
                        }

                        lastIndex = index + 1
                    }
                }

                // Render remaining text
                if (lastIndex < children.size) {
                    val textNodes = children.subList(lastIndex, children.size)

                    val (styledText, inlineContentMap) = remember(textNodes, content) {
                        val map = mutableMapOf<String, InlineTextContent>()
                        val text = buildAnnotatedString {
                            textNodes.forEach { textNode ->
                                visitInlineChild(
                                    textNode,
                                    content,
                                    styles,
                                    context,
                                    density,
                                    fontSizePx,
                                    this,
                                    map,
                                    onHashtagClick
                                )
                            }
                        }
                        text to map
                    }

                    if (styledText.isNotEmpty()) {
                        MarkdownText(
                            text = styledText,
                            style = typography.bodyLarge,
                            inlineContent = inlineContentMap
                        )
                    }
                }
            }
        }

        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val baseStyle = when (node.type) {
                MarkdownElementTypes.ATX_1 -> typography.displayLarge    // H1 (Largest)
                MarkdownElementTypes.ATX_2 -> typography.displayMedium   // H2
                MarkdownElementTypes.ATX_3 -> typography.headlineLarge   // H3
                MarkdownElementTypes.ATX_4 -> typography.headlineMedium  // H4
                MarkdownElementTypes.ATX_5 -> typography.titleLarge      // H5
                MarkdownElementTypes.ATX_6 -> typography.titleMedium     // H6 (Smallest)
                else -> typography.bodyLarge                             // Default fallback
            }
            val bodySize = typography.bodyLarge.fontSize
            val scaledSize = baseStyle.fontSize * headerScale
            // Ensure header is never smaller than the body text
            val finalSize = if (scaledSize >= bodySize) scaledSize else bodySize
            val style = baseStyle.copy(fontSize = finalSize)
            val headerFontSizePx = with(density) { finalSize.toPx() }

            // Headers contain inline elements usually, or just leaf text
            // Retrieve the text content excluding the # characters
            // BUT intellij-markdown structure for headers usually has: 
            // ATX_1 -> [ATX_CONTENT -> [TEXT]]
            // We can just recursively render inline content.
            // But headers are block elements, so we treat them as text with style.
            val (styledText, _) = remember(node, content, finalSize) {
                val map = mutableMapOf<String, InlineTextContent>()
                val text = buildAnnotatedString {
                    // Header content usually doesn't have complex math, but we support it best effort.
                    node.children.forEach { child ->
                        if (child.type != MarkdownTokenTypes.ATX_HEADER) {
                            visitInlineChild(
                                child,
                                content,
                                styles,
                                context,
                                density,
                                headerFontSizePx,
                                this,
                                map,
                                onHashtagClick
                            )
                        }
                    }
                }
                text to map
            }
            val noTopPadding = LocalForceNoTopPadding.current
            val topPadding = if (noTopPadding) 0.dp else 8.dp

            MarkdownText(
                text = styledText,
                style = style,
                modifier = Modifier.padding(top = topPadding, bottom = 8.dp)
            )
        }

        MarkdownElementTypes.UNORDERED_LIST -> {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                node.children.forEach { child ->
                    if (child.type == MarkdownElementTypes.LIST_ITEM) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Check for Checkbox
                            val checkBoxNode = child.findChildOfType(GFMTokenTypes.CHECK_BOX)
                            if (checkBoxNode != null) {
                                val isChecked = checkBoxNode.getTextInNode(content)
                                    .contains("x", ignoreCase = true)
                                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            onContentChange?.invoke(
                                                toggleCheckbox(
                                                    content,
                                                    checkBoxNode.startOffset,
                                                    checkBoxNode.endOffset,
                                                    checked
                                                )
                                            )
                                        },
                                        modifier = Modifier
                                            .scale(0.8f)
                                            .offset(x = (-4).dp, y = (-2).dp)
                                            .padding(end = 4.dp)
                                    )
                                }
                            } else {
                                Text(
                                    "•",
                                    style = typography.bodyLarge,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }

                            Column {
                                var firstChildProcessed = false
                                child.children.forEach { listChild ->
                                    if (listChild.type != GFMTokenTypes.CHECK_BOX && listChild.type != MarkdownTokenTypes.LIST_BULLET && listChild.type != MarkdownTokenTypes.EOL) {
                                        if (!firstChildProcessed) {
                                            CompositionLocalProvider(LocalForceNoTopPadding provides true) {
                                                NativeMarkdownNodeRecursive(listChild)
                                            }
                                            firstChildProcessed = true
                                        } else {
                                            NativeMarkdownNodeRecursive(listChild)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        NativeMarkdownNodeRecursive(child)
                    }
                }
            }
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            var index = 1
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                node.children.forEach { child ->
                    if (child.type == MarkdownElementTypes.LIST_ITEM) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "$index.",
                                style = typography.bodyLarge,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column {
                                var firstChildProcessed = false
                                child.children.forEach { listChild ->
                                    if (listChild.type != MarkdownTokenTypes.LIST_NUMBER && listChild.type != MarkdownTokenTypes.EOL) {
                                        if (!firstChildProcessed) {
                                            CompositionLocalProvider(LocalForceNoTopPadding provides true) {
                                                NativeMarkdownNodeRecursive(listChild)
                                            }
                                            firstChildProcessed = true
                                        } else {
                                            NativeMarkdownNodeRecursive(listChild)
                                        }
                                    }
                                }
                            }
                            index++
                        }
                    } else {
                        NativeMarkdownNodeRecursive(child)
                    }
                }
            }
        }

        MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_FENCE -> {
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                var lang = ""
                val sb = StringBuilder()
                node.children.forEach { child ->
                    when (child.type) {
                        MarkdownTokenTypes.FENCE_LANG -> {
                            lang = child.getTextInNode(content).toString().trim()
                        }

                        MarkdownTokenTypes.CODE_FENCE_CONTENT, MarkdownTokenTypes.CODE_LINE, MarkdownTokenTypes.EOL -> {
                            sb.append(child.getTextInNode(content))
                        }
                        // Ignore fence delimiters (START/END) and other metadata
                    }
                }

                // Remove leading/trailing newlines to avoid extra padding, but preserve indentation
                val code = sb.toString().removePrefix("\n").removeSuffix("\n")
                val isDarkTheme = isSystemInDarkTheme()
                val highlightedText = CodeHighlighter.highlightCode(code, lang, isDarkTheme)

                Text(
                    text = highlightedText,
                    style = typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        MarkdownElementTypes.BLOCK_QUOTE -> {
            val borderColor = MaterialTheme.colorScheme.outlineVariant
            // Draw the border at the measured height instead of asking for the
            // full intrinsic height, which can exceed Compose's packed limits.
            Column(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .drawBehind {
                        val borderWidth = 4.dp.toPx()
                        val radius = 2.dp.toPx()
                        drawRoundRect(
                            color = borderColor,
                            topLeft = Offset(
                                if (layoutDirection == LayoutDirection.Ltr) 0f
                                else size.width - borderWidth,
                                0f
                            ),
                            size = Size(borderWidth, size.height),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                    .padding(start = 12.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    node.children.forEach { child ->
                        if (child.type != MarkdownTokenTypes.BLOCK_QUOTE) {
                            NativeMarkdownNodeRecursive(child)
                        }
                    }
                }
            }
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        MarkdownElementTypes.LINK_DEFINITION -> {
            // Do not render link definitions
        }

        GFMElementTypes.TABLE -> {
            NativeMarkdownTable(content, node)
        }

        GFMElementTypes.BLOCK_MATH -> {
            // $$ ... $$
            val rawText = node.children.joinToString("") { it.getTextInNode(content) }
            val latex = rawText.trim().removePrefix("$$").removeSuffix("$$").trim()

            NativeMarkdownLatex(
                latex = latex,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                inline = false
            )
        }

        MarkdownElementTypes.IMAGE -> {
            // Image handling via AttachmentCard
            // Children: [ "![", LINK_TEXT, "](", LINK_DESTINATION, ")"]
            // or helper to find link
            // We can implement a specific NativeMarkdownAttachment component
            NativeMarkdownAttachmentImage(content, node)
        }

        // Handle other block types or fallthrough
        else -> {
            // If it's a composite node, visit children.
            // If it matches known inline types but we are in block context?
            // Usually PARAGRAPH wraps inline content.
            // If we encounter raw text or unknown nodes at block level:
            if (node.children.isNotEmpty()) {
                node.children.forEach { NativeMarkdownNodeRecursive(it) }
            }
        }
    }
}

// Inline content builder, NOT Composable
fun AnnotatedString.Builder.appendInlineChildren(
    node: ASTNode,
    content: String,
    styles: MarkdownStyles,
    context: Context,
    density: Density,
    fontSizePx: Float,
    inlineContent: MutableMap<String, InlineTextContent>,
    onHashtagClick: ((String) -> Unit)?
) {
    if (node.children.isEmpty()) {
        val text = node.getTextInNode(content).toString()
        // Check for hashtags
        val hashtagRegex = Regex("#[^\\s#]+")
        var lastIndex = 0
        hashtagRegex.findAll(text).forEach { match ->
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }
            val tag = match.value
            pushLink(LinkAnnotation.Clickable(tag) { onHashtagClick?.invoke(tag) })
            withStyle(SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.None)) {
                append(tag)
            }
            pop()
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
        return
    }

    node.children.forEach { child ->
        visitInlineChild(
            child,
            content,
            styles,
            context,
            density,
            fontSizePx,
            this,
            inlineContent,
            onHashtagClick
        )
    }
}

fun visitInlineChild(
    child: ASTNode,
    content: String,
    styles: MarkdownStyles,
    context: Context,
    density: Density,
    fontSizePx: Float,
    builder: AnnotatedString.Builder,
    inlineContent: MutableMap<String, InlineTextContent>,
    onHashtagClick: ((String) -> Unit)?
) {
    with(builder) {
        when (child.type) {
            MarkdownElementTypes.STRONG -> {
                withStyle(styles.boldStyle) {
                    child.children.forEach { c ->
                        if (c.type != MarkdownTokenTypes.EMPH) {
                            visitInlineChild(
                                c,
                                content,
                                styles,
                                context,
                                density,
                                fontSizePx,
                                this,
                                inlineContent,
                                onHashtagClick
                            )
                        }
                    }
                }
            }

            MarkdownElementTypes.EMPH -> {
                withStyle(styles.italicStyle) {
                    child.children.forEach { c ->
                        if (c.type != MarkdownTokenTypes.EMPH) {
                            visitInlineChild(
                                c,
                                content,
                                styles,
                                context,
                                density,
                                fontSizePx,
                                this,
                                inlineContent,
                                onHashtagClick
                            )
                        }
                    }
                }
            }

            MarkdownElementTypes.AUTOLINK -> {
                val text = child.getTextInNode(content).toString()
                val destination = text.removePrefix("<").removeSuffix(">")

                pushLink(LinkAnnotation.Url(destination))
                withStyle(SpanStyle(color = styles.linkColor)) {
                    append(destination)
                }
                pop()
            }

            GFMTokenTypes.GFM_AUTOLINK -> {
                val text = child.getTextInNode(content).toString()
                pushLink(LinkAnnotation.Url(text))
                withStyle(SpanStyle(color = styles.linkColor)) {
                    append(text)
                }
                pop()
            }

            GFMElementTypes.INLINE_MATH -> {
                val rawText = child.getTextInNode(content).toString()
                val latex = rawText.removePrefix("$").removeSuffix("$").trim()
                val id = "inline_math_${UUID.randomUUID()}"

                // Dynamic Measurement
                val (widthEm, heightEm) = try {
                    val mathView = MTMathView(context, null)
                    mathView.fontSize = fontSizePx
                    mathView.latex = latex
                    // Measure with unspecified specs to get desired size
                    mathView.measure(
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    )
                    val w = mathView.measuredWidth
                    val h = mathView.measuredHeight

                    // Convert px to em
                    val wEm = (w / fontSizePx).toDouble()
                    val hEm = (h / fontSizePx).toDouble()

                    wEm to hEm
                } catch (e: Exception) {
                    // Fallback if measurement fails
                    val rawWidth = (latex.length * 0.6)
                    val fallbackWidth = if (rawWidth < 1.0) 1.0 else rawWidth
                    fallbackWidth to 1.5
                }

                inlineContent[id] = InlineTextContent(
                    Placeholder(
                        width = widthEm.em,
                        height = heightEm.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    NativeMarkdownLatex(
                        latex = latex,
                        inline = true,
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                appendInlineContent(id, "($latex)")
            }

            MarkdownElementTypes.CODE_SPAN -> {
                val hexColor = styles.codeBackground.toHex()
                pushStringAnnotation(tag = "ROUNDED_BG_COLOR", annotation = hexColor)
                withStyle(
                    SpanStyle(
                        fontFamily = styles.codeFontFamily
                    )
                ) {
                    child.children.forEach { grandChild ->
                        if (grandChild.type != MarkdownTokenTypes.BACKTICK) {
                            append(grandChild.getTextInNode(content).toString())
                        }
                    }
                }
                pop()
            }

            MarkdownElementTypes.LINK_DEFINITION, MarkdownElementTypes.INLINE_LINK -> {
                val linkText =
                    child.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                        ?.toString() ?: "Link"
                val linkDest = child.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    ?.getTextInNode(content)?.toString() ?: ""

                pushLink(LinkAnnotation.Url(linkDest))
                withStyle(SpanStyle(color = styles.linkColor)) {
                    val linkTextNode = child.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                    if (linkTextNode != null) {
                        linkTextNode.children.forEach { lc ->
                            if (lc.type != MarkdownTokenTypes.LBRACKET && lc.type != MarkdownTokenTypes.RBRACKET) {
                                visitInlineChild(
                                    lc,
                                    content,
                                    styles,
                                    context,
                                    density,
                                    fontSizePx,
                                    this,
                                    inlineContent,
                                    onHashtagClick
                                )
                            }
                        }
                    } else {
                        append(linkText)
                    }
                }
                pop()
            }

            GFMElementTypes.STRIKETHROUGH -> {
                withStyle(styles.strikethroughStyle) {
                    child.children.forEach { c ->
                        if (c.type != GFMTokenTypes.TILDE) {
                            visitInlineChild(
                                c,
                                content,
                                styles,
                                context,
                                density,
                                fontSizePx,
                                this,
                                inlineContent,
                                onHashtagClick
                            )
                        }
                    }
                }
            }

            else -> {
                appendInlineChildren(
                    child,
                    content,
                    styles,
                    context,
                    density,
                    fontSizePx,
                    inlineContent,
                    onHashtagClick
                )
            }
        }
    }
}
