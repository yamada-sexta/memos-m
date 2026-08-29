package org.example.memosm.ui.component.composer

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import org.example.memosm.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


@Composable
fun rememberMarkdownLanguageHandler(): MarkdownLanguageHandler {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    return remember(colorScheme, typography) {
        MarkdownLanguageHandler(colorScheme, typography)
    }
}

@Composable
fun MemoInput(
    modifier: Modifier = Modifier,
    contentState: androidx.compose.ui.text.input.TextFieldValue,
    onContentChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    placeholder: String = stringResource(R.string.memo_composer_placeholder),
    availableTags: Map<String, Int>,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // Suggestion Logic
    var currentSuggestionResult by remember { mutableStateOf<SuggestionResult?>(null) }
    var showSuggestionPopup by remember { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val resources = LocalContext.current.resources

    val markdownHandler = rememberMarkdownLanguageHandler()

    // Monitor text/selection changes to trigger suggestions
    LaunchedEffect(contentState.text, contentState.selection) {
        val result = SuggestionProvider.getSuggestions(
            contentState.text, contentState.selection, availableTags, resources
        )
        currentSuggestionResult = result
        if (result != null && result.type.isAutoShown) {
            showSuggestionPopup = true
        } else if (result == null) {
            showSuggestionPopup = false
        }
        // For non-auto types (Markdown, Code), showSuggestionPopup remains false (showing icon)
    }

    if (autoFocus) {
        LaunchedEffect(Unit) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    Column(modifier = modifier) {
        val interactionSource = remember { MutableInteractionSource() }
        val paddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        val textStyle = LocalTextStyle.current

        var boxPositionInWindow by remember { mutableStateOf(IntOffset.Zero) }
        var boxHeightPx by remember { mutableIntStateOf(0) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.positionInWindow()
                    boxPositionInWindow = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
                    boxHeightPx = coordinates.size.height
                }) {
            if (contentState.text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }

            BasicTextField(
                value = contentState,
                onValueChange = { newValue ->
                    val processedValue = markdownHandler.processInput(contentState, newValue)
                    onContentChange(processedValue)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .focusRequester(focusRequester),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                enabled = enabled,
                onTextLayout = { result -> textLayoutResult = result },
                visualTransformation = markdownHandler,
                interactionSource = interactionSource,
            )

            // Auto-scroll to keep cursor visible
            LaunchedEffect(contentState.selection, contentState.text) {
                val layout = textLayoutResult ?: return@LaunchedEffect
                val cursorIndex =
                    contentState.selection.start.coerceIn(0, layout.layoutInput.text.length)
                val cursorRect = layout.getCursorRect(cursorIndex)
                val cursorBottom = cursorRect.bottom.roundToInt()
                val cursorTop = cursorRect.top.roundToInt()
                val viewportTop = scrollState.value
                val viewportBottom = viewportTop + scrollState.viewportSize

                if (cursorBottom > viewportBottom) {
                    scrollState.animateScrollTo(cursorBottom - scrollState.viewportSize)
                } else if (cursorTop < viewportTop) {
                    scrollState.animateScrollTo(cursorTop)
                }
            }

            // Suggestion UI (Popup OR Hint Icon)
            // Suggestion UI Logic
            // 1. Define states
            val suggestionUIState = remember(currentSuggestionResult, showSuggestionPopup) {
                val result = currentSuggestionResult
                when {
                    result == null -> SuggestionUIState.Hidden
                    result.type.isAutoShown -> SuggestionUIState.List
                    showSuggestionPopup -> SuggestionUIState.List
                    else -> SuggestionUIState.Icon
                }
            }

            // 2. Manage transition state
            val transitionState = remember { MutableTransitionState(SuggestionUIState.Hidden) }
            transitionState.targetState = suggestionUIState

            // 3. Keep track of the last valid result to show during exit animations
            var activeSuggestionResult by remember { mutableStateOf<SuggestionResult?>(null) }
            if (currentSuggestionResult != null) {
                activeSuggestionResult = currentSuggestionResult
            }

            // 4. Calculate position (always needed if we are showing or animating)
            // We use the current cursor position. If fading out, it follows the cursor.
            val isVisible =
                transitionState.currentState != SuggestionUIState.Hidden || transitionState.targetState != SuggestionUIState.Hidden

            if (isVisible && activeSuggestionResult != null) {
                val result = activeSuggestionResult!!
                val imeBottom = WindowInsets.ime.getBottom(density)
                val cursorRect = remember(textLayoutResult, contentState.selection) {
                    val layout = textLayoutResult ?: return@remember IntRect.Zero
                    val cursorIndex = contentState.selection.start
                    val safeIndex = cursorIndex.coerceIn(0, layout.layoutInput.text.length)
                    val rect = layout.getCursorRect(safeIndex)
                    IntRect(
                        left = rect.left.roundToInt(),
                        top = rect.top.roundToInt(),
                        right = rect.right.roundToInt(),
                        bottom = rect.bottom.roundToInt()
                    )
                }

                val effectiveScrollTop = scrollState.value
                val popupPositionProvider =
                    remember(cursorRect, imeBottom, density, effectiveScrollTop) {
                        CursorPopupPositionProvider(
                            cursorRect = cursorRect,
                            imeBottom = imeBottom,
                            density = density,
                            scrollTop = effectiveScrollTop
                        )
                    }

                // Constraints calculation
                val boxY = boxPositionInWindow.y
                val cursorTopY = boxY + cursorRect.top - effectiveScrollTop
                val cursorBottomY = boxY + cursorRect.bottom - effectiveScrollTop
                val configuration = LocalConfiguration.current
                val screenHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }
                val effectiveWindowBottom = screenHeight - imeBottom
                val spaceBelow = max(
                    0, effectiveWindowBottom - cursorBottomY - with(density) { 12.dp.roundToPx() })
                val spaceAbove = max(0, cursorTopY - with(density) { 4.dp.roundToPx() })
                val maxHeightDp = with(density) { max(spaceBelow, spaceAbove).toDp() }
                val constrainedMaxHeight = min(maxHeightDp.value, 200f).dp

                Popup(
                    popupPositionProvider = popupPositionProvider, onDismissRequest = {
                        // Only dismiss if we are in List mode and it's not auto-shown (or if user taps outside)
                        // If it's auto-shown, usually clicking outside or typing handles it.
                        // But standard Popup behavior is to dismiss on outside click.
                        // We map this to: Close Popup -> State recalculation handles the rest.
                        showSuggestionPopup = false
                        // If it was auto-shown, we also clear the result so it actually hides.
                        if (result.type.isAutoShown) {
                            currentSuggestionResult = null
                        }
                    }) {
                    val transition = rememberTransition(
                        transitionState, label = "SuggestionTransition"
                    )
                    transition.AnimatedContent(
                        transitionSpec = {
                            // Define transitions for all state pairs
                            val tweenFloat = tween<Float>(150)
                            val tweenSize = tween<IntSize>(150)

                            if (initialState == SuggestionUIState.Hidden && targetState == SuggestionUIState.Icon) {
                                // Appear as Icon (Zoom In)
                                (scaleIn(initialScale = 0f, animationSpec = tweenFloat) + fadeIn(
                                    animationSpec = tweenFloat
                                )).togetherWith(androidx.compose.animation.ExitTransition.None)
                            } else if (initialState == SuggestionUIState.Icon && targetState == SuggestionUIState.Hidden) {
                                // Disappear as Icon (Zoom Out)
                                androidx.compose.animation.EnterTransition.None.togetherWith(
                                    scaleOut(
                                        targetScale = 0f, animationSpec = tweenFloat
                                    ) + fadeOut(animationSpec = tweenFloat)
                                )
                            } else if (initialState == SuggestionUIState.Hidden && targetState == SuggestionUIState.List) {
                                // Appear as List (Fade + Scale)
                                (fadeIn(animationSpec = tweenFloat) + scaleIn(
                                    initialScale = 0.8f, animationSpec = tweenFloat
                                )).togetherWith(androidx.compose.animation.ExitTransition.None)
                            } else if (initialState == SuggestionUIState.List && targetState == SuggestionUIState.Hidden) {
                                // Disappear as List
                                androidx.compose.animation.EnterTransition.None.togetherWith(
                                    fadeOut(animationSpec = tweenFloat) + scaleOut(
                                        targetScale = 0.8f, animationSpec = tweenFloat
                                    )
                                )
                            } else if (initialState == SuggestionUIState.Icon && targetState == SuggestionUIState.List) {
                                // Morph: Icon -> List (Expand)
                                (fadeIn(animationSpec = tweenFloat) + androidx.compose.animation.expandIn(
                                    expandFrom = androidx.compose.ui.Alignment.Center,
                                    animationSpec = tweenSize
                                )).togetherWith(
                                    fadeOut(animationSpec = tweenFloat) + androidx.compose.animation.shrinkOut(
                                        shrinkTowards = androidx.compose.ui.Alignment.Center,
                                        animationSpec = tweenSize
                                    )
                                )
                            } else if (initialState == SuggestionUIState.List && targetState == SuggestionUIState.Icon) {
                                // Morph: List -> Icon (Collapse)
                                (fadeIn(animationSpec = tweenFloat) + scaleIn(
                                    animationSpec = tweenFloat
                                )).togetherWith(
                                    fadeOut(animationSpec = tweenFloat) + scaleOut(
                                        animationSpec = tweenFloat
                                    )
                                )
                            } else {
                                // Default or same state
                                androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                            }
                        },
                    ) { state ->
                        when (state) {
                            SuggestionUIState.Icon -> {
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    tonalElevation = 6.dp,
                                    shadowElevation = 6.dp,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clickable { showSuggestionPopup = true }) {
                                    Box(modifier = Modifier.padding(8.dp)) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = stringResource(R.string.memo_input_show_suggestions),
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            SuggestionUIState.List -> {
                                Surface(
                                    modifier = Modifier
                                        .widthIn(min = 140.dp, max = 240.dp)
                                        .heightIn(max = constrainedMaxHeight),
                                    shape = RoundedCornerShape(8.dp),
                                    tonalElevation = 3.dp,
                                    shadowElevation = 3.dp,
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                        items(result.suggestions) { item ->
                                            DropdownMenuItem(text = {
                                                Text(
                                                    text = item.label,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }, leadingIcon = {
                                                Icon(
                                                    imageVector = item.icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }, onClick = {
                                                if (result.type == SuggestionType.FORMATTING) {
                                                    // Selection formatting
                                                    val start = contentState.selection.start
                                                    val end = contentState.selection.end
                                                    val selectedText =
                                                        contentState.text.substring(start, end)
                                                    val replacement =
                                                        "${item.content}$selectedText${item.content}"
                                                    val newText = contentState.text.replaceRange(
                                                        start, end, replacement
                                                    )
                                                    val newSelection =
                                                        TextRange(start + replacement.length)
                                                    onContentChange(
                                                        contentState.copy(
                                                            text = newText,
                                                            selection = newSelection
                                                        )
                                                    )
                                                } else {
                                                    // Text Insertion
                                                    val replacement =
                                                        if (result.type == SuggestionType.HASHTAG) "#${item.content} " else item.content

                                                    val text = contentState.text
                                                    val replaceStart = result.startIndex
                                                    val replaceEnd = contentState.selection.start

                                                    val newText = text.replaceRange(
                                                        replaceStart, replaceEnd, replacement
                                                    )
                                                    val newSelection =
                                                        TextRange(replaceStart + replacement.length)
                                                    onContentChange(
                                                        contentState.copy(
                                                            text = newText,
                                                            selection = newSelection
                                                        )
                                                    )
                                                }
                                                currentSuggestionResult = null
                                                showSuggestionPopup = false
                                            })
                                        }
                                    }
                                }
                            }

                            SuggestionUIState.Hidden -> {
                                // Nothing to render
                            }
                        }
                    }
                }
            }
        }
    }
}

fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

private class CursorPopupPositionProvider(
    private val cursorRect: IntRect,
    private val imeBottom: Int,
    private val density: androidx.compose.ui.unit.Density,
    private val scrollTop: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val horizontalPadding = with(density) { 4.dp.roundToPx() }
        // Account for custom inner padding (vertical = 12dp)
        val paddingBelow = with(density) { 12.dp.roundToPx() }
        val paddingAbove = with(density) { 4.dp.roundToPx() }

        val targetX = anchorBounds.left + cursorRect.left + horizontalPadding
        val targetYBelow = anchorBounds.top + cursorRect.bottom - scrollTop + paddingBelow
        val targetYAbove =
            anchorBounds.top + cursorRect.top - scrollTop - popupContentSize.height + paddingAbove

        val effectiveWindowBottom = windowSize.height

        val isSpaceBelow = (targetYBelow + popupContentSize.height) <= effectiveWindowBottom
        val isSpaceAbove = targetYAbove >= 0

        Log.d("PopupDebug", "=== calculatePosition ===")
        Log.d("PopupDebug", "anchorBounds=$anchorBounds")
        Log.d("PopupDebug", "windowSize=$windowSize")
        Log.d("PopupDebug", "cursorRect=$cursorRect, scrollTop=$scrollTop")
        Log.d("PopupDebug", "popupContentSize=$popupContentSize")
        Log.d("PopupDebug", "imeBottom=$imeBottom, effectiveWindowBottom=$effectiveWindowBottom")
        Log.d("PopupDebug", "targetYBelow=$targetYBelow, targetYAbove=$targetYAbove")
        Log.d(
            "PopupDebug",
            "isSpaceBelow=$isSpaceBelow (${targetYBelow + popupContentSize.height} <= $effectiveWindowBottom)"
        )
        Log.d("PopupDebug", "isSpaceAbove=$isSpaceAbove (targetYAbove=$targetYAbove >= 0)")

        val y = when {
            isSpaceBelow -> targetYBelow
            isSpaceAbove -> targetYAbove
            else -> {
                // Neither side fits fully. Pick the side with more room.
                val cursorBottomInWindow = anchorBounds.top + cursorRect.bottom - scrollTop
                val cursorTopInWindow = anchorBounds.top + cursorRect.top - scrollTop
                val spaceBelow = effectiveWindowBottom - cursorBottomInWindow
                val spaceAbove = cursorTopInWindow
                Log.d("PopupDebug", "else branch: spaceBelow=$spaceBelow, spaceAbove=$spaceAbove")
                if (spaceBelow >= spaceAbove) {
                    targetYBelow
                } else {
                    targetYAbove.coerceAtLeast(0)
                }
            }
        }

        Log.d(
            "PopupDebug",
            "FINAL y=$y (chose=${if (isSpaceBelow) "below" else if (isSpaceAbove) "above" else "else"})"
        )

        return IntOffset(targetX, y)
    }
}

private enum class SuggestionUIState {
    Hidden, Icon, List
}
