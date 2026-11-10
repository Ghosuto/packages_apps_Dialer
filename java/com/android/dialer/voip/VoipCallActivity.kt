/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.android.dialer.voip

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.android.dialer.R
import com.android.dialer.expressive.DialerExpressiveTheme
import com.android.dialer.expressive.DialerSymbol
import com.android.dialer.expressive.Symbol
import kotlinx.coroutines.delay

class VoipCallActivity : FragmentActivity() {
    private var recordingEnabled by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        if (!VoipCalls.enabled(this)) { finish(); return }
        recordingEnabled = VoipCalls.recordingEnabled(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(VoipNotificationListener.EXTRA_TOKEN)
        if (token == null || !VoipCalls.enabled(this)) { finish(); return }
        setShowWhenLocked(true)
        setTurnScreenOn(VoipCalls.find(token)?.incoming == true)
        setContent {
            DialerExpressiveTheme { CallScreen(token) }
        }
    }

    @Composable
    private fun CallScreen(token: String) {
        val calls by VoipCalls.calls.collectAsState()
        val call = calls.firstOrNull { it.token == token }
        val recording by VoipRecordingService.state.collectAsState()
        val ownRecording = recording.takeIf { it.token == token }
        val permissionRequest = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.isNotEmpty() && granted.values.all { it }) VoipRecordingService.start(this, token)
        }
        var elapsed by remember { mutableStateOf(0L) }
        LaunchedEffect(ownRecording?.busy, ownRecording?.started, ownRecording?.saving) {
            while (ownRecording?.busy == true && ownRecording.started > 0 && !ownRecording.saving) {
                elapsed = (SystemClock.elapsedRealtime() - ownRecording.started) / 1000
                delay(1000)
            }
        }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    FilledTonalIconButton(onClick = { finish() }) {
                        Symbol(DialerSymbol.Back, stringResource(R.string.expressive_back))
                    }
                }
                Text(call?.appName ?: stringResource(R.string.voip_calls_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary)
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(120.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Symbol(DialerSymbol.Person, modifier = Modifier.size(56.dp))
                    }
                }
                Text(call?.caller ?: stringResource(R.string.voip_call_ended),
                    style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                if (call != null) {
                    Text(stringResource(if (call.incoming) R.string.voip_incoming else R.string.voip_app_call),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    call.actions.forEach { action ->
                        Button(onClick = { sendAction(call.token, action) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(action.title)
                        }
                    }
                    OutlinedButton(onClick = { openApp(call) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.voip_open_app, call.appName))
                    }
                    if (recordingEnabled && !call.incoming) {
                        FilledTonalButton(
                            onClick = {
                                if (ownRecording?.busy == true) VoipRecordingService.stop(this@VoipCallActivity)
                                else {
                                    val missing = arrayOf(Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.POST_NOTIFICATIONS).filter {
                                        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    if (missing.isEmpty()) VoipRecordingService.start(this@VoipCallActivity, token)
                                    else permissionRequest.launch(missing.toTypedArray())
                                }
                            },
                            enabled = ownRecording?.saving != true &&
                                (ownRecording?.busy == true || (!recording.busy && calls.size == 1)),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Symbol(if (ownRecording?.busy == true) DialerSymbol.Stop else DialerSymbol.Record)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(when {
                                ownRecording?.saving == true -> R.string.voip_recording_saving
                                ownRecording?.busy == true -> R.string.voip_stop_recording
                                else -> R.string.voip_start_recording
                            }))
                        }
                    }
                }
                if (ownRecording?.busy == true) {
                    if (ownRecording.saving || ownRecording.started == 0L) {
                        Text(stringResource(if (ownRecording.saving) R.string.voip_recording_saving
                            else R.string.voip_recording_preparing))
                    } else Text(stringResource(R.string.voip_recording_duration, DateUtils.formatElapsedTime(elapsed)),
                        color = MaterialTheme.colorScheme.error)
                    if (!ownRecording.remoteAudioDetected && !ownRecording.saving) Text(stringResource(R.string.voip_waiting_audio),
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    if (call == null && !ownRecording.saving) TextButton(onClick = { VoipRecordingService.stop(this@VoipCallActivity) }) {
                        Text(stringResource(R.string.voip_stop_recording))
                    }
                } else if (ownRecording != null && ownRecording.message != 0) {
                    Text(stringResource(ownRecording.message), textAlign = TextAlign.Center)
                }
                TextButton(onClick = {
                    startActivity(Intent(this@VoipCallActivity, VoipRecordingsActivity::class.java))
                }) { Text(stringResource(R.string.voip_recordings_title)) }
            }
        }
    }

    private fun sendAction(token: String, action: VoipAction) {
        val current = VoipCalls.find(token) ?: return
        if (!current.actions.contains(action)) return
        try { sendIntent(action.intent) }
        catch (_: PendingIntent.CanceledException) {
            Toast.makeText(this, R.string.voip_action_failed, Toast.LENGTH_LONG).show()
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.voip_action_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openApp(call: VoipCall) {
        try {
            if (VoipCalls.find(call.token) == null) return
            if (call.contentIntent != null) sendIntent(call.contentIntent)
            else packageManager.getLaunchIntentForPackage(call.packageName)?.let { startActivity(it) }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.voip_action_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendIntent(intent: PendingIntent) {
        val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        intent.send(this, 0, null, null, null, null, options.toBundle())
    }
}
