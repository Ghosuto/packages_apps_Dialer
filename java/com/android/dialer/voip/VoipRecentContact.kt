/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.android.dialer.R
import com.android.dialer.expressive.RecentCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberVoipContactOpener(): (RecentCall) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingId
        val packageName = pendingPackage
        pendingId = null
        pendingPackage = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && id != null && packageName != null &&
            uri != null && VoipCalls.enabled(context)) {
            busy = true
            scope.launch {
                try {
                    val address = withContext(Dispatchers.IO) {
                        VoipContacts.pickedContact(context, uri)?.takeIf {
                            VoipCallHistory.saveContact(context, id, packageName, it)
                        }
                    }
                    if (address != null) VoipContacts.open(context, packageName, address)
                    else VoipContacts.failed(context)
                } finally { busy = false }
            }
        }
    }
    return { call ->
        val packageName = call.voipPackage
        if (packageName != null && VoipCalls.enabled(context) && !busy && pendingId == null) {
            if (!VoipContacts.supportsContact(packageName)) VoipCalls.openApp(context, packageName)
            else {
                busy = true
                scope.launch {
                    try {
                        val address = withContext(Dispatchers.IO) { VoipContacts.resolve(context, call) }
                        if (VoipCalls.enabled(context)) {
                            if (address != null) {
                                val saved = withContext(Dispatchers.IO) {
                                    VoipCallHistory.saveContact(context, call.id, packageName, address)
                                }
                                if (saved) VoipContacts.open(context, packageName, address)
                                else VoipContacts.failed(context)
                            } else {
                                pendingId = call.id
                                pendingPackage = packageName
                                try {
                                    picker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI))
                                    Toast.makeText(context, context.getString(R.string.voip_choose_contact,
                                        call.name, call.voipAppName.orEmpty()), Toast.LENGTH_LONG).show()
                                } catch (_: RuntimeException) {
                                    pendingId = null
                                    pendingPackage = null
                                    VoipContacts.failed(context)
                                }
                            }
                        }
                    } finally { busy = false }
                }
            }
        }
    }
}
