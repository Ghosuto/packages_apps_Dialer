/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class VoipAction(val title: String, val intent: PendingIntent)

internal data class VoipCall(
    val key: String,
    val token: String,
    val packageName: String,
    val uid: Int,
    val appName: String,
    val caller: String,
    val incoming: Boolean,
    val ongoing: Boolean,
    val date: Long,
    val notificationTime: Long,
    val actions: List<VoipAction>,
    val contentIntent: PendingIntent?,
    val contactUri: String?,
)

internal object VoipCalls {
    const val ENABLED = "universal_voip_calls"
    const val RECORDING_ENABLED = "voip_call_recording"
    val apps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.apps.tachyon" to "Google Meet",
        "com.google.android.apps.meetings" to "Google Meet",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
    )
    private val mutableCalls = MutableStateFlow<List<VoipCall>>(emptyList())
    val calls = mutableCalls.asStateFlow()
    fun preferences(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)
    fun enabled(context: Context) = preferences(context).getBoolean(ENABLED, false)
    fun recordingEnabled(context: Context) =
        enabled(context) && preferences(context).getBoolean(RECORDING_ENABLED, false)
    fun hasNotificationAccess(context: Context) =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(context, VoipNotificationListener::class.java))
    fun find(token: String?) = calls.value.firstOrNull { it.token == token }
    fun replace(calls: List<VoipCall>) { mutableCalls.value = calls }

    fun openApp(context: Context, packageName: String) {
        if (!enabled(context) || packageName !in apps) return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalStateException("Calling app is unavailable")
            context.startActivity(intent)
        } catch (_: RuntimeException) {
            Toast.makeText(context, com.android.dialer.R.string.voip_action_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun parse(context: Context, sbn: StatusBarNotification): VoipCall? {
        val appName = apps[sbn.packageName] ?: return null
        if (sbn.user != Process.myUserHandle()) return null
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE)
        val hasCallStyle = callType == Notification.CallStyle.CALL_TYPE_INCOMING ||
            callType == Notification.CallStyle.CALL_TYPE_ONGOING ||
            callType == Notification.CallStyle.CALL_TYPE_SCREENING
        if (!hasCallStyle && (notification.category != Notification.CATEGORY_CALL ||
                notification.flags and Notification.FLAG_ONGOING_EVENT == 0)) return null
        val answer = extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
        val decline = extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
        val hangup = extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
        val incoming = callType == Notification.CallStyle.CALL_TYPE_INCOMING
        val actions = if (hasCallStyle) {
            buildList {
                if (incoming && decline != null) add(VoipAction(context.getString(
                    com.android.dialer.R.string.voip_decline), decline))
                if (incoming && answer != null) add(VoipAction(context.getString(
                    com.android.dialer.R.string.voip_answer), answer))
                if (!incoming && hangup != null) add(VoipAction(context.getString(
                    com.android.dialer.R.string.voip_hang_up), hangup))
            }
        } else {
            notification.actions.orEmpty().filter {
                it.actionIntent != null && it.remoteInputs.isNullOrEmpty() &&
                    !it.title.isNullOrBlank()
            }.map { VoipAction(it.title.toString().take(100), it.actionIntent) }.take(4)
        }
        val caller = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
            ?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?.takeIf { it.isNotBlank() } ?: appName
        val contactUri = VoipContacts.notificationContact(context, sbn.packageName, notification)
        val previous = calls.value.firstOrNull {
            it.key == sbn.key && it.uid == sbn.uid && !(incoming && it.ongoing) &&
                !(it.contactUri?.startsWith("tel:") == true && contactUri?.startsWith("tel:") == true &&
                    it.contactUri != contactUri)
        }
        return VoipCall(sbn.key, previous?.token ?: UUID.randomUUID().toString(),
            sbn.packageName, sbn.uid, appName, caller.take(200), incoming,
            callType == Notification.CallStyle.CALL_TYPE_ONGOING, previous?.date ?: sbn.postTime,
            sbn.postTime, actions,
            notification.contentIntent,
            if (previous?.contactUri?.startsWith("tel:") == true) previous.contactUri
            else contactUri ?: previous?.contactUri)
    }
}
