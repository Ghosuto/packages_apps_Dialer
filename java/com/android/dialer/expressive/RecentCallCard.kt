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
import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.dialer.R
import com.android.dialer.spam.CarrierCallIdentity
import kotlin.math.abs

@Composable
internal fun RecentCallCard(
    group: RecentCallGroup, expanded: Boolean, onExpand: () -> Unit,
    onActions: () -> Unit, onHistory: () -> Unit, onRecordings: () -> Unit, onCall: () -> Unit,
    onRequestDelete: () -> Unit, onDelete: ((Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp)
) {
    val call = group.latest
    val name = call.displayName()
    val swipe = rememberSpringSwipeState()
    var deleting by remember { mutableStateOf(false) }
    val currentCall by rememberUpdatedState(onCall)
    val currentDelete by rememberUpdatedState(onDelete)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val expandRotation by animateFloatAsState(if (expanded) 180f else 0f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "call details")
    val expandedLabel = stringResource(if (expanded) R.string.expressive_collapse_calls else R.string.expressive_expand_calls)
    val deleteLabel = stringResource(R.string.expressive_delete_history)
    val actionsLabel = stringResource(R.string.expressive_number_actions)
    BoxWithConstraints(modifier.fillMaxWidth().clip(shape)) {
        val width = with(density) { maxWidth.toPx() }
        val threshold = with(density) { (maxWidth * .3f).coerceIn(72.dp, 112.dp).toPx() }
        val direction = swipeDirection(swipe.distance, threshold, positiveEnabled = call.callable)
        LaunchedEffect(direction) {
            if (swipe.dragging && direction != 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        val progress = (abs(swipe.offset) / threshold).coerceIn(0f, 1f)
        val actionScale by animateFloatAsState(if (direction != 0) 1.08f else .9f,
            spring(.7f, 450f), label = "swipe feedback")
        val calling = swipe.offset > 0
        val colors = MaterialTheme.colorScheme
        if (progress > 0f && (!calling || call.callable)) Surface(
            Modifier.matchParentSize(), shape = shape,
            color = if (calling) AnswerGreen else colors.errorContainer,
            contentColor = if (calling) Color.White else colors.onErrorContainer
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Column(Modifier.align(if (calling) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                    .graphicsLayer { alpha = progress; scaleX = actionScale; scaleY = actionScale }
                    .clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    Symbol(if (calling) DialerSymbol.Phone else DialerSymbol.Delete)
                    Text(stringResource(if (calling) R.string.expressive_call else R.string.expressive_delete),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Surface(Modifier.fillMaxWidth()
            .springSwipe(swipe, Orientation.Horizontal, threshold, enabled = !deleting,
                positiveEnabled = call.callable) { action, velocity ->
                when (action) {
                    -1 -> {
                        deleting = true
                        swipe.settle(-width, velocity) {
                            currentDelete { success ->
                                if (!success) { deleting = false; swipe.settle() }
                            }
                        }
                    }
                    1 -> { swipe.settle(velocity = velocity); currentCall() }
                    else -> swipe.settle(velocity = velocity)
                }
            }.graphicsLayer { translationX = swipe.offset }, shape = shape, color = colors.surfaceContainerLow) {
            Column {
                Row(Modifier.fillMaxWidth().semantics {
                    stateDescription = expandedLabel
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onRequestDelete(); true })
                }.combinedClickable(enabled = !deleting, onClickLabel = expandedLabel,
                    onClick = onExpand, onLongClickLabel = actionsLabel, onLongClick = onActions)
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ContactAvatar(name, number = if (call.callable) call.number else "")
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = if (call.type == Calls.MISSED_TYPE) colors.error else colors.onSurface)
                        if (call.spamStatus == CarrierCallIdentity.SPAM_CONFIRMED ||
                            call.spamStatus == CarrierCallIdentity.SPAM_SUSPECTED) {
                            Text(stringResource(if (call.spamStatus == CarrierCallIdentity.SPAM_CONFIRMED)
                                R.string.expressive_confirmed_spam else R.string.expressive_suspected_spam),
                                color = if (call.spamStatus == CarrierCallIdentity.SPAM_CONFIRMED) colors.error else Color(0xFFB26A00),
                                style = MaterialTheme.typography.labelSmall)
                        } else if (call.verificationStatus == CarrierCallIdentity.VERIFICATION_VERIFIED) {
                            Text(stringResource(R.string.expressive_verified_caller), color = Color(0xFF16834B),
                                style = MaterialTheme.typography.labelSmall)
                        } else if (call.spamStatus == CarrierCallIdentity.SPAM_NOT_SPAM) {
                            Text(stringResource(R.string.expressive_not_spam), color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Text(call.typeLabel() + " · " + DateUtils.formatDateTime(LocalContext.current,
                            call.date, DateUtils.FORMAT_SHOW_TIME), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CallSimLabel(call.simNumber)
                            if (group.calls.size > 1) Text(stringResource(R.string.expressive_call_count, group.calls.size),
                                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Symbol(DialerSymbol.Expand, modifier = Modifier.size(20.dp).graphicsLayer {
                        rotationZ = expandRotation
                    })
                    if (call.callable) IconButton(onClick = onCall, enabled = !deleting) {
                        Symbol(DialerSymbol.Phone, stringResource(R.string.expressive_call_person, name))
                    }
                }
                AnimatedVisibility(expanded,
                    enter = expandVertically(spring(.82f, 420f), expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(spring(.85f, 450f), shrinkTowards = Alignment.Top) + fadeOut()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                        HorizontalDivider(color = colors.outlineVariant)
                        if (group.calls.size > 5) Text(stringResource(R.string.expressive_latest_five),
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                        group.preview.forEach { RecentCallDetail(it, showDate = true) }
                        if (call.callable) TextButton(onClick = onHistory, enabled = !deleting,
                            modifier = Modifier.fillMaxWidth()) {
                            Symbol(DialerSymbol.History)
                            Text(stringResource(R.string.expressive_full_history), Modifier.padding(start = 8.dp))
                        }
                        if (call.callable) TextButton(onClick = onRecordings, enabled = !deleting,
                            modifier = Modifier.fillMaxWidth()) {
                            Symbol(DialerSymbol.Record)
                            Text(stringResource(R.string.expressive_call_recordings), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCallDetail(call: RecentCall, showDate: Boolean) {
    val flags = DateUtils.FORMAT_SHOW_TIME or if (showDate)
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR else 0
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(call.typeLabel(), style = MaterialTheme.typography.bodyMedium,
                color = if (call.type == Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(DateUtils.formatDateTime(LocalContext.current, call.date, flags),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CallSimLabel(call.simNumber, Modifier.padding(top = 4.dp))
        }
        if (call.duration > 0) Text(DateUtils.formatElapsedTime(call.duration),
            Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CallSimLabel(simNumber: Int?, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Symbol(DialerSymbol.Sim, modifier = Modifier.size(14.dp))
        Text(if (simNumber != null) stringResource(R.string.expressive_sim_number, simNumber)
            else stringResource(R.string.expressive_sim_unavailable),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* Forget her. She is history. */
@Composable
internal fun NumberCallHistory(selected: RecentCall, calls: List<RecentCall>,
    onDismiss: () -> Unit, onRecordings: () -> Unit) {
    val context = LocalContext.current
    val history = remember(calls, selected.numberKey) { calls.filter { selected.isSameNumber(it) } }
    DialerModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.expressive_full_history), style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(selected.displayName(), style = MaterialTheme.typography.titleMedium)
                    if (selected.name.isNotBlank()) Text(selected.number, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onDismiss) { Symbol(DialerSymbol.Close, stringResource(R.string.expressive_close_history)) }
            }
            TextButton(onClick = onRecordings, modifier = Modifier.padding(horizontal = 16.dp)) {
                Symbol(DialerSymbol.Record)
                Text(stringResource(R.string.expressive_call_recordings), Modifier.padding(start = 8.dp))
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
                if (history.isEmpty()) item {
                    Text(stringResource(R.string.expressive_no_calls_body), Modifier.padding(vertical = 24.dp))
                }
                var previousDay = ""
                history.forEach { call ->
                    val day = DateUtils.formatDateTime(context, call.date,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
                    if (day != previousDay) {
                        item(key = "day_${call.id}") { SectionLabel(day) }
                        previousDay = day
                    }
                    item(key = call.id) { RecentCallDetail(call, showDate = false) }
                }
            }
        }
    }
}
