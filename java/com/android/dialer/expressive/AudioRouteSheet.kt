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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.android.dialer.expressive

import android.telecom.CallAudioState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.dialer.R

@Composable
internal fun AudioRouteSheet(state: CallAudioState, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val routes = listOf(
        Triple(CallAudioState.ROUTE_EARPIECE, DialerSymbol.Phone, R.string.expressive_earpiece),
        Triple(CallAudioState.ROUTE_WIRED_HEADSET, DialerSymbol.Headset, R.string.expressive_headset),
        Triple(CallAudioState.ROUTE_BLUETOOTH, DialerSymbol.Bluetooth, R.string.expressive_bluetooth),
        Triple(CallAudioState.ROUTE_SPEAKER, DialerSymbol.Speaker, R.string.expressive_speaker)
    ).filter { (route, _, _) -> state.supportedRouteMask and route != 0 }
    val haptic = LocalHapticFeedback.current
    DialerModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.expressive_choose_audio), Modifier.padding(bottom = 16.dp),
                style = MaterialTheme.typography.headlineSmallEmphasized)
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                routes.forEachIndexed { index, (route, icon, label) ->
                    val isSelected = state.route == route
                    val container by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.motionScheme.fastEffectsSpec(), label = "audio bg"
                    )
                    val content by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        MaterialTheme.motionScheme.fastEffectsSpec(), label = "audio fg"
                    )
                    val interaction = remember { MutableInteractionSource() }
                    Surface(
                        shape = dialerGroupedShape(index == 0, index == routes.lastIndex),
                        color = container,
                        contentColor = content
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = isSelected,
                                    interactionSource = interaction,
                                    indication = LocalIndication.current,
                                    role = Role.RadioButton,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelect(route)
                                    }
                                )
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Symbol(icon)
                            Text(
                                stringResource(label),
                                Modifier.weight(1f).padding(horizontal = 16.dp),
                                style = if (isSelected) MaterialTheme.typography.titleMediumEmphasized
                                else MaterialTheme.typography.titleMedium
                            )
                            if (isSelected) Symbol(DialerSymbol.Check, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
