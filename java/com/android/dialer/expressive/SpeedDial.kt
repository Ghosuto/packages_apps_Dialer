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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.android.dialer.expressive

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.dialer.R

@Composable
internal fun SpeedDialScreen(
    entries: Map<Int, SpeedDialEntry>,
    people: List<Person>,
    contactsAllowed: Boolean,
    onAssign: (Int, SpeedDialEntry) -> Unit,
    onRemove: (Int) -> Unit,
    onCall: (String) -> Unit
) {
    var assignmentKey by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DialerEmblem(DialerSymbol.Keypad, Modifier.size(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.expressive_speed_dial_hint),
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                    Text(
                        stringResource(R.string.expressive_speed_dial_hint_summary),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .78f)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(SpeedDialStore.SPEED_DIAL_KEYS.toList(), key = { it }) { key ->
                SpeedDialRow(
                    key = key,
                    entry = entries[key],
                    onAssign = { assignmentKey = key },
                    onRemove = { onRemove(key) },
                    onCall = onCall
                )
            }
        }
    }

    assignmentKey?.let { key ->
        SpeedDialAssignmentDialog(
            key = key,
            people = people,
            contactsAllowed = contactsAllowed,
            onDismiss = { assignmentKey = null },
            onAssign = { entry ->
                onAssign(key, entry)
                assignmentKey = null
            }
        )
    }
}

@Composable
private fun SpeedDialRow(
    key: Int,
    entry: SpeedDialEntry?,
    onAssign: () -> Unit,
    onRemove: () -> Unit,
    onCall: (String) -> Unit
) {
    val assigned = entry != null
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (entry == null) onAssign() else onCall(entry.number) },
                    onLongClick = if (entry == null) null else onAssign
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SpeedDialKey(key)
            if (entry != null) {
                ContactAvatar(entry.name, Modifier.size(48.dp), number = entry.number)
            } else {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Symbol(DialerSymbol.Add, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    entry?.name ?: stringResource(R.string.expressive_speed_dial_unassigned),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    entry?.number ?: stringResource(R.string.expressive_speed_dial_assign_summary),
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (assigned) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.semantics {
                        contentDescription = stringResource(R.string.expressive_speed_dial_remove)
                    }
                ) {
                    Symbol(DialerSymbol.Delete)
                }
            } else {
                FilledTonalButton(onClick = onAssign) {
                    Text(stringResource(R.string.expressive_speed_dial_assign))
                }
            }
        }
    }
}

@Composable
private fun SpeedDialKey(key: Int) {
    val letters = when (key) {
        2 -> "ABC"
        3 -> "DEF"
        4 -> "GHI"
        5 -> "JKL"
        6 -> "MNO"
        7 -> "PQRS"
        8 -> "TUV"
        else -> "WXYZ"
    }
    Surface(
        modifier = Modifier.width(54.dp).heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(key.toString(), style = MaterialTheme.typography.titleLargeEmphasized)
            Text(letters, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun SpeedDialAssignmentDialog(
    key: Int,
    people: List<Person>,
    contactsAllowed: Boolean,
    onDismiss: () -> Unit,
    onAssign: (SpeedDialEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(people, query) {
        if (query.isBlank()) people.take(8) else searchPeople(people, query).take(8)
    }
    val number = query.trim()
    val canUseNumber = number.any(Char::isDigit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expressive_speed_dial_assign_title, key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.expressive_speed_dial_contact_or_number)) }
                )
                if (!contactsAllowed) {
                    Text(
                        stringResource(R.string.expressive_speed_dial_contacts_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (matches.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 264.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(matches, key = { "${it.id}:${it.number}" }) { person ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onAssign(SpeedDialEntry(person.name, person.number)) },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ContactAvatar(person.name, Modifier.size(40.dp), number = person.number)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            person.name,
                                            style = MaterialTheme.typography.titleSmallEmphasized,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            person.number,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (canUseNumber) {
                    FilledTonalButton(
                        onClick = { onAssign(SpeedDialEntry(number, number)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.expressive_speed_dial_use_number))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.expressive_cancel)) }
        }
    )
}
