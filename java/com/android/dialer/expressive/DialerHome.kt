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

import android.provider.CallLog.Calls
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.android.dialer.R

@Composable
fun DialerHome(
    data: DialerData, loading: Boolean, error: Boolean, tab: Int, query: String, digits: String,
    keypad: Boolean, compactFloatingBar: Boolean = false, hideKeyboardOnScroll: Boolean = true,
    groupContactsByName: Boolean = true, bottomDialpadBackspace: Boolean = true,
    onTab: (Int) -> Unit, onQuery: (String) -> Unit, onDigits: (String) -> String,
    onKeypad: (Boolean) -> Unit, onCall: (String) -> Unit, onVideo: (String) -> Unit, onVoicemail: () -> Unit,
    onPermissions: () -> Unit, onRetry: () -> Unit, onStar: (Person) -> Unit,
    speedDial: Map<Int, SpeedDialEntry>, onAssignSpeedDial: (Int, SpeedDialEntry) -> Unit,
    onRemoveSpeedDial: (Int) -> Unit, onSpeedDial: (Int) -> Unit,
    onDelete: (List<RecentCall>, (Boolean) -> Unit) -> Unit,
    onAddContact: (String) -> Unit, onMessage: (String) -> Unit, onWhatsApp: (String) -> Unit,
    onSettings: () -> Unit,
    onCopy: (String) -> Unit, onShare: (String) -> Unit, onEditContact: (Person) -> Unit,
    onPhoto: (String) -> Unit, onRemovePhoto: (String) -> Unit,
    onAutoRecording: (String, Boolean, (Boolean) -> Unit) -> Unit
) {
    val labels = listOf(
        stringResource(R.string.expressive_recents),
        stringResource(R.string.expressive_speed_dial),
        stringResource(R.string.expressive_favorites),
        stringResource(R.string.expressive_contacts),
        stringResource(R.string.expressive_voicemail)
    )
    var person by remember { mutableStateOf<Person?>(null) }
    var recent by remember { mutableStateOf<RecentCallGroup?>(null) }
    var delete by remember { mutableStateOf<RecentCallGroup?>(null) }
    var history by remember { mutableStateOf<RecentCall?>(null) }
    var recordingNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingName by rememberSaveable { mutableStateOf("") }
    fun showRecordings(name: String, number: String) {
        person = null
        recent = null
        history = null
        recordingName = name
        recordingNumber = number
    }
    var expandedGroup by rememberSaveable { mutableStateOf<Long?>(null) }
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    val groups = remember(data.recentGroups, query, missedOnly) {
        data.recentGroups.filter { group ->
            group.calls.any { (!missedOnly || it.type == Calls.MISSED_TYPE) && matches(it.name, it.number, query) }
        }
    }
    val recentContactMatches = remember(data.people, data.contactsAllowed, query, missedOnly) {
        if (query.isBlank() || missedOnly || !data.contactsAllowed) emptyList() else searchPeople(data.people, query)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp), Alignment.Center) {
                FloatingDialerBar(tab, labels, onTab, compactFloatingBar)
            }
        }, floatingActionButton = {
            ExpressiveTile(onClick = { onKeypad(true) }, modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) {
                Symbol(DialerSymbol.Keypad, stringResource(R.string.expressive_keypad), Modifier.size(28.dp))
            }
        }) { padding ->
        val direction = LocalLayoutDirection.current
        val contentInsets = PaddingValues(start = padding.calculateStartPadding(direction),
            top = padding.calculateTopPadding(), end = padding.calculateEndPadding(direction))
        val bottomClearance = padding.calculateBottomPadding()
        val keyboard = LocalSoftwareKeyboardController.current
        val keyboardScrollConnection = remember(hideKeyboardOnScroll, keyboard) {
            if (!hideKeyboardOnScroll) null else object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) {
                        keyboard?.hide()
                    }
                    return Offset.Zero
                }
            }
        }
        Column(Modifier.padding(contentInsets).consumeWindowInsets(padding).fillMaxSize().widthIn(max = 840.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.expressive_phone), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedContent(labels[tab], label = "page title") {
                        Text(it, Modifier.semantics { heading() },
                            style = MaterialTheme.typography.displaySmallEmphasized,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                FilledTonalIconButton(onClick = onSettings) { Symbol(DialerSymbol.Settings, stringResource(R.string.expressive_settings)) }
            }
            if (tab != 1 && tab != 4) {
                TextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 60.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    placeholder = { Text(stringResource(R.string.expressive_search)) },
                    leadingIcon = { Symbol(DialerSymbol.Search) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                        Symbol(DialerSymbol.Close, stringResource(R.string.expressive_clear))
                    } }, singleLine = true, shape = CircleShape,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            }
            AnimatedContent(tab, Modifier.weight(1f), transitionSpec = {
                (fadeIn(tween(180)) + slideInHorizontally(spring(.85f, 450f)) { if (targetState > initialState) it / 8 else -it / 8 }) togetherWith
                    (fadeOut(tween(100)) + slideOutHorizontally { if (targetState > initialState) -it / 8 else it / 8 })
            }, label = "page") { page ->
                val contacts = remember(data.people, query, page) {
                    searchPeople(if (page == 2) data.people.filter { it.starred } else data.people, query)
                }
                val displayedContacts = if (page == 2) contacts.distinctBy { it.id } else contacts
                val contactNameGroups = remember(contacts, page, groupContactsByName) {
                    if (page == 3 && groupContactsByName) groupContactsByDisplayName(contacts) else emptyList()
                }
                when {
                    loading -> ExpressiveLoading(stringResource(R.string.expressive_loading))
                    error -> EmptyDialerState(DialerSymbol.History, stringResource(R.string.expressive_load_failed),
                        stringResource(R.string.expressive_try_again), stringResource(R.string.expressive_retry), onRetry)
                    page == 4 -> VoicemailScreen(onVoicemail, onSettings, bottomInset = bottomClearance)
                    page == 0 && !data.callsAllowed || page in 2..3 && !data.contactsAllowed ->
                        EmptyDialerState(DialerSymbol.People, stringResource(R.string.expressive_permission_title),
                            stringResource(R.string.expressive_permission_body), stringResource(R.string.expressive_continue), onPermissions)
                    page == 1 -> SpeedDialScreen(
                        entries = speedDial,
                        people = data.people,
                        contactsAllowed = data.contactsAllowed,
                        onAssign = onAssignSpeedDial,
                        onRemove = onRemoveSpeedDial,
                        onCall = onCall
                    )
                    else -> LazyColumn(Modifier.fillMaxSize().then(
                        keyboardScrollConnection?.let { Modifier.nestedScroll(it) } ?: Modifier
                    ), contentPadding = PaddingValues(
                        start = 20.dp, end = 20.dp, top = 16.dp, bottom = 104.dp + bottomClearance),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (page == 0) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(!missedOnly, { missedOnly = false }, { Text(stringResource(R.string.expressive_all)) })
                                    FilterChip(missedOnly, { missedOnly = true }, { Text(stringResource(R.string.expressive_missed)) })
                                }
                            }
                            if (groups.isEmpty() && recentContactMatches.isEmpty()) item {
                                EmptyDialerState(DialerSymbol.History, stringResource(R.string.expressive_no_calls),
                                    stringResource(if (query.isBlank()) R.string.expressive_no_calls_body else R.string.expressive_no_results))
                            }
                            val now = System.currentTimeMillis()
                            val days = groups.map { group ->
                                DateUtils.getRelativeTimeSpanString(group.latest.date, now, DateUtils.DAY_IN_MILLIS).toString()
                            }
                            groups.forEachIndexed { index, group ->
                                val call = group.latest
                                val first = index == 0 || days[index - 1] != days[index]
                                val last = index == groups.lastIndex || days[index + 1] != days[index]
                                if (first) {
                                    item(key = "day_${call.id}") { SectionLabel(days[index]) }
                                }
                                item(key = group.id) {
                                    RecentCallCard(group, expanded = expandedGroup == group.id,
                                        onExpand = { expandedGroup = if (expandedGroup == group.id) null else group.id },
                                        onActions = { recent = group }, onHistory = { history = call },
                                        onRecordings = { showRecordings(call.name.ifBlank { call.number }, call.number) },
                                        onCall = { onCall(call.number) }, onRequestDelete = { delete = group },
                                        onDelete = { completed -> onDelete(group.calls, completed) },
                                        shape = dialerGroupedShape(first, last),
                                        modifier = Modifier.animateItem(placementSpec = null))
                                }
                            }
                            if (recentContactMatches.isNotEmpty()) {
                                item(key = "search_contacts_label") { SectionLabel(stringResource(R.string.expressive_contacts)) }
                                items(recentContactMatches, key = { "search_contact_${it.id}:${it.number}" }) { contact ->
                                    ContactRow(contact, onClick = { person = contact }, onCall = { onCall(contact.number) },
                                        modifier = Modifier.animateItem())
                                }
                            }
                        } else {
                            if (page == 3) item {
                                TextButton(onClick = { onAddContact("") }) {
                                    Symbol(DialerSymbol.Add); Text(stringResource(R.string.expressive_create_contact), Modifier.padding(start = 12.dp))
                                }
                            }
                            if (page == 3 && query.isBlank()) item {
                                Text(stringResource(R.string.expressive_photo_hint), Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (displayedContacts.isEmpty()) item {
                                EmptyDialerState(if (page == 2) DialerSymbol.Star else DialerSymbol.People,
                                    stringResource(if (page == 2) R.string.expressive_no_favorites else R.string.expressive_no_contacts),
                                    stringResource(if (query.isNotBlank()) R.string.expressive_no_results else if (page == 2) R.string.expressive_favorites_body else R.string.expressive_contacts_body))
                            }
                            if (page == 2) {
                                items(displayedContacts.chunked(2), key = { row -> "favorite_${row.first().id}" }) { row ->
                                    Row(Modifier.fillMaxWidth().animateItem().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { contact ->
                                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(32.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                                Column(Modifier.fillMaxWidth().combinedClickable(
                                                    onClick = { person = contact }, onLongClick = { person = contact }).padding(16.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    ContactAvatar(contact.name, Modifier.size(64.dp), number = contact.number)
                                                    Text(contact.name, style = MaterialTheme.typography.titleMediumEmphasized,
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    FilledTonalIconButton(onClick = { onCall(contact.number) }) {
                                                        Symbol(DialerSymbol.Phone, stringResource(R.string.expressive_call_person, contact.name))
                                                    }
                                                }
                                            }
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            } else if (page == 3 && groupContactsByName) {
                                itemsIndexed(contactNameGroups, key = { index, group ->
                                    if (group.name.isNotBlank()) "contact_name:${group.name}" else "unnamed_contact:$index"
                                }) { index, group ->
                                    Surface(modifier = Modifier.animateItem(),
                                        shape = dialerGroupedShape(index == 0, index == contactNameGroups.lastIndex),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                ContactAvatar(group.name, number = group.people.first().number)
                                                Text(group.name, Modifier.weight(1f).padding(horizontal = 12.dp),
                                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            group.people.forEach { contact ->
                                                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { person = contact },
                                                    onLongClick = { person = contact }).padding(start = 14.dp, end = 14.dp),
                                                    verticalAlignment = Alignment.CenterVertically) {
                                                    Text(contact.number, Modifier.weight(1f).padding(horizontal = 12.dp),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    IconButton(onClick = { onCall(contact.number) }) {
                                                        Symbol(DialerSymbol.Phone, stringResource(R.string.expressive_call_person, group.name))
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }
                                }
                            } else itemsIndexed(contacts, key = { _, contact -> "${contact.id}:${contact.number}" }) { index, contact ->
                                ContactRow(contact, onClick = { person = contact }, onCall = { onCall(contact.number) },
                                    shape = dialerGroupedShape(index == 0, index == contacts.lastIndex),
                                    modifier = Modifier.animateItem())
                            }
                        }
                    }
                }
            }
        }
    }
    if (keypad) DialpadSheet(data.people, speedDial, digits, bottomDialpadBackspace,
        onDismiss = { onKeypad(false) }, onDigits = onDigits, onCall = onCall,
        onVoicemail = onVoicemail, onAddContact = onAddContact, onSpeedDial = onSpeedDial)
    person?.let { selected ->
        val updated = data.people.firstOrNull { it.id == selected.id && it.number == selected.number } ?: selected
        NumberActionsDialog(updated.name, updated.number, updated.starred, true,
            onDismiss = { person = null }, onCall = { person = null; onCall(updated.number) },
            onVideo = { person = null; onVideo(updated.number) }, onMessage = { person = null; onMessage(updated.number) },
            onWhatsApp = { person = null; onWhatsApp(updated.number) },
            onFavorite = { onStar(updated) }, onCopy = { onCopy(updated.number); person = null },
            onShare = { person = null; onShare(updated.number) }, onEdit = { person = null; onEditContact(updated) },
            onPhoto = { onPhoto(updated.number) }, onRemovePhoto = { onRemovePhoto(updated.number) },
            onRecordings = { showRecordings(updated.name, updated.number) })
    }
    history?.let { selected -> NumberCallHistory(selected, data.calls, onDismiss = { history = null },
        onRecordings = { showRecordings(selected.name.ifBlank { selected.number }, selected.number) }) }
    recordingNumber?.let { number -> key(number) {
        NumberCallRecordings(recordingName, number, onDismiss = { recordingNumber = null },
            onAutoRecording = { enabled, completed -> onAutoRecording(number, enabled, completed) })
    } }
    recent?.let { selectedGroup ->
        val selected = selectedGroup.latest
        val contact = data.people.firstOrNull { PhoneNumberUtils.compare(it.number, selected.number) }
        if (selected.callable) NumberActionsDialog(selected.displayName(), selected.number, contact?.starred == true, contact != null,
            onDismiss = { recent = null }, onCall = { recent = null; onCall(selected.number) },
            onVideo = { recent = null; onVideo(selected.number) }, onMessage = { recent = null; onMessage(selected.number) },
            onWhatsApp = { recent = null; onWhatsApp(selected.number) },
            onFavorite = { if (contact != null) onStar(contact) else { recent = null; onAddContact(selected.number) } },
            onCopy = { onCopy(selected.number); recent = null }, onShare = { recent = null; onShare(selected.number) },
            onEdit = { recent = null; if (contact != null) onEditContact(contact) else onAddContact(selected.number) },
            onPhoto = { onPhoto(selected.number) }, onRemovePhoto = { onRemovePhoto(selected.number) },
            onRecordings = { showRecordings(selected.name.ifBlank { selected.number }, selected.number) },
            onDelete = { delete = selectedGroup; recent = null })
        else AlertDialog(onDismissRequest = { recent = null }, title = { Text(selected.displayName()) },
            text = { Text(selected.typeLabel()) }, confirmButton = {
                TextButton(onClick = { delete = selectedGroup; recent = null }) { Text(stringResource(R.string.expressive_delete_history)) }
            }, dismissButton = { TextButton(onClick = { recent = null }) { Text(stringResource(R.string.expressive_cancel)) } })
    }
    delete?.let { selected -> AlertDialog(onDismissRequest = { delete = null },
        title = { Text(stringResource(R.string.expressive_delete_call)) },
        text = { Text(stringResource(R.string.expressive_delete_group_body)) },
        confirmButton = { TextButton(onClick = { onDelete(selected.calls) {}; delete = null }) { Text(stringResource(R.string.expressive_delete)) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.expressive_cancel)) } }) }
}

@Composable
private fun ContactRow(
    contact: Person, onClick: () -> Unit, onCall: () -> Unit,
    shape: Shape = RoundedCornerShape(24.dp), modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(contact.name, number = contact.number)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(contact.name, style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contact.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onCall) {
                Symbol(DialerSymbol.Phone, stringResource(R.string.expressive_call_person, contact.name))
            }
        }
    }
}

@Composable
internal fun RecentCall.displayName(): String = when (presentation) {
    Calls.PRESENTATION_RESTRICTED -> stringResource(R.string.expressive_private_number)
    Calls.PRESENTATION_PAYPHONE -> stringResource(R.string.expressive_payphone)
    Calls.PRESENTATION_UNKNOWN, Calls.PRESENTATION_UNAVAILABLE -> stringResource(R.string.expressive_unknown)
    else -> name.ifBlank { number.ifBlank { stringResource(R.string.expressive_unknown) } }
}
@Composable
internal fun RecentCall.typeLabel(): String = stringResource(when (type) {
    Calls.OUTGOING_TYPE -> R.string.expressive_outgoing
    Calls.MISSED_TYPE -> R.string.expressive_missed
    Calls.REJECTED_TYPE -> R.string.expressive_declined
    Calls.BLOCKED_TYPE -> R.string.expressive_blocked
    else -> R.string.expressive_incoming
})
@Composable
fun SectionLabel(text: String) = Text(text,
    Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
@Composable
fun EmptyDialerState(icon: DialerSymbol, title: String, body: String, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DialerEmblem(icon, Modifier.size(88.dp))
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) FilledTonalButton(onClick) { Text(action) }
    }
}
@Composable
fun DetailHeader(name: String, number: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ContactAvatar(name, large = true, number = number)
        Text(name, style = MaterialTheme.typography.headlineLargeEmphasized, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (number.isNotBlank() && number != name) Text(number, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun DetailAction(icon: DialerSymbol, title: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    ExpressiveTile(onClick, modifier, selected) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(icon); Text(title, style = if (selected) MaterialTheme.typography.labelMediumEmphasized
                else MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
@Composable
fun DialerKeypad(onDigit: (String) -> Unit, onLongDigit: (String) -> Unit = onDigit) {
    val tones = rememberDialpadTones()
    val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (digit, letters) ->
                    ExpressiveTile(onClick = { tones.play(digit); onDigit(digit) },
                        onLongClick = { tones.play(digit); onLongDigit(digit) },
                        highlightOnPress = true,
                        modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(digit, style = MaterialTheme.typography.headlineLargeEmphasized)
                            Text(letters.ifBlank { " " }, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                        }
                    }
                }
            }
        }
    }
}
