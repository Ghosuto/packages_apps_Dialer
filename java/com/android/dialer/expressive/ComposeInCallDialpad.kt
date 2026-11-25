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

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.dialer.R
import com.android.incallui.DialpadFragment

object ComposeInCallDialpad {
    @JvmStatic fun create(fragment: DialpadFragment, field: EditText): ComposeView = ComposeView(fragment.requireContext()).apply {
        id = R.id.expressive_dtmf
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            var digits by remember { mutableStateOf(field.text.toString()) }
            DisposableEffect(field) {
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { digits = s.toString() }
                    override fun afterTextChanged(s: Editable?) {}
                }
                field.addTextChangedListener(watcher)
                onDispose { field.removeTextChangedListener(watcher); fragment.onExpressiveDigit('0', false) }
            }
            DialerExpressiveTheme {
                val roundedControls = rememberRoundedCallControls()
                val colors = MaterialTheme.colorScheme
                val dark = isSystemInDarkTheme()
                val neutralBackground = if (dark) Color.Black.copy(alpha = .34f)
                    else colors.surfaceContainerHigh.copy(alpha = .82f)
                val neutralForeground = if (dark) Color.White else Color.Black
                Surface(shape = if (roundedControls) RoundedCornerShape(32.dp)
                        else RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                    color = if (roundedControls) Color.Transparent else colors.surfaceContainer,
                    contentColor = if (roundedControls) neutralForeground else colors.onSurface,
                    border = if (roundedControls) null else BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .28f))) {
                    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .65f)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(if (roundedControls) 0.dp else 8.dp)) {
                        Surface(shape = RoundedCornerShape(24.dp),
                            color = if (roundedControls) neutralBackground else colors.surfaceContainerHigh,
                            contentColor = if (roundedControls) neutralForeground else colors.onSurface) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(digits, Modifier.weight(1f).padding(horizontal = 12.dp),
                                    style = if (roundedControls) MaterialTheme.typography.headlineMedium
                                        else MaterialTheme.typography.headlineMediumEmphasized.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (roundedControls) {
                                    val interaction = remember { MutableInteractionSource() }
                                    val pressed by interaction.collectIsPressedAsState()
                                    val background by animateColorAsState(
                                        if (pressed) Color.White.copy(alpha = .94f) else neutralBackground,
                                        tween(100), label = "dtmf close color")
                                    val foreground by animateColorAsState(
                                        if (pressed) Color.Black else neutralForeground,
                                        tween(100), label = "dtmf close content")
                                    FilledTonalIconButton(
                                        onClick = { fragment.requireActivity().onBackPressed() },
                                        modifier = Modifier.clip(CircleShape),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = background, contentColor = foreground),
                                        interactionSource = interaction) {
                                        Symbol(DialerSymbol.Close, stringResource(R.string.expressive_close_keypad))
                                    }
                                } else {
                                    FilledTonalIconButton(onClick = { fragment.requireActivity().onBackPressed() }) {
                                        Symbol(DialerSymbol.Close, stringResource(R.string.expressive_close_keypad))
                                    }
                                }
                            }
                        }
                        listOf("123", "456", "789", "*0#").forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { digit ->
                                    if (roundedControls) Box(Modifier.weight(1f).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                        DtmfKey(fragment, digit, Modifier.size(60.dp), roundedControls = true)
                                    } else DtmfKey(fragment, digit, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dtmfLetters(digit: Char): String = when (digit) {
    '2' -> "ABC"
    '3' -> "DEF"
    '4' -> "GHI"
    '5' -> "JKL"
    '6' -> "MNO"
    '7' -> "PQRS"
    '8' -> "TUV"
    '9' -> "WXYZ"
    '0' -> "+"
    else -> ""
}

@Composable
private fun DtmfKey(fragment: DialpadFragment, digit: Char, modifier: Modifier, roundedControls: Boolean = false) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) .94f else 1f,
        if (roundedControls) spring(.65f, 500f) else MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "dtmf press"
    )
    val radius by animateDpAsState(
        if (pressed) 18.dp else if (roundedControls) 32.dp else 52.dp,
        if (roundedControls) spring(.7f, 500f) else MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "dtmf shape"
    )
    val dark = isSystemInDarkTheme()
    val background by animateColorAsState(if (roundedControls && pressed) Color.White.copy(alpha = .94f)
        else if (pressed) MaterialTheme.colorScheme.primary
        else if (roundedControls) {
            if (dark) Color.Black.copy(alpha = .34f)
            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .82f)
        }
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        if (roundedControls) tween(100) else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "dtmf color")
    val foreground by animateColorAsState(if (roundedControls) {
            if (pressed || !dark) Color.Black else Color.White
        } else if (pressed) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        if (roundedControls) tween(100) else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "dtmf content")
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Surface((if (roundedControls) modifier else modifier.heightIn(min = 60.dp))
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (roundedControls) Modifier.clip(RoundedCornerShape(radius)) else Modifier)
        .semantics {
            role = Role.Button
            contentDescription = digit.toString()
            onClick {
                scope.launch {
                    pressed = true
                    fragment.onExpressiveDigit(digit, true)
                    try { delay(120) } finally { pressed = false; fragment.onExpressiveDigit(digit, false) }
                }
                true
            }
        }.onKeyEvent { event ->
            if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter) {
                if (event.type == KeyEventType.KeyDown && !pressed) {
                    pressed = true; fragment.onExpressiveDigit(digit, true)
                } else if (event.type == KeyEventType.KeyUp) {
                    pressed = false; fragment.onExpressiveDigit(digit, false)
                }
                true
            } else false
        }.focusable().pointerInput(fragment, digit) {
            detectTapGestures(onPress = {
                pressed = true
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                fragment.onExpressiveDigit(digit, true)
                try { tryAwaitRelease() } finally {
                    pressed = false; fragment.onExpressiveDigit(digit, false)
                }
            })
        }, shape = RoundedCornerShape(radius),
        color = background, contentColor = foreground) {
        if (roundedControls) {
            Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                Text(digit.toString(), style = MaterialTheme.typography.headlineMedium)
            }
        } else {
            val letters = dtmfLetters(digit)
            Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(digit.toString(), style = MaterialTheme.typography.headlineMediumEmphasized)
                Text(
                    letters.ifBlank { " " },
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp,
                    color = if (pressed) foreground.copy(alpha = .85f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
