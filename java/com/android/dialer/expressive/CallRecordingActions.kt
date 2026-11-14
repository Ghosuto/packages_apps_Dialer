/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.expressive

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.android.dialer.R
import com.android.dialer.callrecord.CallRecordingDataStore
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun shareCallRecording(context: Context, entry: RecordedCall) {
    val mimeType = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { }
            ?: throw FileNotFoundException("Recording is unavailable")
        context.contentResolver.getType(entry.uri) ?: "audio/*"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, entry.uri)
        clipData = ClipData.newRawUri(entry.recording.fileName, entry.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent,
        context.getString(R.string.expressive_share_recording)))
}

internal suspend fun deleteCallRecording(context: Context, entry: RecordedCall) =
    withContext(Dispatchers.IO + NonCancellable) {
        val store = CallRecordingDataStore()
        try {
            store.open(context)
            val deleted = context.contentResolver.delete(entry.uri, null, null)
            val removed = deleted > 0 || context.contentResolver.query(entry.uri,
                arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { !it.moveToFirst() } == true
            check(removed) { "Recording could not be deleted" }
            store.deleteRecording(entry.id)
            context.sendBroadcast(Intent(CallRecordingDataStore.ACTION_RECORDINGS_CHANGED)
                .setPackage(context.packageName))
        } finally {
            store.close()
        }
    }
