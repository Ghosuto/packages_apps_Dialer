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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.dialer.R

@Composable
internal fun SpeedDialScreen(
    entries: Map<Int, SpeedDialEntry>,
    people: List<Person>,
    contactsAllowed: Boolean,
    onAssign: (Int, SpeedDialEntry) -> Unit,
    onRemove: (Int) -> Unit,
    onCall: (String) -> Unit,
    bottomInset: Dp = 0.dp
) {
    var assignmentKey by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DialerEmblem(DialerSymbol.Keypad, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.expressive_speed_dial_hint),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.expressive_speed_dial_hint_summary),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.expressive_speed_dial_shortcuts)) }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = dialerGroupedShape(
            first = key == SpeedDialStore.SPEED_DIAL_KEYS.first,
            last = key == SpeedDialStore.SPEED_DIAL_KEYS.last
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (entry == null) onAssign() else onCall(entry.number) },
                    onLongClick = if (entry == null) null else onAssign,
                    onLongClickLabel = if (assigned) stringResource(R.string.expressive_speed_dial_change) else null
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (assigned) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (assigned) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                SpeedDialKey(key)
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
                val removeDescription = stringResource(R.string.expressive_speed_dial_remove)
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.semantics {
                        contentDescription = removeDescription
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(key.toString(), style = MaterialTheme.typography.titleMediumEmphasized)
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
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(matches, key = { _, it -> "${it.id}:${it.number}" }) { index, person ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onAssign(SpeedDialEntry(person.name, person.number)) },
                                shape = dialerGroupedShape(index == 0, index == matches.lastIndex),
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
