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

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.PhoneLookup
import android.provider.VoicemailContract.Voicemails
import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.android.dialer.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.*

data class VoiceMessage(
    val id: Long,
    val number: String,
    val name: String?,
    val date: Long,
    val duration: Long,
    val transcript: String,
    val available: Boolean
) {
    val uri: Uri get() = ContentUris.withAppendedId(Voicemails.CONTENT_URI, id)
}

private fun lookupContactName(context: Context, number: String): String? {
    if (number.isBlank() || number == "Unknown") return null
    return try {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(
            uri,
            arrayOf(PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun VoicemailScreen(onCallVoicemail: () -> Unit = {}, onSettings: () -> Unit, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<VoiceMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var activeId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf<Long?>(null) }
    var playbackError by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(0) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableFloatStateOf(0f) }

    fun stopPlayback(fullReset: Boolean = true) {
        val previous = player
        player = null
        isPlaying = false
        preparing = null
        if (fullReset) {
            activeId = null
            currentPositionMs = 0
            totalDurationMs = 0
            isUserSeeking = false
            seekPositionMs = 0f
        }
        previous?.setOnPreparedListener(null)
        previous?.setOnCompletionListener(null)
        previous?.setOnErrorListener(null)
        try {
            if (previous?.isPlaying == true) {
                previous.stop()
            }
            previous?.release()
        } catch (_: Exception) {}
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val p = player
            if (p != null && !isUserSeeking) {
                try {
                    if (p.isPlaying) {
                        currentPositionMs = p.currentPosition
                        if (p.duration > 0) {
                            totalDurationMs = p.duration
                        }
                    }
                } catch (_: Exception) {}
            }
            delay(100)
        }
    }

    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopPlayback(fullReset = true)
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            stopPlayback(fullReset = true)
        }
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        context.contentResolver.registerContentObserver(Voicemails.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    LaunchedEffect(revision) {
        try {
            messages = withContext(Dispatchers.IO) {
                buildList {
                    val selection = "${Voicemails.DELETED} = 0 OR ${Voicemails.DELETED} IS NULL"
                    context.contentResolver.query(
                        Voicemails.CONTENT_URI,
                        arrayOf(
                            Voicemails._ID,
                            Voicemails.NUMBER,
                            Voicemails.DATE,
                            Voicemails.DURATION,
                            Voicemails.TRANSCRIPTION,
                            Voicemails.HAS_CONTENT
                        ),
                        selection,
                        null,
                        "${Voicemails.DATE} DESC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val number = c.getString(1).orEmpty()
                            val date = c.getLong(2)
                            val duration = c.getLong(3)
                            val transcript = c.getString(4).orEmpty()
                            val available = c.getInt(5) != 0
                            val name = lookupContactName(context, number)
                            add(VoiceMessage(id, number, name, date, duration, transcript, available))
                        }
                    }
                }
            }
            unavailable = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unavailable = true
        } finally {
            loading = false
        }
    }

    if (loading) {
        ExpressiveLoading(stringResource(R.string.expressive_loading))
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 104.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (messages.isEmpty() || unavailable) item {
            EmptyDialerState(
                DialerSymbol.Voicemail,
                stringResource(R.string.expressive_no_voicemail),
                stringResource(R.string.expressive_voicemail_body),
                stringResource(R.string.expressive_settings),
                onSettings
            )
        }

        items(messages, key = { it.id }) { message ->
            val isCurrentActive = activeId == message.id

            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val title = message.name
                        ?: message.number.takeIf { it.isNotBlank() && it != "Unknown" }
                        ?: stringResource(R.string.expressive_unknown)
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized)

                    val subtitle = buildString {
                        if (message.name != null && message.number.isNotBlank() && message.number != "Unknown") {
                            append(message.number)
                            append(" · ")
                        }
                        append(DateUtils.getRelativeTimeSpanString(message.date))
                        append(" · ")
                        append(DateUtils.formatElapsedTime(message.duration))
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (message.transcript.isNotBlank()) {
                        Text(message.transcript)
                    }

                    AnimatedVisibility(
                        visible = isCurrentActive,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                    ) {
                        val effectiveTotal = if (totalDurationMs > 0) {
                            totalDurationMs
                        } else {
                            (message.duration * 1000).toInt().coerceAtLeast(1000)
                        }
                        val currentPos = if (isUserSeeking) seekPositionMs.toInt() else currentPositionMs

                        Column(Modifier.fillMaxWidth()) {
                            Slider(
                                value = currentPos.coerceIn(0, effectiveTotal).toFloat(),
                                onValueChange = { newPos ->
                                    isUserSeeking = true
                                    seekPositionMs = newPos
                                },
                                onValueChangeFinished = {
                                    val targetMs = seekPositionMs.toInt()
                                    try {
                                        player?.seekTo(targetMs)
                                        currentPositionMs = targetMs
                                    } catch (_: Exception) {}
                                    isUserSeeking = false
                                },
                                valueRange = 0f..effectiveTotal.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    DateUtils.formatElapsedTime((currentPos / 1000).toLong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    DateUtils.formatElapsedTime((effectiveTotal / 1000).toLong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                if (preparing != null) return@FilledTonalButton
                                if (isCurrentActive && player != null) {
                                    if (isPlaying) {
                                        try {
                                            player?.pause()
                                            isPlaying = false
                                        } catch (_: Exception) {
                                            stopPlayback(fullReset = true)
                                        }
                                    } else {
                                        try {
                                            player?.start()
                                            isPlaying = true
                                        } catch (_: Exception) {
                                            stopPlayback(fullReset = true)
                                        }
                                    }
                                } else {
                                    stopPlayback(fullReset = true)
                                    activeId = message.id
                                    preparing = message.id
                                    playbackError = false
                                    totalDurationMs = (message.duration * 1000).toInt().coerceAtLeast(1000)
                                    try {
                                        val next = MediaPlayer().apply {
                                            setAudioAttributes(
                                                AudioAttributes.Builder()
                                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                    .build()
                                            )
                                            setDataSource(context, message.uri)
                                            setOnPreparedListener { mp ->
                                                if (activeId == message.id) {
                                                    mp.start()
                                                    isPlaying = true
                                                    preparing = null
                                                    if (mp.duration > 0) {
                                                        totalDurationMs = mp.duration
                                                    }
                                                }
                                            }
                                            setOnCompletionListener { mp ->
                                                if (player === mp) {
                                                    isPlaying = false
                                                    currentPositionMs = 0
                                                    seekPositionMs = 0f
                                                    try {
                                                        mp.seekTo(0)
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                            setOnErrorListener { failed, _, _ ->
                                                if (player === failed) {
                                                    stopPlayback(fullReset = true)
                                                    playbackError = true
                                                }
                                                true
                                            }
                                            prepareAsync()
                                        }
                                        player = next
                                    } catch (_: Exception) {
                                        stopPlayback(fullReset = true)
                                        playbackError = true
                                    }
                                }
                            },
                            enabled = message.available
                        ) {
                            Symbol(
                                if (isCurrentActive && isPlaying) DialerSymbol.Pause
                                else DialerSymbol.Play
                            )
                            Text(
                                stringResource(
                                    if (isCurrentActive && isPlaying) R.string.expressive_pause
                                    else R.string.expressive_play
                                ),
                                Modifier.padding(start = 8.dp)
                            )
                        }

                        FilledTonalIconButton(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            onClick = {
                                if (isCurrentActive) {
                                    stopPlayback(fullReset = true)
                                }
                                messages = messages.filter { it.id != message.id }
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val values = ContentValues().apply {
                                            put(Voicemails.DELETED, "1")
                                        }
                                        context.contentResolver.update(message.uri, values, null, null)
                                        context.contentResolver.delete(message.uri, null, null)
                                    } catch (_: Exception) {}
                                }
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Symbol(DialerSymbol.Delete, stringResource(R.string.expressive_delete))
                        }
                    }

                    if (!message.available) {
                        Text(
                            stringResource(R.string.expressive_voicemail_download),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (playbackError) item {
            Text(
                stringResource(R.string.expressive_playback_failed),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
