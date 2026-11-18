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

package com.android.dialer.expressive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.dialer.R

// Keep the call actions recognizable across dynamic and monochrome themes.
private val DeclineRed = Color(0xFFE53935)
private val VoicemailIndigo = Color(0xFF5E35B1)

@Composable
internal fun IncomingCallButtons(
    enabled: Boolean,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onVoicemail: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val buttonSize = if (onVoicemail != null) 72.dp else 80.dp
    val spacing = if (onVoicemail != null) 16.dp else 24.dp
    Row(modifier.widthIn(max = 380.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Top) {
        IncomingCallButton(
            label = stringResource(R.string.expressive_decline),
            symbol = DialerSymbol.EndCall,
            color = DeclineRed,
            buttonSize = buttonSize,
            enabled = enabled,
            onClick = onReject,
            modifier = Modifier.weight(1f))
        if (onVoicemail != null) {
            IncomingCallButton(
                label = stringResource(R.string.local_voicemail_title),
                symbol = DialerSymbol.Voicemail,
                color = VoicemailIndigo,
                buttonSize = buttonSize,
                enabled = enabled,
                onClick = onVoicemail,
                modifier = Modifier.weight(1f))
        }
        IncomingCallButton(
            label = stringResource(R.string.expressive_answer),
            symbol = DialerSymbol.Phone,
            color = AnswerGreen,
            buttonSize = buttonSize,
            enabled = enabled,
            onClick = onAnswer,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun IncomingCallButton(
    label: String,
    symbol: DialerSymbol,
    color: Color,
    enabled: Boolean,
    buttonSize: androidx.compose.ui.unit.Dp = 80.dp,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (enabled && pressed) .92f else 1f,
        spring(.8f, 500f), label = "incoming call button press")
    val iconSize = if (buttonSize < 80.dp) 30.dp else 34.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(buttonSize).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Color.White,
                disabledContainerColor = color.copy(alpha = .45f),
                disabledContentColor = Color.White.copy(alpha = .6f)),
            contentPadding = PaddingValues(0.dp),
            interactionSource = interaction
        ) {
            Symbol(symbol, description = label,
                modifier = Modifier.size(iconSize).incomingCallShake(enabled && symbol == DialerSymbol.Phone))
        }
        Text(label, Modifier.padding(top = 12.dp).clearAndSetSemantics {},
            style = if (buttonSize < 80.dp) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .5f),
            textAlign = TextAlign.Center)
    }
}
