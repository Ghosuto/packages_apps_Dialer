/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.android.dialer.voip

import android.content.ContentUris
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.dialer.R
import com.android.dialer.expressive.DialerExpressiveTheme
import com.android.dialer.expressive.DialerSymbol
import com.android.dialer.expressive.RecordingPlayback
import com.android.dialer.expressive.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoipRecordingsActivity : FragmentActivity() {
    private data class Recording(val id: Long, val title: String, val duration: Long)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DialerExpressiveTheme { RecordingsScreen() } }
    }

    @Composable
    private fun RecordingsScreen() {
        var revision by remember { mutableStateOf(0) }
        var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        val playback = remember { RecordingPlayback(applicationContext) }
        DisposableEffect(Unit) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { revision++ }
            }
            val lifecycleObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) playback.stop()
                if (event == Lifecycle.Event.ON_RESUME) revision++
            }
            contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            lifecycle.addObserver(lifecycleObserver)
            onDispose {
                playback.stop()
                contentResolver.unregisterContentObserver(observer)
                lifecycle.removeObserver(lifecycleObserver)
            }
        }
        LaunchedEffect(revision) {
            loading = true
            failed = false
            try {
                recordings = withContext(Dispatchers.IO) {
                    buildList {
                        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                                MediaStore.Audio.Media.DURATION),
                            "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND " +
                                "${MediaStore.Audio.Media.OWNER_PACKAGE_NAME} = ? AND " +
                                "${MediaStore.Audio.Media.IS_PENDING} = 0",
                            arrayOf(VoipAudioRecorder.RECORDINGS_PATH, packageName),
                            "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                            while (cursor.moveToNext()) add(Recording(cursor.getLong(0),
                                cursor.getString(1).orEmpty(), cursor.getLong(2)))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.voip_recordings_title)) },
                navigationIcon = {
                    IconButton(onClick = { finish() }) {
                        Symbol(DialerSymbol.Back, stringResource(R.string.expressive_back))
                    }
                })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.voip_recordings_summary)) }
                if (loading) item { CircularProgressIndicator() }
                if (failed || playback.failed) item { Text(stringResource(R.string.voip_playback_failed)) }
                if (!loading && !failed && recordings.isEmpty()) {
                    item { Text(stringResource(R.string.voip_no_recordings)) }
                }
                items(recordings, key = { it.id }) { recording ->
                    val playing = playback.mediaId == recording.id
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(recording.title, style = MaterialTheme.typography.titleMedium)
                                Text(DateUtils.formatElapsedTime(recording.duration / 1000),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            FilledTonalIconButton(onClick = {
                                if (playing) playback.stop()
                                else playback.play(recording.id, ContentUris.withAppendedId(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, recording.id))
                            }) {
                                Symbol(if (playing) DialerSymbol.Stop else DialerSymbol.Play,
                                    stringResource(if (playing) R.string.voip_stop_playback else R.string.voip_play))
                            }
                        }
                    }
                }
            }
        }
    }
}
