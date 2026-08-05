package com.musheer360.swiftslate.ui.processtext

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * SwiftSlate's own bottom sheet, deliberately not Material3's [androidx.compose.material3.ModalBottomSheet].
 *
 * ModalBottomSheet cannot express the behaviour this screen needs. It listens for whatever a
 * child scrollable leaves unconsumed and translates itself by it, and the only way to stop that
 * is to consume the leftover — but in Compose the overscroll effect is rendered from exactly that
 * leftover, so consuming it removes the stretch glow from the list. Sheet-stays-put and
 * list-bounces-at-its-ends are mutually exclusive there, and 1.4.0 has no gesture-disable flag.
 *
 * Owning the sheet instead means nothing between the list and the window consumes scroll, so the
 * command list keeps the same native bounce the Commands screen's LazyColumn has, while the sheet
 * moves only for the two gestures that should move it: a drag on [SheetHandle] or a tap on the
 * scrim. It also renders in the Activity's own window rather than a second one, so overlays like
 * the copy confirmation can sit above the content.
 *
 * The host Activity's theme is already translucent with no window animation, so the enter and
 * exit transitions here are the only ones that run.
 */
@Composable
fun SlateBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val visible = shown && !closing

    // Enter on the first frame so the transition actually animates instead of the sheet simply
    // being present on composition.
    LaunchedEffect(Unit) { shown = true }
    // Let the exit transition finish before the Activity goes away, otherwise finish() tears the
    // window down mid-animation and the sheet appears to vanish.
    LaunchedEffect(closing) { if (closing) { delay(EXIT_TOTAL_MS); onDismiss() } }

    val close: () -> Unit = remember { { closing = true } }
    BackHandler(enabled = !closing, onBack = close)

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) FADE_IN_MS else FADE_OUT_MS),
        label = "scrim"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Tap anywhere off the sheet to dismiss. No indication: a ripple across the whole screen
        // is not what a scrim should do.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SCRIM_COLOR.copy(alpha = SCRIM_ALPHA * scrimAlpha))
                .clickable(interactionSource = null, indication = null, onClick = close)
        )

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(ANIM_MS)) { it } + fadeIn(tween(FADE_IN_MS)),
            exit = slideOutVertically(tween(ANIM_MS)) { it } + fadeOut(tween(FADE_OUT_MS))
        ) {
            SheetSurface(onDismissRequest = close, content = content)
        }
    }
}

@Composable
private fun SheetSurface(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dragOffset = remember { Animatable(0f) }
    val dismissThresholdPx = remember(density) { with(density) { DISMISS_THRESHOLD_DP.dp.toPx() } }

    val draggableState = rememberDraggableState { delta ->
        scope.launch {
            // Downwards only: there is nothing above the resting position to drag to.
            dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Follows the finger while the sheet is being dragged.
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .clip(RoundedCornerShape(topStart = CORNER_DP.dp, topEnd = CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.background)
            // Swallow taps that land on the sheet itself. A background alone does not consume
            // pointer input, so without this a tap on the handle or on the title row — anywhere
            // without an interactive child under it — fell through to the scrim behind and
            // dismissed the sheet. No indication: this is only here to stop the fall-through,
            // it is not a button.
            .clickable(interactionSource = null, indication = null, onClick = {})
            // Drag-to-dismiss lives on the whole surface, not on the handle alone.
            //
            // It was on the handle, and only the handle appeared to work: Compose expands a
            // small interactive element to the 48dp minimum touch target, so the pill claimed a
            // 48dp square of hit area while the strip around it was thinner than that and lost
            // the arbitration. Owning the gesture at the surface removes the competition
            // entirely. Children that handle their own vertical drags still win, because they
            // are deeper in the tree — the command list and result text keep scrolling (and keep
            // their overscroll glow, since they consume the delta themselves rather than letting
            // it reach here).
            .draggable(
                orientation = Orientation.Vertical,
                state = draggableState,
                onDragStopped = { velocity ->
                    if (dragOffset.value >= dismissThresholdPx || velocity >= DISMISS_VELOCITY) {
                        onDismissRequest()
                    } else {
                        dragOffset.animateTo(0f, tween(ANIM_MS))
                    }
                }
            )
            // Keeps content clear of the navigation bar; the background above still runs to the
            // bottom edge of the screen.
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SheetHandle()
        content()
    }
}

/**
 * The grab affordance: a full-width strip, at least the 48dp minimum touch target tall, with the
 * pill drawn centred inside it. Carries no gesture of its own — [SheetSurface] owns the drag, so
 * this cannot end up as a competing hit target the way it did when the pill held the gesture.
 */
@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HANDLE_ROW_HEIGHT_DP.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = HANDLE_WIDTH_DP.dp, height = HANDLE_HEIGHT_DP.dp)
                .background(
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(HANDLE_HEIGHT_DP.dp / 2)
                )
        )
    }
}

private const val ANIM_MS = 250
private const val FADE_IN_MS = 200
private const val FADE_OUT_MS = 150
private const val EXIT_TOTAL_MS = 250L
private const val CORNER_DP = 16
private const val HANDLE_WIDTH_DP = 32
private const val HANDLE_HEIGHT_DP = 4
/** At least the 48dp minimum interactive touch target, so nothing inside can out-claim it. */
private const val HANDLE_ROW_HEIGHT_DP = 48
private const val DISMISS_THRESHOLD_DP = 96
private const val DISMISS_VELOCITY = 1_000f
private const val SCRIM_ALPHA = 0.4f
private val SCRIM_COLOR = Color.Black
