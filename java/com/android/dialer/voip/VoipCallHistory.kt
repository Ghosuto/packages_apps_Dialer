/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.CallLog.Calls
import android.util.Log
import com.android.dialer.expressive.RecentCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

internal object VoipCallHistory {
    private val writer = Executors.newSingleThreadExecutor()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private class Database(context: Context) : SQLiteOpenHelper(context, "voip_calls.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE calls (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "notification_key TEXT NOT NULL, package_name TEXT NOT NULL, caller TEXT NOT NULL, " +
                "date INTEGER NOT NULL, incoming INTEGER NOT NULL, ongoing INTEGER NOT NULL, " +
                "active INTEGER NOT NULL, last_seen INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, " +
                "session_token TEXT, notification_time INTEGER NOT NULL DEFAULT 0, contact_uri TEXT)")
            db.execSQL("CREATE UNIQUE INDEX active_call_key ON calls(notification_key) WHERE active = 1")
            db.execSQL("CREATE UNIQUE INDEX call_session ON calls(session_token)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE calls ADD COLUMN session_token TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN notification_time INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX call_session ON calls(session_token)")
                db.execSQL("UPDATE calls SET active = 0")
            }
            if (oldVersion < 3) db.execSQL("ALTER TABLE calls ADD COLUMN contact_uri TEXT")
        }
    }

    fun observe(context: Context, calls: List<VoipCall>) {
        val application = context.applicationContext
        val enabled = VoipCalls.enabled(context)
        val now = System.currentTimeMillis()
        writer.execute {
            try {
                Database(application).use { helper ->
                    val db = helper.writableDatabase
                    db.beginTransaction()
                    try {
                        val ids = mutableListOf<Long>()
                        if (enabled) calls.forEach { call ->
                            var id = findId(db, "session_token = ?", arrayOf(call.token))
                            if (id == null) {
                                id = findId(db, "notification_key = ? AND package_name = ? " +
                                    "AND notification_time = ? AND active = 1",
                                    arrayOf(call.key, call.packageName, call.notificationTime.toString()))
                            }
                            db.update("calls", ContentValues().apply { put("active", 0) },
                                "notification_key = ? AND active = 1 AND _id != ?",
                                arrayOf(call.key, (id ?: -1).toString()))
                            val values = ContentValues().apply {
                                put("session_token", call.token)
                                put("notification_time", call.notificationTime)
                                put("notification_key", call.key)
                                put("package_name", call.packageName)
                                put("caller", call.caller)
                                put("ongoing", if (call.ongoing) 1 else 0)
                                put("active", 1)
                                put("last_seen", now)
                            }
                            if (id == null) {
                                values.put("date", call.date)
                                values.put("incoming", if (call.incoming) 1 else 0)
                                values.put("contact_uri", call.contactUri)
                                ids += db.insertOrThrow("calls", null, values)
                            } else {
                                db.update("calls", values, "_id = ?", arrayOf(id.toString()))
                                if (call.contactUri != null) db.update("calls", ContentValues().apply {
                                    put("contact_uri", call.contactUri)
                                }, "_id = ? AND (contact_uri IS NULL OR " +
                                    "(contact_uri LIKE 'content:%' AND ? LIKE 'tel:%'))",
                                    arrayOf(id.toString(), call.contactUri))
                                ids += id
                            }
                        }
                        val ended = ContentValues().apply { put("active", 0); put("last_seen", now) }
                        if (ids.isEmpty()) db.update("calls", ended, "active = 1", null)
                        else db.update("calls", ended,
                            "active = 1 AND _id NOT IN (${ids.joinToString(",") { "?" }})",
                            ids.map { it.toString() }.toTypedArray())
                        db.delete("calls", "hidden = 1 AND active = 0 AND last_seen < ?",
                            arrayOf((now - 60000).toString()))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                changes.update { it + 1 }
            } catch (e: RuntimeException) { Log.w("VoipCallHistory", "Cannot update call history", e) }
        }
    }

    private fun findId(db: SQLiteDatabase, selection: String, args: Array<String>): Long? =
        db.query("calls", arrayOf("_id"), selection, args, null, null, "date DESC", "1").use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    fun load(context: Context): List<RecentCall> {
        if (!VoipCalls.enabled(context)) return emptyList()
        return try {
            Database(context).use { helper ->
                helper.readableDatabase.query("calls", arrayOf("_id", "package_name", "caller", "date", "contact_uri"),
                    "hidden = 0", null, null, null, "date DESC, _id DESC").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val packageName = cursor.getString(1)
                            val appName = VoipCalls.apps[packageName] ?: continue
                            add(RecentCall(-cursor.getLong(0), cursor.getString(2), "", cursor.getLong(3),
                                0, 0, Calls.PRESENTATION_ALLOWED, voipPackage = packageName, voipAppName = appName,
                                voipContactUri = cursor.getString(4)))
                        }
                    }
                }
            }
        } catch (e: RuntimeException) {
            Log.w("VoipCallHistory", "Cannot load VoIP history", e)
            emptyList()
        }
    }

    fun saveContact(context: Context, id: Long, packageName: String, contactUri: String): Boolean {
        if (!VoipCalls.enabled(context) || id >= 0 || !VoipContacts.supportsContact(packageName)) return false
        return try {
            val updated = Database(context).use { helper ->
                helper.writableDatabase.update("calls", ContentValues().apply {
                    put("contact_uri", contactUri)
                }, "_id = ? AND package_name = ? AND hidden = 0", arrayOf((-id).toString(), packageName)) > 0
            }
            if (updated) changes.update { it + 1 }
            updated
        } catch (e: RuntimeException) {
            Log.w("VoipCallHistory", "Cannot save VoIP contact", e)
            false
        }
    }

    fun delete(context: Context, calls: List<RecentCall>) {
        Database(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                calls.filter { it.voipPackage != null }.forEach {
                    val args = arrayOf((-it.id).toString())
                    db.update("calls", ContentValues().apply {
                        put("hidden", 1)
                        put("last_seen", System.currentTimeMillis())
                    }, "_id = ?", args)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        changes.update { it + 1 }
    }
}
