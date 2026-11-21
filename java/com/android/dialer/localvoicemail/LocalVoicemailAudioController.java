/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.localvoicemail;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.telecom.PhoneAccountHandle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.dialer.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends audio to the call uplink and records only the call downlink. */
public final class LocalVoicemailAudioController {
  private static final String TAG = "LocalVoicemailAudio";
  static final int SAMPLE_RATE = 16000;
  static final int BYTES_PER_SECOND = SAMPLE_RATE * 2;
  static final int MAX_GREETING_SECONDS = 30;

  public interface AudioControllerCallback {
    void onGreetingStarted();
    void onRecordingStarted();
    void onRecordingCompleted(@Nullable File recordedFile, long durationSeconds);
    void onError(String message, Exception exception);
  }

  private final Context mContext;
  private final AudioManager mAudioManager;
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
  private Session mSession;

  public LocalVoicemailAudioController(@NonNull Context context) {
    mContext = context.getApplicationContext();
    mAudioManager = mContext.getSystemService(AudioManager.class);
  }

  public static boolean isSupported(Context context) {
    return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED && hasCallAudioSupport(context);
  }

  public static boolean hasCallAudioSupport(Context context) {
    UserManager users = context.getSystemService(UserManager.class);
    if (users != null && !users.isUserUnlocked()) return false;
    for (String permission : new String[] {
        Manifest.permission.CALL_AUDIO_INTERCEPTION,
        Manifest.permission.MODIFY_AUDIO_ROUTING}) {
      if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
    }
    AudioManager audio = context.getSystemService(AudioManager.class);
    try {
      return audio != null && audio.isPstnCallAudioInterceptable();
    } catch (RuntimeException exception) {
      Log.w(TAG, "Call audio interception is unavailable", exception);
      return false;
    }
  }

  public synchronized boolean isSessionBusy() {
    return mSession != null;
  }

  public synchronized void startVoicemailSession(
      @NonNull String callerNumber, int maxDurationSeconds,
      @Nullable PhoneAccountHandle account, @NonNull AudioControllerCallback callback) {
    if (mSession != null) throw new IllegalStateException("A voicemail session is still finishing");
    Session session = new Session(callerNumber, maxDurationSeconds, account, callback);
    mSession = session;
    mWorker.execute(() -> runSession(session));
  }

  /** Cancellation never joins an audio thread on the main thread. */
  public synchronized void stopSessionAndNotify() {
    if (mSession == null) return;
    Session session = mSession;
    session.stopped = true;
    synchronized (session) {
      if (session.track != null) {
        try {
          session.track.pause();
          session.track.flush();
        } catch (IllegalStateException ignored) { }
      }
    }
  }

  private void runSession(Session session) {
    File message = null;
    Exception failure = null;
    long duration = 0;
    try {
      byte[] greeting = readGreeting();
      if (session.stopped) return;
      AudioFormat output = new AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
      AudioFormat input = new AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
      AudioTrack track = mAudioManager.getCallUplinkInjectionAudioTrack(output);
      synchronized (session) { session.track = track; }
      if (session.stopped) return;
      AudioRecord record = mAudioManager.getCallDownlinkExtractionAudioRecord(input);
      synchronized (session) { session.record = record; }
      if (session.stopped) return;
      if (session.track.getState() != AudioTrack.STATE_INITIALIZED
          || session.record.getState() != AudioRecord.STATE_INITIALIZED) {
        throw new IOException("Unable to initialize call audio interception");
      }
      postProgress(session, true);
      playGreeting(session, greeting);
      if (session.stopped) return;
      message = recordMessage(session);
      if (message != null) {
        duration = Math.max(1, (message.length() - 44) / BYTES_PER_SECOND);
        LocalVoicemailStore.stage(mContext, session.number, duration, session.account, message);
      }
    } catch (Exception exception) {
      failure = exception;
      Log.e(TAG, "Voicemail audio session failed", exception);
    } finally {
      releaseAudio(session);
      File completedMessage = message;
      long completedDuration = duration;
      Exception completedFailure = failure;
      mMainHandler.post(() -> {
        synchronized (LocalVoicemailAudioController.this) {
          if (mSession == session) mSession = null;
        }
        if (completedFailure != null && !session.stopped) {
          session.callback.onError("Unable to record local voicemail", completedFailure);
        } else {
          session.callback.onRecordingCompleted(completedMessage, completedDuration);
        }
      });
    }
  }

  private byte[] readGreeting() throws IOException {
    String path = LocalVoicemailPreferences.getCustomGreetingPath(mContext);
    if (path != null && new File(path).isFile()) {
      try (InputStream input = new FileInputStream(path)) {
        byte[] pcm = readPcmDataFromWav(input);
        byte[] greeting = new byte[pcm.length + SAMPLE_RATE * 2 / 5];
        System.arraycopy(pcm, 0, greeting, 0, pcm.length);
        for (int frame = 0; frame < SAMPLE_RATE / 5; frame++) {
          short sample = (short) (Math.sin(frame * 2 * Math.PI * 880 / SAMPLE_RATE) * 10000);
          greeting[pcm.length + frame * 2] = (byte) sample;
          greeting[pcm.length + frame * 2 + 1] = (byte) (sample >> 8);
        }
        return greeting;
      }
    }
    try (InputStream input = mContext.getResources()
        .openRawResource(R.raw.local_voicemail_default_greeting_wav)) {
      return readPcmDataFromWav(input);
    }
  }

  private void playGreeting(Session session, byte[] pcm) throws IOException, InterruptedException {
    AudioTrack track = session.track;
    synchronized (session) {
      if (session.stopped) return;
      track.setVolume(1f);
      track.play();
    }
    long deadline = SystemClock.elapsedRealtime() + pcm.length * 1000L / BYTES_PER_SECOND + 5000;
    int offset = 0;
    while (!session.stopped && offset < pcm.length) {
      if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Greeting timed out");
      int written = track.write(pcm, offset, Math.min(4096, pcm.length - offset),
          AudioTrack.WRITE_NON_BLOCKING);
      if (written < 0) throw new IOException("Greeting write failed: " + written);
      offset += written;
      if (written == 0) Thread.sleep(10);
    }
    while (!session.stopped && track.getPlaybackHeadPosition() < pcm.length / 2) {
      if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Greeting did not finish");
      Thread.sleep(10);
    }
    synchronized (session) {
      if (!session.stopped) track.stop();
    }
  }

  @Nullable
  private File recordMessage(Session session) throws IOException, InterruptedException {
    File file = File.createTempFile("local_voicemail_", ".wav", mContext.getFilesDir());
    boolean valid = false;
    long bytes = 0;
    boolean hasAudio = false;
    try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
      writeWavHeader(output, 0);
      synchronized (session) {
        if (session.stopped) return null;
        session.record.startRecording();
      }
      if (session.record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
        throw new IOException("Call capture did not start");
      }
      postProgress(session, false);
      long maxBytes = (long) session.maxDuration * BYTES_PER_SECOND;
      long deadline = SystemClock.elapsedRealtime() + session.maxDuration * 1000L;
      byte[] buffer = new byte[2048];
      while (!session.stopped && bytes < maxBytes && SystemClock.elapsedRealtime() < deadline) {
        int size = (int) Math.min(buffer.length, maxBytes - bytes);
        int read;
        try {
          read = session.record.read(buffer, 0, size, AudioRecord.READ_NON_BLOCKING);
        } catch (IllegalStateException exception) {
          if (bytes > 0 || session.stopped) break;
          throw exception;
        }
        if (read < 0) {
          if (bytes == 0 && !session.stopped) throw new IOException("Call capture read failed: " + read);
          break;
        }
        if (read == 0) {
          Thread.sleep(10);
          continue;
        }
        if ((read & 1) != 0) throw new IOException("Incomplete PCM frame from call capture");
        output.write(buffer, 0, read);
        bytes += read;
        for (int i = 0; i < read && !hasAudio; i++) hasAudio = buffer[i] != 0;
      }
      writeWavHeader(output, bytes);
      output.getFD().sync();
      valid = bytes > 0 && hasAudio;
      return valid ? file : null;
    } finally {
      if (!valid) file.delete();
    }
  }

  private void postProgress(Session session, boolean greeting) {
    mMainHandler.post(() -> {
      synchronized (LocalVoicemailAudioController.this) {
        if (mSession != session || session.stopped) return;
      }
      if (greeting) session.callback.onGreetingStarted();
      else session.callback.onRecordingStarted();
    });
  }

  private static void releaseAudio(Session session) {
    synchronized (session) {
      if (session.record != null) {
        try { session.record.stop(); } catch (IllegalStateException ignored) { }
        try { session.record.release(); } catch (RuntimeException ignored) { }
        session.record = null;
      }
      if (session.track != null) {
        try { session.track.stop(); } catch (IllegalStateException ignored) { }
        try { session.track.release(); } catch (RuntimeException ignored) { }
        session.track = null;
      }
    }
  }

  /** Reads RIFF chunks instead of searching for a "data" byte sequence inside arbitrary audio. */
  static byte[] readPcmDataFromWav(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    int limit = MAX_GREETING_SECONDS * BYTES_PER_SECOND + 65536;
    while ((read = input.read(buffer)) != -1) {
      if (output.size() + read > limit) throw new IOException("Greeting is too large");
      output.write(buffer, 0, read);
    }
    byte[] wav = output.toByteArray();
    if (wav.length < 12 || !chunkEquals(wav, 0, "RIFF") || !chunkEquals(wav, 8, "WAVE")) {
      throw new IOException("Greeting must be a PCM WAV file");
    }
    boolean supportedFormat = false;
    for (int offset = 12; offset <= wav.length - 8; ) {
      long length = unsignedInt(wav, offset + 4);
      int start = offset + 8;
      if (length > wav.length - start) throw new IOException("Truncated WAV chunk");
      if (chunkEquals(wav, offset, "fmt ")) {
        supportedFormat = length >= 16 && unsignedShort(wav, start) == 1
            && unsignedShort(wav, start + 2) == 1
            && unsignedInt(wav, start + 4) == SAMPLE_RATE
            && unsignedShort(wav, start + 12) == 2 && unsignedShort(wav, start + 14) == 16;
      } else if (chunkEquals(wav, offset, "data")) {
        if (!supportedFormat || length == 0 || (length & 1) != 0
            || length > MAX_GREETING_SECONDS * BYTES_PER_SECOND) {
          throw new IOException("Greeting must be 16 kHz mono PCM16 and at most 30 seconds");
        }
        byte[] pcm = new byte[(int) length];
        System.arraycopy(wav, start, pcm, 0, pcm.length);
        return pcm;
      }
      offset = start + (int) length + ((int) length & 1);
    }
    throw new IOException("Greeting contains no PCM data");
  }

  private static boolean chunkEquals(byte[] data, int offset, String chunk) {
    for (int i = 0; i < 4; i++) if (data[offset + i] != chunk.charAt(i)) return false;
    return true;
  }

  private static int unsignedShort(byte[] data, int offset) {
    return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
  }

  private static long unsignedInt(byte[] data, int offset) {
    return (unsignedShort(data, offset) | ((long) unsignedShort(data, offset + 2) << 16));
  }

  static void writeWavHeader(RandomAccessFile output, long length) throws IOException {
    output.seek(0);
    output.writeBytes("RIFF");
    writeLittleEndian(output, length + 36, 4);
    output.writeBytes("WAVEfmt ");
    writeLittleEndian(output, 16, 4);
    writeLittleEndian(output, 1, 2);
    writeLittleEndian(output, 1, 2);
    writeLittleEndian(output, SAMPLE_RATE, 4);
    writeLittleEndian(output, BYTES_PER_SECOND, 4);
    writeLittleEndian(output, 2, 2);
    writeLittleEndian(output, 16, 2);
    output.writeBytes("data");
    writeLittleEndian(output, length, 4);
  }

  private static void writeLittleEndian(RandomAccessFile output, long value, int bytes)
      throws IOException {
    for (int i = 0; i < bytes; i++) output.write((int) (value >> (i * 8)) & 255);
  }

  private static final class Session {
    final String number;
    final int maxDuration;
    final PhoneAccountHandle account;
    final AudioControllerCallback callback;
    volatile boolean stopped;
    AudioTrack track;
    AudioRecord record;

    Session(String number, int maxDuration, PhoneAccountHandle account,
        AudioControllerCallback callback) {
      this.number = number;
      this.maxDuration = Math.max(1, Math.min(120, maxDuration));
      this.account = account;
      this.callback = callback;
    }
  }
}
