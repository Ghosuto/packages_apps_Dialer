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

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.preference.PreferenceManager
import com.android.dialer.R
import com.android.dialer.callrecord.CallRecording
import com.android.dialer.callrecord.CallRecordingDataStore
import com.android.dialer.callrecord.CallRecordingNumbers
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.callrecord.impl.CallRecorderService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RecordedCall(val recording: CallRecording, val durationSeconds: Long) {
    val id: Long get() = recording.mediaId
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

internal suspend fun loadNumberRecordings(context: Context, number: String): List<RecordedCall> =
    withContext(Dispatchers.IO) {
        val store = CallRecordingDataStore()
        try {
            store.open(context)
            store.getRecordingsForNumber(number, CallRecordingNumbers.countryIso(context))
                .distinctBy { it.mediaId }.mapNotNull { recording ->
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, recording.mediaId)
                    context.contentResolver.query(uri,
                        arrayOf(MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.IS_PENDING),
                        null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getInt(1) != 0) null
                        else RecordedCall(recording, (cursor.getLong(0) / 1000).coerceAtLeast(0))
                    }
                }
        } finally { store.close() }
    }

@Composable
internal fun NumberCallRecordings(
    name: String, number: String, onDismiss: () -> Unit,
    onAutoRecording: (Boolean, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    val playback = remember(number, context) { RecordingPlayback(context.applicationContext) }
    var revision by remember { mutableIntStateOf(0) }
    var recordings by remember(number) { mutableStateOf<List<RecordedCall>>(emptyList()) }
    var loading by remember(number) { mutableStateOf(true) }
    var loadFailed by remember(number) { mutableStateOf(false) }
    var autoRecording by remember(number) { mutableStateOf<Boolean?>(null) }
    var preferenceFailed by remember(number) { mutableStateOf(false) }
    var saving by remember(number) { mutableStateOf(false) }
    var warningAccepted by remember { mutableStateOf(false) }
    var confirmRecording by remember { mutableStateOf(false) }
    var microphoneGranted by remember { mutableStateOf(false) }
    val supported = CallRecorderService.isEnabled(context)

    fun saveAutoRecording(enabled: Boolean) {
        saving = true
        preferenceFailed = false
        onAutoRecording(enabled) { success ->
            if (success) autoRecording = enabled
            preferenceFailed = !success
            saving = false
            microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
    fun changeAutoRecording(enabled: Boolean) {
        if (enabled && !warningAccepted) confirmRecording = true
        else saveAutoRecording(enabled)
    }

    DisposableEffect(lifecycleOwner, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playback.stop()
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            playback.stop()
        }
    }
    DisposableEffect(context) {
        val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { revision++ }
        }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(CallRecordingDataStore.ACTION_RECORDINGS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            context.contentResolver.unregisterContentObserver(mediaObserver)
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(number, revision, saving) {
        if (saving) return@LaunchedEffect
        microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        try {
            val preference = withContext(Dispatchers.IO) {
                CallRecordingPreferences.isEnabled(context, number) to
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(CallRecordingPreferences.KEY_WARNING_PRESENTED, false)
            }
            autoRecording = preference.first
            warningAccepted = preference.second
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { preferenceFailed = true }
    }
    LaunchedEffect(number, revision) {
        try {
            recordings = loadNumberRecordings(context, number)
            loadFailed = false
            if (playback.mediaId != null && recordings.none { it.id == playback.mediaId }) playback.stop()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadFailed = true }
        finally { loading = false }
    }

    DialerModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.expressive_call_recordings), style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(name.ifBlank { number }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (name.isNotBlank() && name != number) Text(number, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onDismiss) {
                    Symbol(DialerSymbol.Close, stringResource(R.string.expressive_close_recordings))
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "auto_recording") {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        val checked = autoRecording == true
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch,
                                enabled = autoRecording != null && !saving && (supported || checked),
                                onValueChange = { changeAutoRecording(it) }), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(stringResource(R.string.expressive_auto_call_recording), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.expressive_auto_call_recording_body), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked, onCheckedChange = null,
                                    enabled = autoRecording != null && !saving && (supported || checked),
                                    thumbContent = if (checked) ({ Symbol(DialerSymbol.Check, modifier = Modifier.size(16.dp)) }) else null)
                            }
                            if (!supported) Text(stringResource(R.string.expressive_recording_unavailable),
                                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                            if (supported && checked && !microphoneGranted) TextButton(
                                enabled = !saving, onClick = { changeAutoRecording(true) }) {
                                Text(stringResource(R.string.expressive_recording_allow_microphone))
                            }
                            if (preferenceFailed) {
                                Text(stringResource(R.string.expressive_recording_preference_failed),
                                    Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { preferenceFailed = false; revision++ }, enabled = !saving) {
                                    Text(stringResource(R.string.expressive_retry))
                                }
                            }
                        }
                    }
                }
                when {
                    loading -> item { ExpressiveLoading(stringResource(R.string.expressive_loading)) }
                    loadFailed -> item {
                        EmptyDialerState(DialerSymbol.Record, stringResource(R.string.expressive_recordings_load_failed),
                            stringResource(R.string.expressive_try_again), stringResource(R.string.expressive_retry), { revision++ })
                    }
                    recordings.isEmpty() -> item {
                        EmptyDialerState(DialerSymbol.Record, stringResource(R.string.expressive_no_recordings),
                            stringResource(R.string.expressive_no_recordings_body))
                    }
                }
                if (playback.failed) item(key = "playback_error") {
                    Text(stringResource(R.string.expressive_recording_playback_failed), color = MaterialTheme.colorScheme.error)
                }
                if (!loadFailed) items(recordings, key = { it.id }) { entry ->
                    val active = playback.mediaId == entry.id
                    val date = DateUtils.formatDateTime(context, entry.recording.creationTime,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                            DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(date, style = MaterialTheme.typography.titleSmall)
                            Text(entry.recording.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RecordingPlaybackProgress(
                                positionMillis = if (active) playback.positionMillis.toLong() else 0L,
                                durationMillis = if (active && !playback.preparing) playback.durationMillis.toLong()
                                    else entry.durationSeconds * 1000L,
                                preparing = active && playback.preparing)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton(onClick = {
                                    if (active) playback.stop() else playback.play(entry.id, entry.uri)
                                }) {
                                    Symbol(if (active) DialerSymbol.Stop else DialerSymbol.Play)
                                    Text(stringResource(if (active) R.string.expressive_stop else R.string.expressive_play),
                                        Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmRecording) AlertDialog(onDismissRequest = { confirmRecording = false },
        title = { Text(stringResource(R.string.recording_warning_title)) },
        text = { Text(stringResource(R.string.recording_warning_text)) },
        confirmButton = {
            TextButton(onClick = {
                confirmRecording = false
                warningAccepted = true
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean(CallRecordingPreferences.KEY_WARNING_PRESENTED, true).apply()
                saveAutoRecording(true)
            }) { Text(stringResource(R.string.expressive_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { confirmRecording = false }) { Text(stringResource(R.string.expressive_cancel)) }
        })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecordingPlaybackProgress(positionMillis: Long, durationMillis: Long, preparing: Boolean) {
    val duration = durationMillis.coerceAtLeast(0L)
    val position = positionMillis.coerceIn(0L, duration)
    val fraction = if (duration > 0L) position.toFloat() / duration else 0f
    val progress by animateFloatAsState(fraction, tween(250, easing = LinearEasing), label = "recording progress")
    val elapsed = DateUtils.formatElapsedTime(position / 1000L)
    val total = DateUtils.formatElapsedTime(duration / 1000L)
    val description = stringResource(R.string.expressive_recording_progress, elapsed, total)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = description
        progressBarRangeInfo = if (preparing) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f)
    }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(elapsed, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        if (preparing) LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
        else LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
        Text(total, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
