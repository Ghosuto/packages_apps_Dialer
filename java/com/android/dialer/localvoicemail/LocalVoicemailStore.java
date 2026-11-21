/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package com.android.dialer.localvoicemail;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.VoicemailContract.Voicemails;
import android.telecom.PhoneAccountHandle;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.dialer.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable pending messages, retried on the next call if the voicemail provider is unavailable. */
final class LocalVoicemailStore {
  private static final String TAG = "LocalVoicemailStore";
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

  private LocalVoicemailStore() { }

  static void stage(Context context, String number, long duration,
      @Nullable PhoneAccountHandle account, File recording) throws IOException {
    JSONObject metadata = new JSONObject();
    try {
      metadata.put("number", number);
      metadata.put("duration", duration);
      metadata.put("date", System.currentTimeMillis());
      if (account != null) {
        metadata.put("component", account.getComponentName().flattenToString());
        metadata.put("account", account.getId());
      }
    } catch (JSONException exception) {
      throw new IOException("Unable to describe voicemail", exception);
    }
    AtomicFile pending = new AtomicFile(new File(recording.getPath() + ".json"));
    FileOutputStream output = null;
    try {
      output = pending.startWrite();
      output.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
      pending.finishWrite(output);
    } catch (IOException exception) {
      pending.failWrite(output);
      throw exception;
    }
    retryPending(context);
  }

  static void retryPending(Context context) {
    Context application = context.getApplicationContext();
    WORKER.execute(() -> {
      UserManager users = application.getSystemService(UserManager.class);
      if (users != null && !users.isUserUnlocked()) return;
      File[] pending = application.getFilesDir().listFiles(
          (directory, name) -> name.startsWith("local_voicemail_") && name.endsWith(".wav.json"));
      if (pending == null) return;
      boolean failed = false;
      for (File metadataFile : pending) if (!commit(application, metadataFile)) failed = true;
      if (failed) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(application, R.string.local_voicemail_save_pending, Toast.LENGTH_LONG).show());
      }
    });
  }

  private static boolean commit(Context context, File metadataFile) {
    AtomicFile pending = new AtomicFile(metadataFile);
    File recording = new File(metadataFile.getPath().substring(0, metadataFile.getPath().length() - 5));
    Uri row = null;
    try {
      if (!recording.isFile() || recording.length() <= 44) {
        recording.delete();
        pending.delete();
        return true;
      }
      JSONObject metadata = new JSONObject(new String(pending.readFully(), StandardCharsets.UTF_8));
      Uri source = Voicemails.buildSourceUri(context.getPackageName());
      try (Cursor cursor = context.getContentResolver().query(source,
          new String[] {Voicemails._ID, Voicemails.HAS_CONTENT, Voicemails.DELETED}, Voicemails.SOURCE_DATA + " = ?",
          new String[] {recording.getName()}, null)) {
        if (cursor == null) throw new IOException("Voicemail provider returned no cursor");
        if (cursor.moveToFirst()) {
          if (cursor.getInt(1) != 0 || cursor.getInt(2) != 0) {
            if (recording.delete()) pending.delete();
            return true;
          }
          row = source.buildUpon().appendPath(String.valueOf(cursor.getLong(0))).build();
        }
      }
      if (row == null) {
        ContentValues values = new ContentValues();
        values.put(Voicemails.DATE, metadata.getLong("date"));
        values.put(Voicemails.NUMBER, metadata.getString("number"));
        values.put(Voicemails.DURATION, metadata.getLong("duration"));
        values.put(Voicemails.MIME_TYPE, "audio/wav");
        values.put(Voicemails.SOURCE_PACKAGE, context.getPackageName());
        values.put(Voicemails.SOURCE_DATA, recording.getName());
        values.put(Voicemails.IS_READ, 0);
        values.put(Voicemails.HAS_CONTENT, 0);
        values.put(Voicemails.IS_OMTP_VOICEMAIL, 0);
        String component = metadata.optString("component");
        if (!component.isEmpty() && ComponentName.unflattenFromString(component) != null) {
          values.put(Voicemails.PHONE_ACCOUNT_COMPONENT_NAME, component);
          values.put(Voicemails.PHONE_ACCOUNT_ID, metadata.optString("account"));
        }
        row = context.getContentResolver().insert(source, values);
        if (row == null) throw new IOException("Voicemail provider rejected the message");
      }
      try (InputStream input = new FileInputStream(recording);
          OutputStream output = context.getContentResolver().openOutputStream(row, "wt")) {
        if (output == null) throw new IOException("Voicemail provider returned no output stream");
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        output.flush();
      }
      ContentValues complete = new ContentValues();
      complete.put(Voicemails.HAS_CONTENT, 1);
      if (context.getContentResolver().update(row, complete, null, null) == 0) {
        throw new IOException("Voicemail provider did not publish the message");
      }
      if (recording.delete()) pending.delete();
      return true;
    } catch (IOException | JSONException | RuntimeException exception) {
      Log.w(TAG, "Keeping pending voicemail for retry", exception);
      if (row != null) {
        try { context.getContentResolver().delete(row, null, null); }
        catch (RuntimeException cleanupFailure) { Log.w(TAG, "Could not remove incomplete row", cleanupFailure); }
      }
      return false;
    }
  }
}
