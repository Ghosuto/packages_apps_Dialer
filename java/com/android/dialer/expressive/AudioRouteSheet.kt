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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    )
    DialerModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.expressive_choose_audio), Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.headlineMediumEmphasized)
            routes.filter { (route, _, _) -> state.supportedRouteMask and route != 0 }.forEach { (route, icon, label) ->
                ExpressiveTile(onClick = { onSelect(route) }, selected = state.route == route,
                    modifier = Modifier.fillMaxWidth().semantics { selected = state.route == route }) {
                    Symbol(icon)
                    Text(stringResource(label), Modifier.weight(1f).padding(start = 16.dp),
                        style = if (state.route == route) MaterialTheme.typography.titleMediumEmphasized
                            else MaterialTheme.typography.titleMedium)
                    RadioButton(selected = state.route == route, onClick = null)
                }
            }
        }
    }
}
