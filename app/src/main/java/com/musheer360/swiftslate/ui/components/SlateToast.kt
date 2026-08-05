package com.musheer360.swiftslate.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The look of SwiftSlate's toast, in one place.
 *
 * [com.musheer360.swiftslate.service.OverlayToast] renders this with Views because an
 * accessibility service has no Compose tree to draw into; [SlateToast] renders the same thing
 * for the Compose UI. Both read these values so the two cannot drift apart.
 */
object SlateToastTokens {
    /** Same ARGB the View implementation has always used. */
    const val BACKGROUND_ARGB: Int = 0xE6323232.toInt()
    const val DURATION_MS: Long = 3_500L
    const val ANIM_DURATION_MS: Int = 300
    const val SLIDE_DISTANCE_DP: Int = 40
    const val BOTTOM_MARGIN_DP: Int = 64
    const val CORNER_RADIUS_DP: Int = 24
    const val HORIZONTAL_PADDING_DP: Int = 24
    const val VERTICAL_PADDING_DP: Int = 12
    const val TEXT_SIZE_SP: Int = 14
    const val MAX_WIDTH_FRACTION: Float = 0.85f
}

/**
 * SwiftSlate's own toast for Compose surfaces, so no screen has to fall back to a platform
 * [android.widget.Toast] — the app renders every transient message itself.
 *
 * Visible while [message] is non-null; the caller decides when to clear it and where to put it.
 */
@Composable
fun SlateToast(
    message: String?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(SlateToastTokens.ANIM_DURATION_MS)) +
                slideInVertically(tween(SlateToastTokens.ANIM_DURATION_MS)) {
                    SlateToastTokens.SLIDE_DISTANCE_DP
                },
            exit = fadeOut(tween(SlateToastTokens.ANIM_DURATION_MS)) +
                slideOutVertically(tween(SlateToastTokens.ANIM_DURATION_MS)) {
                    SlateToastTokens.SLIDE_DISTANCE_DP
                }
        ) {
            Text(
                // Kept after the animation starts running so the exit transition has something
                // to draw; AnimatedVisibility outlives the message being cleared.
                text = message.orEmpty(),
                color = Color.White,
                fontSize = SlateToastTokens.TEXT_SIZE_SP.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(
                        color = Color(SlateToastTokens.BACKGROUND_ARGB),
                        shape = RoundedCornerShape(SlateToastTokens.CORNER_RADIUS_DP.dp)
                    )
                    .padding(
                        horizontal = SlateToastTokens.HORIZONTAL_PADDING_DP.dp,
                        vertical = SlateToastTokens.VERTICAL_PADDING_DP.dp
                    )
            )
        }
    }
}
