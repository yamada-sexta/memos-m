package org.example.memosm.ui.component.item.media

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

internal val LocalViewerGesturesBlocked = compositionLocalOf { false }
internal enum class ViewerDragIntent { None, Dismiss, Details }

internal fun detailsSnapTarget(offset: Int, snapOffset: Int, minDistance: Float, velocity: Float, minVelocity: Float): Int = when {
    velocity < -minVelocity -> snapOffset
    velocity > minVelocity -> if (offset < snapOffset) 0 else snapOffset
    offset < minDistance -> 0
    else -> snapOffset
}

/** The media and details share one scroll surface, like Immich's mobile asset page. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun FullScreenMediaDialog(
    onDismiss: () -> Unit,
    mediaAspectRatio: Float? = null,
    originBounds: (() -> Rect?)? = null,
    infoContent: (@Composable () -> Unit)? = null,
    actionsContent: (@Composable (showInfo: () -> Unit) -> Unit)? = null,
    gestureKey: Any? = null,
    content: @Composable BoxScope.(dismiss: () -> Unit) -> Unit
) {
    val density = LocalDensity.current
    val threshold = with(density) { 120.dp.toPx() }
    val fadeDistance = with(density) { 180.dp.toPx() }
    val scaleDistance = with(density) { 600.dp.toPx() }
    val edgeDistance = with(density) { 60.dp.toPx() }
    val minDetailsDistance = with(density) { 30.dp.toPx() }
    val minFlingVelocity = with(density) { 700.dp.toPx() }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentOrigin by rememberUpdatedState(originBounds)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var exiting by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(true) }
    var viewerReady by remember { mutableStateOf(false) }
    val enterProgress = remember { Animatable(0f) }
    var enterOrigin by remember { mutableStateOf<Rect?>(null) }
    var enterMediaSize by remember { mutableStateOf(Size(1f, 1f)) }
    var backing by remember { mutableStateOf(false) }
    var backingDetails by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val exitProgress = remember { Animatable(0f) }
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { mutableFloatStateOf(1f) }
    var exitTarget by remember { mutableStateOf<Rect?>(null) }
    var viewerPosition by remember { mutableStateOf(Offset.Zero) }
    var exitStartScale by remember { mutableFloatStateOf(1f) }
    var exitStartTranslation by remember { mutableStateOf(Offset.Zero) }
    var exitMediaSize by remember { mutableStateOf(Size(1f, 1f)) }
    val scroll = rememberScrollState()
    val flingBehavior = ScrollableDefaults.flingBehavior()
    val zoomState = remember(gestureKey) { ViewerZoomState() }

    Dialog(
        onDismissRequest = { if (!exiting) currentDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = false, dismissOnClickOutside = false
        )
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                it.setDimAmount(0f)
                it.setWindowAnimations(0)
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        val backOwner = checkNotNull(
            view.findViewTreeOnBackPressedDispatcherOwner() ?: LocalOnBackPressedDispatcherOwner.current
        ) { "A full-screen media dialog requires a back dispatcher" }
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner) {
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
                viewerPosition = it.localToScreen(Offset.Zero)
                viewerReady = true
            }) {
                val width = constraints.maxWidth.toFloat()
                val height = constraints.maxHeight.toFloat()
                val viewportHeight = maxHeight
                val imageHeight = mediaAspectRatio?.takeIf { it.isFinite() && it > 0f }
                    ?.let { min(width / it, height) } ?: height
                val imageWidth = mediaAspectRatio?.takeIf { it.isFinite() && it > 0f }
                    ?.let { min(height * it, width) } ?: width
                LaunchedEffect(viewerReady) {
                    if (viewerReady && entering) {
                        enterOrigin = currentOrigin?.invoke()?.translate(-viewerPosition)
                        enterMediaSize = Size(imageWidth, imageHeight)
                        enterProgress.animateTo(1f, tween(220))
                        entering = false
                    }
                }
                val detailsTop = ((height + imageHeight - with(density) { 48.dp.toPx() }) / 2f)
                    .coerceAtLeast(height / 3f)
                val snapOffset = (detailsTop - height / 3f).toInt().coerceAtLeast(1)
                val infoVisible = scroll.value > 0
                val viewerBack = if (backingDetails) 0f else backProgress.value
                val downwardOffset = dragOffset.coerceAtLeast(0f)
                val dragProgress = (downwardOffset / fadeDistance).coerceIn(0f, 1f)
                val alpha = (1f - dragProgress) * (1f - viewerBack * 0.5f) *
                    (1f - exitProgress.value) * enterProgress.value
                val scale = ((1f - downwardOffset / scaleDistance) * (1f - viewerBack * 0.1f)).coerceIn(0.6f, 1f)
                val dismissWithZoom: (ViewerZoomTransform?) -> Unit = { zoom ->
                    if (!exiting) {
                        settleJob?.cancel()
                        exitTarget = currentOrigin?.invoke()?.translate(-viewerPosition)
                        // Read live gesture state here: a pointerInput callback can outlive the
                        // composition that created it, so its captured layout values may be stale.
                        val releaseOffset = dragOffset.coerceAtLeast(0f)
                        val releaseBack = if (backingDetails) 0f else backProgress.value
                        val releaseScale = ((1f - releaseOffset / scaleDistance) *
                            (1f - releaseBack * 0.1f)).coerceIn(0.6f, 1f)
                        exitStartScale = releaseScale * (zoom?.scale ?: 1f)
                        exitStartTranslation = Offset(releaseBack * edgeDistance * backEdge, releaseOffset) +
                            (zoom?.offset ?: Offset.Zero) * releaseScale
                        exitMediaSize = Size(imageWidth, imageHeight)
                        exiting = true
                        scope.launch {
                            exitProgress.animateTo(1f, tween(220))
                            currentDismiss()
                        }
                    }
                }
                val dismiss: () -> Unit = { dismissWithZoom(null) }
                SideEffect { zoomState.onPinchDismiss = { dismissWithZoom(it) } }
                val showInfo: () -> Unit = {
                    settleJob?.cancel()
                    settleJob = scope.launch { scroll.animateScrollTo(snapOffset, spring()) }
                }
                PredictiveBackHandler(enabled = !exiting && !entering) { events ->
                    settleJob?.cancel()
                    backingDetails = infoVisible
                    backing = true
                    try {
                        events.collect { event ->
                            backEdge = if (event.swipeEdge == 0) 1f else -1f
                            backProgress.snapTo(event.progress)
                        }
                        if (backingDetails) {
                            backProgress.animateTo(1f, tween(180))
                            scroll.scrollTo(0)
                            backProgress.snapTo(0f)
                        } else dismiss()
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { backProgress.animateTo(0f, spring()) }
                        throw cancelled
                    } finally {
                        backing = false
                        backingDetails = false
                    }
                }

                val target = exitTarget
                val progress = exitProgress.value
                val transition = when {
                    entering -> mediaTransitionTransform(
                        Size(width, height), enterMediaSize, enterOrigin, 1f, Offset.Zero, 1f - enterProgress.value
                    )
                    exiting -> mediaTransitionTransform(
                        Size(width, height), exitMediaSize, target, exitStartScale, exitStartTranslation, progress,
                        Offset(0f, if (exitStartTranslation.y > 0f) height * 0.25f else 0f)
                    )
                    else -> MediaTransitionTransform(scale, Offset(viewerBack * edgeDistance * backEdge, downwardOffset), null)
                }

                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha))) {
                    CompositionLocalProvider(
                        LocalViewerGesturesBlocked provides (infoVisible || backing || exiting || entering),
                        LocalViewerZoomState provides zoomState
                    ) {
                        Box(
                            Modifier.fillMaxSize().testTag("attachment_viewer")
                                .pointerInput(exiting, backing, entering, infoContent != null, snapOffset, zoomState) {
                                    if (exiting || backing || entering) return@pointerInput
                                    val tracker = VelocityTracker()
                                    var intent = ViewerDragIntent.None
                                    var startScroll = 0
                                    detectViewerVerticalGestures(
                                        canStart = { scroll.value > 0 || zoomState.zoomedBy == null },
                                        onDragStart = {
                                            settleJob?.cancel()
                                            tracker.resetTracking()
                                            startScroll = scroll.value
                                            intent = if (scroll.value > 0) ViewerDragIntent.Details else ViewerDragIntent.None
                                        },
                                        onDragCancel = {
                                            if (!exiting && !backing) {
                                                settleJob = scope.launch {
                                                    Animatable(dragOffset).animateTo(0f, spring()) { dragOffset = value }
                                                    scroll.animateScrollTo(startScroll, spring())
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            if (intent == ViewerDragIntent.Dismiss && dragOffset > threshold) dismiss()
                                            else {
                                                val velocity = tracker.calculateVelocity().y
                                                settleJob = scope.launch {
                                                    if (intent == ViewerDragIntent.Details) {
                                                        if (scroll.value > snapOffset) {
                                                            scroll.scroll { with(flingBehavior) { performFling(-velocity) } }
                                                        }
                                                        if (scroll.value <= snapOffset) {
                                                            scroll.animateScrollTo(detailsSnapTarget(
                                                                scroll.value, snapOffset, minDetailsDistance, velocity, minFlingVelocity
                                                            ), spring())
                                                        }
                                                    }
                                                    Animatable(dragOffset).animateTo(0f, spring()) { dragOffset = value }
                                                }
                                            }
                                        },
                                        onVerticalDrag = { change, amount ->
                                            if (intent == ViewerDragIntent.None) {
                                                intent = if (amount > 0f || infoContent == null) ViewerDragIntent.Dismiss
                                                    else ViewerDragIntent.Details
                                            }
                                            change.consume()
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            if (intent == ViewerDragIntent.Dismiss) dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                                            else scroll.dispatchRawDelta(-amount)
                                        }
                                    )
                                }
                                .graphicsLayer {
                                    scaleX = transition.scale
                                    scaleY = transition.scale
                                    translationX = transition.translation.x
                                    translationY = transition.translation.y
                                    this.alpha = if (entering) enterProgress.value else if (target == null) 1f - progress else 1f
                                }
                                .drawWithContent {
                                    val clip = transition.clip
                                    if (clip != null) {
                                        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                                            this@drawWithContent.drawContent()
                                        }
                                    } else drawContent()
                                }
                        ) {
                            // Immich proxies drag gestures into its scroll controller as well. Keeping one
                            // owner prevents a downward details gesture becoming a dismiss halfway through.
                            Box(
                                Modifier.fillMaxSize().verticalScroll(scroll, enabled = false)
                                    .semantics {
                                        scrollBy { _, y ->
                                            settleJob = scope.launch { scroll.animateScrollTo((scroll.value + y.toInt()).coerceIn(0, scroll.maxValue)) }
                                            true
                                        }
                                    }
                                    .graphicsLayer {
                                        translationY = if (backingDetails) scroll.value * backProgress.value else 0f
                                    }
                            ) {
                                Box(Modifier.fillMaxWidth().height(viewportHeight)) { content(dismiss) }
                                if (infoContent != null) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Spacer(Modifier.height(with(density) { detailsTop.toDp() }))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth()
                                                .heightIn(min = viewportHeight * (2f / 3f))
                                                .graphicsLayer { this.alpha = if (infoVisible) 1f else 0f },
                                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            shadowElevation = 4.dp
                                        ) {
                                            Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
                                                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                                                if (infoVisible) infoContent()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (actionsContent != null && !infoVisible) {
                        Box(
                            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                                .graphicsLayer { this.alpha = if (entering || exiting) 0f else alpha }
                        ) {
                            CompositionLocalProvider(LocalViewerGesturesBlocked provides (entering || exiting || backing)) {
                                actionsContent(showInfo)
                            }
                        }
                    }
                }
            }
        }
    }
}
