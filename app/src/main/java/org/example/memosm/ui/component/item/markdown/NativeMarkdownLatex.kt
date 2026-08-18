package org.example.memosm.ui.component.item.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agog.mathdisplay.MTMathView
import com.agog.mathdisplay.parse.MTMathListBuilder

@Composable
fun NativeMarkdownLatex(
    modifier: Modifier = Modifier,
    latex: String,
    inline: Boolean // New parameter to distinguish inline vs block
) {
    val localDensity = androidx.compose.ui.platform.LocalDensity.current
    // 1. Pre-validate the LaTeX string
    val validationError by remember(latex) {
        mutableStateOf(
            try {
                val list = MTMathListBuilder.buildFromString(latex)
                if (list == null) "Invalid Syntax" else null
            } catch (e: Exception) {
                null
            }
        )
    }

    if (validationError != null) {
        // 2. Choose error display based on inline status
        if (inline) {
            InlineLatexError(
                source = latex,
                modifier = modifier
            )
        } else {
            BlockLatexErrorCard(
                error = validationError!!,
                source = latex,
                modifier = modifier
            )
        }
    } else {
        // 3. Render Native View if valid
        val style = typography.bodyLarge
        val defaultColor = LocalContentColor.current
        // Match the logic in MarkdownText for "regular texts"
        val resolvedColor = if (style.color.isSpecified) style.color else defaultColor
        val argbColor = resolvedColor.toArgb()

        // Calculate font size in Composable scope
        val fontSizePx = with(localDensity) {
            style.fontSize.toPx()
        }

        AndroidView(
            factory = { context ->
                MTMathView(context, null).apply {
                    fontSize = fontSizePx
                    // Set initial color
                    textColor = argbColor
                    // labelMode = MTMathViewMode.KMathViewModeDisplay
                }
            },
            update = { view ->
                view.latex = latex
                // Update color on recomposition/theme change
                view.textColor = argbColor
            },
            modifier = modifier
        )
    }
}

@Composable
private fun InlineLatexError(
    source: String,
    modifier: Modifier = Modifier
) {
    // Simple red text for inline errors
    Text(
        text = source,
        color = MaterialTheme.colorScheme.error,
        fontFamily = FontFamily.Monospace,
        style = typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun BlockLatexErrorCard(
    error: String,
    source: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Equation Error",
                    style = typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = error,
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = source,
                        style = typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}