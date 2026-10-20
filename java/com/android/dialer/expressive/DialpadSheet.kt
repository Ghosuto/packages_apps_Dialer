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
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.android.dialer.expressive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.dialer.R
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun DialpadSheet(
    people: List<Person>, speedDial: Map<Int, SpeedDialEntry>, digits: String, bottomBackspace: Boolean,
    onDismiss: () -> Unit, onDigits: (String) -> String, onCall: (String) -> Unit,
    onVoicemail: () -> Unit, onAddContact: (String) -> Unit, onSpeedDial: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { keyboard?.hide() }
        var input by remember { mutableStateOf(TextFieldValue(digits, TextRange(digits.length))) }
        LaunchedEffect(digits) {
            if (input.text != digits) input = TextFieldValue(digits, TextRange(digits.length))
        }
        fun updateInput(value: TextFieldValue) {
            val normalized = normalizeDialpadInput(value)
            val accepted = onDigits(normalized.text)
            input = if (accepted == normalized.text) normalized else TextFieldValue(accepted, TextRange(accepted.length))
        }
        val numberMatches = remember(people, digits) {
            if (digits.isBlank()) emptyList() else searchPeople(people, digits).take(3)
        }
        val keypad: @Composable () -> Unit = {
            DialerKeypad(onDigit = { updateInput(replaceDialpadSelection(input, it)) }, onLongDigit = { digit ->
                when (digit) {
                    "0" -> updateInput(replaceDialpadSelection(input, "+"))
                    "1" -> if (input.text.isEmpty()) onVoicemail() else updateInput(replaceDialpadSelection(input, digit))
                    else -> {
                        val key = digit.toInt()
                        val entry = speedDial[key]
                        if (entry == null) onSpeedDial(key) else onCall(entry.number)
                    }
                }
            })
        }
        if (bottomBackspace) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DialpadSuggestions(numberMatches, digits, onCall, onAddContact, grouped = true)
                    DialpadNumberField(input, ::updateInput, inlineBackspace = false)
                    keypad()
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DialpadCallButton(digits, onCall, Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp))
                    DialpadBackspace(input, ::updateInput, Modifier.width(64.dp).fillMaxHeight().heightIn(min = 64.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                DialpadNumberField(input, ::updateInput, inlineBackspace = true)
                DialpadSuggestions(numberMatches, digits, onCall, onAddContact)
                keypad()
                DialpadCallButton(digits, onCall, Modifier.fillMaxWidth().padding(vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun DialpadNumberField(input: TextFieldValue, onInput: (TextFieldValue) -> Unit, inlineBackspace: Boolean) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
                TextField(input, onInput, Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.headlineLargeEmphasized.copy(textAlign = TextAlign.Center, textDirection = TextDirection.Ltr),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, showKeyboardOnFocus = false),
                    placeholder = { Text(stringResource(R.string.expressive_enter_number),
                        Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            }
            if (inlineBackspace) DialpadBackspace(input, onInput, Modifier.width(56.dp))
        }
    }
}

@Composable
private fun DialpadBackspace(input: TextFieldValue, onInput: (TextFieldValue) -> Unit, modifier: Modifier) {
    ExpressiveTile(onClick = { onInput(deleteDialpadSelection(input)) },
        onLongClick = { onInput(TextFieldValue("")) },
        onLongClickLabel = stringResource(R.string.expressive_clear_number),
        enabled = input.text.isNotEmpty(), highlightOnPress = true, modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Symbol(DialerSymbol.Backspace, stringResource(R.string.expressive_backspace))
    }
}

@Composable
private fun DialpadCallButton(digits: String, onCall: (String) -> Unit, modifier: Modifier) {
    ExpressiveTile(onClick = { onCall(digits) }, enabled = digits.isNotBlank(), modifier = modifier,
        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Symbol(DialerSymbol.Phone)
        Text(stringResource(R.string.expressive_call), Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.titleLargeEmphasized)
    }
}

@Composable
private fun DialpadSuggestions(
    matches: List<Person>, digits: String, onCall: (String) -> Unit, onAddContact: (String) -> Unit,
    grouped: Boolean = false
) {
    if (matches.isEmpty()) {
        TextButton(onClick = { onAddContact(digits) }, enabled = digits.isNotBlank()) {
            Text(stringResource(R.string.expressive_create_contact))
        }
    } else if (grouped) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            matches.forEachIndexed { index, match ->
                key(match.id, match.number) {
                    Surface(onClick = { onCall(match.number) },
                        shape = RoundedCornerShape(
                            topStart = if (index == 0) 24.dp else 4.dp,
                            topEnd = if (index == 0) 24.dp else 4.dp,
                            bottomStart = if (index == matches.lastIndex) 24.dp else 4.dp,
                            bottomEnd = if (index == matches.lastIndex) 24.dp else 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(match.name, style = MaterialTheme.typography.titleSmallEmphasized,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(match.number, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Symbol(DialerSymbol.Phone, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            matches.forEach { match ->
                TextButton(onClick = { onCall(match.number) }, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Text("${match.name} · ${match.number}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
