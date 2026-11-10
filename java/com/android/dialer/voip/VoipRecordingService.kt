/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import com.android.dialer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class VoipRecordingState(
    val token: String? = null,
    val busy: Boolean = false,
    val started: Long = 0,
    val remoteAudioDetected: Boolean = false,
    val message: Int = 0,
    val savedUri: Uri? = null,
    val saving: Boolean = false,
)

class VoipRecordingService : Service() {
    private var recorder: VoipAudioRecorder? = null
    private var destroyed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.voip_recording_title),
                NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (intent.getStringExtra(VoipNotificationListener.EXTRA_TOKEN) == state.value.token) {
                recorder?.stop()
            }
            if (recorder == null) stopSelf()
            return START_NOT_STICKY
        }
        if (recorder != null) return START_NOT_STICKY
        if (state.value.busy) { stopSelf(); return START_NOT_STICKY }
        val token = intent?.getStringExtra(VoipNotificationListener.EXTRA_TOKEN)
        val call = VoipCalls.find(token)
        if (call == null || !canRecord(this, call)) {
            mutableState.value = VoipRecordingState(token = token, message = R.string.voip_cannot_record)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val stopIntent = PendingIntent.getService(this, 0,
                Intent(this, VoipRecordingService::class.java).setAction(STOP)
                    .setData(Uri.parse("dialer-voip:stop/${call.token}"))
                    .putExtra(VoipNotificationListener.EXTRA_TOKEN, call.token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_ms_fiber_manual_record_24)
                .setContentTitle(getString(R.string.voip_recording_app, call.appName))
                .setContentText(getString(R.string.voip_recording_preparing))
                .setContentIntent(VoipNotificationListener.screenIntent(this, call.token))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.voip_stop_recording),
                    stopIntent).build()).build()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dialer:VoipRecording").apply {
                    acquire((4 * 60 + 10) * 60 * 1000L)
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot start foreground recording", e)
            mutableState.value = VoipRecordingState(token = token, message = R.string.voip_cannot_record)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val current = VoipAudioRecorder(this)
        recorder = current
        mutableState.value = VoipRecordingState(call.token, busy = true)
        Thread({
            val result = try {
                val uri = current.record(call, { canRecord(this, call) }, onStarted = {
                    mutableState.update { it.copy(started = SystemClock.elapsedRealtime()) }
                    updateNotification(saving = false)
                }, onSaving = {
                    mutableState.update { it.copy(saving = true) }
                    updateNotification(saving = true)
                }, progress = { detected ->
                    mutableState.update { it.copy(remoteAudioDetected = detected) }
                })
                VoipRecordingState(call.token, message = R.string.voip_recording_saved, savedUri = uri)
            } catch (e: Exception) {
                Log.w(TAG, "VoIP recording failed", e)
                VoipRecordingState(call.token, message = if (e is VoipAudioRecorder.NoRemoteAudioException)
                    R.string.voip_no_remote_audio else R.string.voip_recording_failed)
            }
            main.post {
                recorder = null
                mutableState.value = result
                try {
                    if (!destroyed) showResult(call, result)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Cannot show recording result", e)
                } finally {
                    releaseWakeLock()
                    if (!destroyed) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }, "VoipRecording").start()
        return START_NOT_STICKY
    }

    private fun updateNotification(saving: Boolean) {
        main.post {
            if (destroyed || recorder == null) return@post
            try {
                val manager = getSystemService(NotificationManager::class.java)
                val previous = manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }
                    ?: return@post
                val builder = Notification.Builder.recoverBuilder(this, previous.notification)
                    .setContentText(getString(if (saving) R.string.voip_recording_saving
                        else R.string.voip_recording_in_progress))
                    .setUsesChronometer(!saving)
                    .setWhen(System.currentTimeMillis())
                if (saving) builder.setActions()
                manager.notify(NOTIFICATION_ID, builder.build())
            } catch (e: RuntimeException) { Log.w(TAG, "Cannot update recording notification", e) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun showResult(call: VoipCall, result: VoipRecordingState) {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, VoipRecordingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(RESULT_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_ms_fiber_manual_record_24)
                .setContentTitle(getString(result.message))
                .setContentText(call.appName)
                .setContentIntent(open).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
    }

    override fun onDestroy() {
        destroyed = true
        recorder?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoipRecorder"
        private const val CHANNEL = "voip_recordings"
        private const val NOTIFICATION_ID = 4101
        private const val RESULT_ID = 4102
        private const val STOP = "com.android.dialer.voip.STOP_RECORDING"
        private val mutableState = MutableStateFlow(VoipRecordingState())
        internal val state = mutableState.asStateFlow()
        private val systemPermissions = arrayOf(
            Manifest.permission.CAPTURE_AUDIO_OUTPUT,
            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "android.permission.MODIFY_AUDIO_ROUTING",
        )
        internal fun hasSystemPermissions(context: Context) = systemPermissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        internal fun canRecord(context: Context, call: VoipCall): Boolean = try {
            val audio = context.getSystemService(AudioManager::class.java)
            VoipCalls.recordingEnabled(context) && VoipCalls.hasNotificationAccess(context) &&
                VoipCalls.calls.value.singleOrNull()?.token == call.token && !call.incoming &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
                context.getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
                hasSystemPermissions(context) &&
                !context.getSystemService(TelecomManager::class.java).isInManagedCall &&
                audio.mode == AudioManager.MODE_IN_COMMUNICATION &&
                (audio.activeRecordingConfigurations.any { it.clientUid == call.uid && !it.isClientSilenced } ||
                    audio.activePlaybackConfigurations.any { it.clientUid == call.uid && it.isActive &&
                        it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION })
        } catch (_: RuntimeException) { false }

        internal fun start(context: Context, token: String) {
            if (state.value.busy) return
            try {
                context.startForegroundService(Intent(context, VoipRecordingService::class.java)
                    .putExtra(VoipNotificationListener.EXTRA_TOKEN, token))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Recording start rejected", e)
                mutableState.value = VoipRecordingState(token, message = R.string.voip_cannot_record)
            }
        }

        internal fun stop(context: Context) {
            if (state.value.busy) {
                try {
                    context.startService(Intent(context, VoipRecordingService::class.java).setAction(STOP)
                        .putExtra(VoipNotificationListener.EXTRA_TOKEN, state.value.token))
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Recording stop command rejected", e)
                    context.stopService(Intent(context, VoipRecordingService::class.java))
                }
            }
        }
    }
}
