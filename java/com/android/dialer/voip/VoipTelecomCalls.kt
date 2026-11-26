/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.dialer.voip

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserHandle
import android.telecom.Call
import android.telecom.TelecomManager
import com.android.dialer.R
import java.util.UUID

internal object VoipTelecomCalls {
    private class Session(val uid: Int, val packageName: String, val callback: Call.Callback) {
        val id = UUID.randomUUID().toString()
        var token = id
        var key = "telecom:$id"
        var date = System.currentTimeMillis()
    }

    private val sessions = mutableMapOf<Call, Session>()
    var onChanged: ((Boolean) -> Unit)? = null

    @JvmStatic fun add(context: Context, call: Call) {
        val account = call.details.accountHandle ?: return
        val pkg = account.componentName.packageName
        if (pkg !in VoipCalls.apps || call in sessions) return
        val uid = try {
            if (!VoipCalls.supportsUser(context, account.userHandle)) return
            context.getSystemService(LauncherApps::class.java)
                .getApplicationInfo(pkg, 0, account.userHandle).uid
        } catch (_: PackageManager.NameNotFoundException) {
            return
        } catch (_: SecurityException) {
            return
        }
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) { onChanged?.invoke(true) }
            override fun onDetailsChanged(call: Call, details: Call.Details) { onChanged?.invoke(true) }
        }
        val session = Session(uid, pkg, callback)
        if (sessions.values.none { it.uid == uid }) {
            VoipCalls.calls.value.singleOrNull { it.uid == uid }?.let {
                session.token = it.token
                session.key = it.key
                session.date = it.date
            }
        }
        sessions[call] = session
        call.registerCallback(callback)
        onChanged?.invoke(true)
    }

    @JvmStatic fun remove(call: Call) {
        val session = sessions.remove(call) ?: return
        call.unregisterCallback(session.callback)
        onChanged?.invoke(false)
    }

    @JvmStatic fun clear() {
        sessions.forEach { (call, session) -> call.unregisterCallback(session.callback) }
        sessions.clear()
        onChanged?.invoke(false)
    }

    fun merge(context: Context, notifications: List<VoipCall>): List<VoipCall> {
        if (sessions.isEmpty()) return notifications
        val result = notifications.toMutableList()
        for ((call, session) in sessions) {
            val details = call.details
            val state = details.state
            val incoming = ringing(state)
            val app = VoipCalls.appLabel(context, VoipCalls.apps.getValue(session.packageName),
                UserHandle.getUserHandleForUid(session.uid))
            val source = notifications.firstOrNull { it.uid == session.uid && it.key == session.key }
                ?: if (sessions.values.count { it.uid == session.uid } == 1)
                    notifications.singleOrNull { it.uid == session.uid } else null
            if (source != null) result.remove(source)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) continue
            val caller = details.callerDisplayName?.takeIf {
                it.isNotBlank() && details.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED
            } ?: details.handle?.schemeSpecificPart?.takeIf {
                it.isNotBlank() && details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED
            } ?: source?.caller ?: app
            val actions = if (incoming) listOf(action(context, session, R.string.voip_decline),
                action(context, session, R.string.voip_answer))
                else listOf(action(context, session, R.string.voip_hang_up))
            result.add(VoipCall(session.key, session.token, session.packageName, session.uid,
                app, caller, incoming, state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING,
                session.date, source?.notificationTime ?: session.date, actions,
                source?.contentIntent, source?.contactUri, recordingEligible = state == Call.STATE_ACTIVE))
        }
        return result
    }

    private fun ringing(state: Int) = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING

    private fun action(context: Context, session: Session, label: Int): VoipAction {
        val intent = Intent(context, VoipTelecomActionReceiver::class.java)
            .setData(Uri.Builder().scheme("dialer-telecom").authority(session.id)
                .appendPath(label.toString()).build())
        return VoipAction(context.getString(label), PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE))
    }

    fun perform(uri: Uri) {
        val call = sessions.entries.firstOrNull { it.value.id == uri.authority }?.key ?: return
        val incoming = ringing(call.details.state)
        when (uri.lastPathSegment?.toIntOrNull()) {
            R.string.voip_answer -> if (incoming) call.answer(call.details.videoState)
            R.string.voip_decline -> if (incoming) call.reject(false, null)
            R.string.voip_hang_up -> if (!incoming) call.disconnect()
        }
    }
}

class VoipTelecomActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (VoipCalls.enabled(context)) intent.data?.let { VoipTelecomCalls.perform(it) }
    }
}
