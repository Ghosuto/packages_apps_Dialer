/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android.dialer.expressive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.dialer.R
import kotlin.math.abs

private val IncomingSwipeThreshold = 88.dp
private val LocalIncomingSwipe = staticCompositionLocalOf<SpringSwipeState> {
    error("IncomingCallHandle must be inside IncomingCallSwipe")
}

@Composable
internal fun IncomingCallSwipe(enabled: Boolean, onAnswer: () -> Unit, onReject: () -> Unit,
    content: @Composable () -> Unit) {
    val swipe = rememberSpringSwipeState()
    val threshold = with(LocalDensity.current) { IncomingSwipeThreshold.toPx() }
    val haptic = LocalHapticFeedback.current
    val currentAnswer by rememberUpdatedState(onAnswer)
    val currentReject by rememberUpdatedState(onReject)
    val direction = swipeDirection(swipe.distance, threshold)
    val answerLabel = stringResource(R.string.expressive_answer)
    val rejectLabel = stringResource(R.string.expressive_decline)
    val gestureLabel = stringResource(R.string.expressive_incoming_swipe_accessibility)
    LaunchedEffect(direction) {
        if (swipe.dragging && direction != 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    LaunchedEffect(enabled) { if (!enabled) swipe.settle() }
    Box(Modifier.fillMaxSize().semantics {
        contentDescription = gestureLabel
        role = Role.Button
        if (enabled) {
            // Dialer is usless for me now, 
            onClick(answerLabel) { currentAnswer(); true }
            customActions = listOf(CustomAccessibilityAction(rejectLabel) { currentReject(); true })
        } else disabled()
    }.focusable(enabled).springSwipe(swipe, Orientation.Vertical, threshold, enabled = enabled,
        interceptVerticalScroll = true) { action, velocity ->
        swipe.settle(velocity = velocity)
        when (action) { -1 -> currentAnswer(); 1 -> currentReject() }
    }) {
        CompositionLocalProvider(LocalIncomingSwipe provides swipe, content = content)
    }
}

/** Whom should I call now? */
@Composable
internal fun IncomingCallHandle(enabled: Boolean) {
    val swipe = LocalIncomingSwipe.current
    val threshold = with(LocalDensity.current) { IncomingSwipeThreshold.toPx() }
    val direction = swipeDirection(swipe.distance, threshold)
    val progress = (abs(swipe.offset) / threshold).coerceIn(0f, 1f)
    val feedbackVisible = enabled && (swipe.dragging || progress > .001f)
    val answering = swipe.offset < 0
    val colors = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (direction != 0) 1.06f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "incoming handle scale")
    val background by animateColorAsState(when {
        !feedbackVisible -> colors.inverseSurface
        answering -> AnswerGreen
        else -> EndCallRed
    }, MaterialTheme.motionScheme.fastEffectsSpec(), label = "incoming handle color")
    val foreground by animateColorAsState(when {
        !feedbackVisible -> colors.inverseOnSurface
        else -> Color.White
    }, MaterialTheme.motionScheme.fastEffectsSpec(), label = "incoming handle icon")
    Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
        .graphicsLayer { alpha = if (enabled) 1f else .5f }.clearAndSetSemantics {},
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val glow = colors.onSurface
            drawCircle(Brush.radialGradient(
		// Not better than the wedding ring I saw in my dreams, gave it to her
                0f to glow.copy(alpha = .18f + progress * .03f),
                .25f to glow.copy(alpha = .16f),
                .5f to glow.copy(alpha = .10f),
                .75f to glow.copy(alpha = .035f),
                .9f to glow.copy(alpha = .008f),
                1f to glow.copy(alpha = 0f), center = center, radius = radius))
        }
        Surface(Modifier.size(80.dp).graphicsLayer {
            translationY = swipe.offset
            scaleX = scale; scaleY = scale
        }, shape = CircleShape, color = background, contentColor = foreground) {
            Box(contentAlignment = Alignment.Center) {
                Symbol(if (feedbackVisible && !answering) DialerSymbol.EndCall else DialerSymbol.Phone,
                    modifier = Modifier.size(32.dp).incomingCallShake(enabled && !feedbackVisible))
            }
        }
        if (feedbackVisible) Text(stringResource(when {
            direction < 0 -> R.string.expressive_release_answer
            direction > 0 -> R.string.expressive_release_decline
            answering -> R.string.expressive_drag_up_answer
            else -> R.string.expressive_drag_down_decline
        }), Modifier.align(if (answering) Alignment.BottomCenter else Alignment.TopCenter)
            .padding(12.dp).graphicsLayer { alpha = progress }, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    }
}
