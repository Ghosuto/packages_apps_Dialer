/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.dialer.R

class VoipNotificationListener : NotificationListenerService() {
    private var connected = false
    private val main = Handler(Looper.getMainLooper())
    private val autoRecordingAttempted = mutableSetOf<String>()
    private val autoRecordingMonitor = object : Runnable {
        override fun run() {
            val calls = VoipCalls.calls.value
            autoRecordingAttempted.retainAll(calls.map { it.token }.toSet())
            val recording = VoipRecordingService.state.value
            recording.token?.let { token ->
                if (calls.any { it.token == token }) autoRecordingAttempted.add(token)
            }
            if (!connected || !VoipCalls.autoRecordingEnabled(this@VoipNotificationListener) ||
                !VoipRecordingService.hasBackgroundRecordingPermission(this@VoipNotificationListener)) return
            val call = calls.singleOrNull()
            if (call != null && !recording.busy && call.token !in autoRecordingAttempted &&
                VoipRecordingService.canRecord(this@VoipNotificationListener, call, automatic = true)) {
                autoRecordingAttempted.add(call.token)
                VoipRecordingService.start(this@VoipNotificationListener, call.token, automatic = true)
            }
            if (calls.isNotEmpty()) main.postDelayed(this, 1000)
        }
    }
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == VoipCalls.ENABLED || key == VoipCalls.RECORDING_ENABLED ||
            key == VoipCalls.AUTO_RECORDING_ENABLED) {
            refresh(false)
            if (!VoipCalls.recordingEnabled(this)) VoipRecordingService.stop(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        VoipCalls.preferences(this).registerOnSharedPreferenceChangeListener(preferencesListener)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.voip_calls_title),
                NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            })
    }

    override fun onListenerConnected() { connected = true; refresh(false, true) }
    override fun onListenerDisconnected() { connected = false; clearCalls() }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (VoipCalls.enabled(this) && sbn.packageName in VoipCalls.apps) refresh(true)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (VoipCalls.enabled(this) && sbn.packageName in VoipCalls.apps) {
            val removed = VoipCalls.calls.value.filter {
                it.key == sbn.key && it.notificationTime == sbn.postTime
            }
            removed.forEach { cancel(it) }
            VoipCalls.replace(VoipCalls.calls.value.filter { it !in removed })
            if (removed.isNotEmpty()) VoipCallHistory.observe(this, VoipCalls.calls.value)
            if (removed.any { it.token == VoipRecordingService.state.value.token }) {
                VoipRecordingService.stop(this)
            }
            refresh(false)
        }
    }

    private fun refresh(allowAlert: Boolean, syncHistory: Boolean = false) {
        if (!connected || !VoipCalls.enabled(this)) {
            clearCalls(closeHistory = !VoipCalls.enabled(this))
            return
        }
        try {
            val previous = VoipCalls.calls.value
            val next = activeNotifications.orEmpty().mapNotNull { sbn ->
                try { VoipCalls.parse(this, sbn) }
                catch (e: RuntimeException) { Log.w(TAG, "Invalid call notification", e); null }
            }
            previous.filter { old -> next.none { it.token == old.token } }.forEach { cancel(it) }
            VoipCalls.replace(next)
            if (syncHistory || next != previous) VoipCallHistory.observe(this, next)
            val manager = getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { notification ->
                notification.id == NOTIFICATION_ID && next.none { it.key == notification.tag }
            }.forEach { manager.cancel(it.tag, NOTIFICATION_ID) }
            next.forEach { call ->
                val old = previous.firstOrNull { it.token == call.token }
                if (old?.copy(notificationTime = call.notificationTime) != call) {
                    notifyCall(call, allowAlert && old == null && call.incoming)
                }
            }
            val recording = VoipRecordingService.state.value
            if (recording.busy && (next.size != 1 || next.single().token != recording.token)) {
                VoipRecordingService.stop(this)
            }
            main.removeCallbacks(autoRecordingMonitor)
            autoRecordingMonitor.run()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Call notifications are unavailable", e)
            clearCalls()
        }
    }

    private fun notifyCall(call: VoipCall, alert: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val open = screenIntent(this, call.token)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ms_call_24)
            .setContentTitle(call.caller)
            .setContentText(getString(if (call.incoming) R.string.voip_incoming_app
                else R.string.voip_call_app, call.appName))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        call.actions.forEach { builder.addAction(Notification.Action.Builder(
            null, it.title, it.intent).build()) }
        if (alert && manager.canUseFullScreenIntent()) builder.setFullScreenIntent(open, true)
        manager.notify(call.key, NOTIFICATION_ID, builder.build())
    }

    private fun cancel(call: VoipCall) =
        getSystemService(NotificationManager::class.java).cancel(call.key, NOTIFICATION_ID)

    private fun clearCalls(closeHistory: Boolean = false) {
        main.removeCallbacks(autoRecordingMonitor)
        val manager = getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.id == NOTIFICATION_ID }
            .forEach { manager.cancel(it.tag, NOTIFICATION_ID) }
        if (closeHistory) VoipCallHistory.observe(this, emptyList())
        VoipCalls.replace(emptyList())
        VoipRecordingService.stop(this)
    }

    override fun onDestroy() {
        VoipCalls.preferences(this).unregisterOnSharedPreferenceChangeListener(preferencesListener)
        clearCalls()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoipNotifications"
        private const val CHANNEL = "voip_calls"
        private const val NOTIFICATION_ID = 4100
        internal const val EXTRA_TOKEN = "voip_call_token"
        internal fun screenIntent(context: Context, token: String): PendingIntent =
            PendingIntent.getActivity(context, 0,
                Intent(context, VoipCallActivity::class.java)
                    .setData(Uri.Builder().scheme("dialer-voip").authority("call")
                        .appendPath(token).build())
                    .putExtra(EXTRA_TOKEN, token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
