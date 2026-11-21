/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package com.android.dialer.localvoicemail;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.dialer.R;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class LocalVoicemailGreetingRecorder {
  interface Callback {
    void onStarted();
    void onCompleted(@Nullable File greeting, @Nullable Exception exception);
  }

  private final Context mContext;
  private final Callback mCallback;
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private volatile boolean mStopped;
  private volatile boolean mSave = true;

  LocalVoicemailGreetingRecorder(Context context, Callback callback) {
    mContext = context.getApplicationContext();
    mCallback = callback;
  }

  void start() {
    new Thread(this::record, "LocalVoicemailGreetingRecorder").start();
  }

  synchronized void stop(boolean save) {
    if (mStopped) return;
    mSave = save;
    mStopped = true;
  }

  private void record() {
    AudioRecord record = null;
    File file = null;
    boolean valid = false;
    boolean saved = false;
    Exception failure = null;
    try {
      AudioManager audio = mContext.getSystemService(AudioManager.class);
      if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL) {
        throw new IOException("Cannot record a greeting during a call");
      }
      int minimum = AudioRecord.getMinBufferSize(LocalVoicemailAudioController.SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
          .setAudioFormat(new AudioFormat.Builder()
              .setSampleRate(LocalVoicemailAudioController.SAMPLE_RATE)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
          .setBufferSizeInBytes(Math.max(minimum * 2, 8192)).build();
      if (record.getState() != AudioRecord.STATE_INITIALIZED) {
        throw new IOException("Microphone capture is unavailable");
      }
      if (mStopped) return;
      file = File.createTempFile("local_greeting_", ".wav", mContext.getFilesDir());
      try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
        LocalVoicemailAudioController.writeWavHeader(output, 0);
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
          throw new IOException("Microphone recording did not start");
        }
        mMain.post(() -> { if (!mStopped) mCallback.onStarted(); });
        byte[] buffer = new byte[2048];
        long bytes = 0;
        long maxBytes = (long) LocalVoicemailAudioController.MAX_GREETING_SECONDS
            * LocalVoicemailAudioController.BYTES_PER_SECOND;
        long deadline = SystemClock.elapsedRealtime()
            + LocalVoicemailAudioController.MAX_GREETING_SECONDS * 1000L;
        boolean hasAudio = false;
        while (!mStopped && bytes < maxBytes && SystemClock.elapsedRealtime() < deadline) {
          if (audio.getMode() != AudioManager.MODE_NORMAL) throw new IOException("A call interrupted recording");
          int count = record.read(buffer, 0, (int) Math.min(buffer.length, maxBytes - bytes),
              AudioRecord.READ_NON_BLOCKING);
          if (count < 0 || (count & 1) != 0) throw new IOException("Microphone read failed: " + count);
          if (count == 0) {
            Thread.sleep(10);
            continue;
          }
          output.write(buffer, 0, count);
          bytes += count;
          for (int i = 0; i < count && !hasAudio; i++) hasAudio = buffer[i] != 0;
        }
        LocalVoicemailAudioController.writeWavHeader(output, bytes);
        output.getFD().sync();
        synchronized (this) {
          if (!mStopped) mStopped = true;
        }
        valid = mSave && hasAudio && bytes >= LocalVoicemailAudioController.BYTES_PER_SECOND / 2;
        if (mSave && !valid) throw new IOException("Greeting is empty or too short");
      }
      if (valid) {
        String old = LocalVoicemailPreferences.getCustomGreetingPath(mContext);
        if (!LocalVoicemailPreferences.getPreferences(mContext).edit()
            .putString(LocalVoicemailPreferences.PREF_CUSTOM_GREETING_PATH, file.getPath()).commit()) {
          throw new IOException("Could not save the custom greeting preference");
        }
        saved = true;
        LocalVoicemailPreferences.setCustomGreetingPath(mContext, file.getPath());
        if (old != null) new File(old).delete();
      }
    } catch (Exception exception) {
      failure = exception;
    } finally {
      if (record != null) {
        try { record.stop(); } catch (IllegalStateException ignored) { }
        try { record.release(); } catch (RuntimeException ignored) { }
      }
      if (!saved && file != null) file.delete();
      File completed = saved ? file : null;
      Exception error = failure;
      mMain.post(() -> {
        if (error != null && mSave) {
          Toast.makeText(mContext, R.string.local_voicemail_greeting_record_failed, Toast.LENGTH_LONG).show();
        }
        mCallback.onCompleted(completed, error);
      });
    }
  }
}
