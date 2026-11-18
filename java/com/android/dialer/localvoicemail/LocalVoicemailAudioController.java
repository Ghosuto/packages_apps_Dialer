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

import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.VoicemailContract.Voicemails;
import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.incallui.call.TelecomAdapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class LocalVoicemailAudioController {
  private static final String TAG = "LocalVoicemailAudio";
  private static final String TEMP_RECORDING_NAME = "local_voicemail_temp.wav";
  private static final int SAMPLE_RATE = 16000;

  public interface AudioControllerCallback {
    void onGreetingStarted();
    void onRecordingStarted();
    void onRecordingCompleted(File recordedFile, long durationSeconds);
    void onError(String message, Exception e);
  }

  private final Context mContext;
  private final AudioManager mAudioManager;
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());

  private MediaPlayer mMediaPlayer;
  private AudioTrack mAudioTrack;
  private AudioRecord mAudioRecord;
  private Thread mRecordingThread;
  private File mTempRecordingFile;

  private boolean mIsSessionActive = false;
  private volatile boolean mIsPlayingGreeting = false;
  private volatile boolean mIsRecording = false;

  private Runnable mMaxDurationTimeoutRunnable;
  private AudioControllerCallback mCallback;

  public LocalVoicemailAudioController(@NonNull Context context) {
    mContext = context.getApplicationContext();
    mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
  }

  public synchronized void startVoicemailSession(
      @NonNull String callerNumber,
      int maxDurationSeconds,
      @Nullable AudioControllerCallback callback) {
    if (mIsSessionActive) {
      stopSessionAndNotify();
    }
    mIsSessionActive = true;
    mCallback = callback;

    silenceLocalAudio();

    // 1. Play greeting prompt to the caller first
    playGreetingPrompt(maxDurationSeconds);

    // 2. Arm safety timeout in case greeting hangs or fails silently
    mMaxDurationTimeoutRunnable = () -> {
      Log.i(TAG, "Voicemail safety timeout reached (" + (maxDurationSeconds + 30) + "s)");
      stopSessionAndNotify();
    };
    mMainHandler.postDelayed(mMaxDurationTimeoutRunnable, (maxDurationSeconds + 30L) * 1000L);
  }

  private void silenceLocalAudio() {
    try {
      if (mAudioManager != null) {
        mAudioManager.setMicrophoneMute(true);
      }
      TelecomAdapter.getInstance().mute(true);
    } catch (Exception e) {
      Log.w(TAG, "Failed to silence local audio: " + e.getMessage());
    }
  }

  private void restoreLocalAudio() {
    try {
      if (mAudioManager != null) {
        mAudioManager.setMicrophoneMute(false);
      }
      TelecomAdapter.getInstance().mute(false);
    } catch (Exception e) {
      Log.w(TAG, "Failed to restore local audio: " + e.getMessage());
    }
  }

  @Nullable
  private AudioDeviceInfo findTelephonyAudioDevice() {
    if (mAudioManager == null) return null;
    AudioDeviceInfo[] outputDevices = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
    if (outputDevices != null) {
      for (AudioDeviceInfo device : outputDevices) {
        if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
          return device;
        }
      }
    }
    return null;
  }

  @Nullable
  private AudioDeviceInfo findTelephonyRxAudioDevice() {
    if (mAudioManager == null) return null;
    AudioDeviceInfo[] inputDevices = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
    if (inputDevices != null) {
      for (AudioDeviceInfo device : inputDevices) {
        if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
          return device;
        }
      }
    }
    return null;
  }

  private void playGreetingPrompt(final int maxDurationSeconds) {
    stopPlayback();
    mIsPlayingGreeting = true;
    if (mCallback != null) {
      mCallback.onGreetingStarted();
    }

    new Thread(() -> {
      boolean success = false;
      String customPath = LocalVoicemailPreferences.getCustomGreetingPath(mContext);
      boolean hasCustomGreeting = customPath != null && new File(customPath).exists();

      if (!hasCustomGreeting && mIsPlayingGreeting) {
        success = playPcmGreetingViaAudioTrack();
      }

      if (!success && mIsPlayingGreeting) {
        Log.i(TAG, "AudioTrack injection failed or custom greeting used; falling back to MediaPlayer");
        mMainHandler.post(() -> playGreetingViaMediaPlayer(maxDurationSeconds));
      } else if (success) {
        Log.i(TAG, "Greeting prompt playback completed successfully");
        stopPlayback();
        mMainHandler.post(() -> {
          if (mIsSessionActive) {
            startDownlinkRecording(maxDurationSeconds);
          }
        });
      } else {
        stopPlayback();
      }
    }, "LocalVoicemailGreeting").start();
  }

  private static byte[] readPcmDataFromWav(InputStream is) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = is.read(buf)) != -1) {
      baos.write(buf, 0, n);
    }
    byte[] fileData = baos.toByteArray();
    for (int i = 0; i < fileData.length - 8; i++) {
      if (fileData[i] == 'd' && fileData[i + 1] == 'a'
          && fileData[i + 2] == 't' && fileData[i + 3] == 'a') {
        int pcmStart = i + 8;
        int pcmLength = fileData.length - pcmStart;
        byte[] pcm = new byte[pcmLength];
        System.arraycopy(fileData, pcmStart, pcm, 0, pcmLength);
        return pcm;
      }
    }
    return fileData;
  }

  public static byte[] convertMonoToStereo(byte[] monoPcm) {
    if (monoPcm == null) return new byte[0];
    byte[] stereoPcm = new byte[monoPcm.length * 2];
    for (int i = 0; i < monoPcm.length; i += 2) {
      byte b0 = monoPcm[i];
      byte b1 = (i + 1 < monoPcm.length) ? monoPcm[i + 1] : 0;
      int outIdx = i * 2;
      stereoPcm[outIdx] = b0;
      stereoPcm[outIdx + 1] = b1;
      stereoPcm[outIdx + 2] = b0;
      stereoPcm[outIdx + 3] = b1;
    }
    return stereoPcm;
  }

  private boolean playPcmGreetingViaAudioTrack() {
    AudioTrack track = null;
    try (InputStream is =
             mContext.getResources().openRawResource(R.raw.local_voicemail_default_greeting_wav)) {
      byte[] monoPcm = readPcmDataFromWav(is);
      if (monoPcm == null || monoPcm.length == 0) {
        Log.e(TAG, "Failed to read PCM data from default greeting WAV");
        return false;
      }

      byte[] stereoPcm = convertMonoToStereo(monoPcm);
      AudioDeviceInfo telephonyDevice = findTelephonyAudioDevice();
      Log.i(TAG, "playPcmGreetingViaAudioTrack: telephonyDevice="
          + (telephonyDevice != null ? telephonyDevice.getProductName() : "null"));

      AudioAttributes commAttributes =
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build();

      AudioFormat stereoFormat =
          new AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(SAMPLE_RATE)
              .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
              .build();

      int minBufferSize = AudioTrack.getMinBufferSize(
          SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
      int bufferSize = Math.max(minBufferSize * 2, 16384);

      AudioTrack.Builder builder = new AudioTrack.Builder()
          .setAudioAttributes(commAttributes)
          .setAudioFormat(stereoFormat)
          .setBufferSizeInBytes(bufferSize)
          .setTransferMode(AudioTrack.MODE_STREAM);

      try {
        track = builder.build();
        if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED && telephonyDevice != null) {
          track.setPreferredDevice(telephonyDevice);
        }
      } catch (Exception e) {
        Log.w(TAG, "Failed to build preferred telephony AudioTrack: " + e.getMessage());
      }

      if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
        if (track != null) {
          track.release();
          track = null;
        }
        try {
          track = new AudioTrack.Builder()
              .setAudioAttributes(commAttributes)
              .setAudioFormat(new AudioFormat.Builder()
                  .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                  .setSampleRate(SAMPLE_RATE)
                  .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                  .build())
              .setBufferSizeInBytes(bufferSize)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build();
        } catch (Exception e) {
          Log.w(TAG, "Fallback AudioTrack build failed: " + e.getMessage());
        }
      }

      if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
        Log.e(TAG, "Could not initialize AudioTrack for greeting playback");
        return false;
      }

      synchronized (this) {
        if (!mIsPlayingGreeting) {
          track.release();
          return false;
        }
        mAudioTrack = track;
      }

      try {
        track.setVolume(1.0f);
      } catch (Exception ignored) {}

      track.play();

      byte[] dataToWrite = (track.getChannelCount() == 2) ? stereoPcm : monoPcm;
      int offset = 0;
      int chunkSize = 4096;
      while (offset < dataToWrite.length && mIsPlayingGreeting) {
        int bytesToWrite = Math.min(chunkSize, dataToWrite.length - offset);
        int written = track.write(dataToWrite, offset, bytesToWrite);
        if (written <= 0) {
          Log.w(TAG, "AudioTrack.write returned " + written);
          break;
        }
        offset += written;
      }

      int frameSize = track.getChannelCount() * 2;
      int totalFrames = dataToWrite.length / frameSize;
      long playStart = System.currentTimeMillis();
      long maxWaitMs = ((long) totalFrames * 1000L / SAMPLE_RATE) + 800L;
      while (mIsPlayingGreeting && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
        if (track.getPlaybackHeadPosition() >= totalFrames) {
          break;
        }
        if (System.currentTimeMillis() - playStart > maxWaitMs) {
          break;
        }
        try {
          Thread.sleep(50);
        } catch (InterruptedException ignored) {}
      }

      // Small delay after tone playback completes to let the beep finish ringing out
      try {
        Thread.sleep(150);
      } catch (InterruptedException ignored) {}

      return offset >= dataToWrite.length;
    } catch (Exception e) {
      Log.e(TAG, "playPcmGreetingViaAudioTrack failed: " + e.getMessage(), e);
      return false;
    } finally {
      if (track != null) {
        try {
          track.stop();
          track.release();
        } catch (Exception ignored) {}
        synchronized (this) {
          if (mAudioTrack == track) {
            mAudioTrack = null;
          }
        }
      }
    }
  }

  private void playGreetingViaMediaPlayer(final int maxDurationSeconds) {
    boolean acousticRouting = false;
    try {
      mMediaPlayer = new MediaPlayer();
      mMediaPlayer.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build());

      AudioDeviceInfo telephonyDevice = findTelephonyAudioDevice();
      if (telephonyDevice != null) {
        Log.i(TAG, "Routing MediaPlayer greeting to telephony device: " + telephonyDevice.getProductName());
        mMediaPlayer.setPreferredDevice(telephonyDevice);
      } else {
        Log.w(TAG, "No telephony device found; activating acoustic coupling for greeting");
        try {
          mAudioManager.setSpeakerphoneOn(true);
          mAudioManager.setMicrophoneMute(false);
          acousticRouting = true;
        } catch (Exception e) {
          Log.w(TAG, "Failed to enable speakerphone: " + e.getMessage());
        }
      }

      final boolean wasAcoustic = acousticRouting;

      String customPath = LocalVoicemailPreferences.getCustomGreetingPath(mContext);
      if (customPath != null && new File(customPath).exists()) {
        mMediaPlayer.setDataSource(customPath);
      } else {
        try (AssetFileDescriptor afd =
                 mContext.getResources().openRawResourceFd(R.raw.local_voicemail_default_greeting)) {
          if (afd != null) {
            mMediaPlayer.setDataSource(
                afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
          }
        }
      }

      mMediaPlayer.setVolume(1.0f, 1.0f);
      mMediaPlayer.setOnCompletionListener(mp -> {
        Log.i(TAG, "MediaPlayer greeting playback completed");
        if (wasAcoustic) {
          try {
            mAudioManager.setSpeakerphoneOn(false);
            mAudioManager.setMicrophoneMute(true);
          } catch (Exception ignored) {}
        }
        stopPlayback();
        if (mIsSessionActive) {
          startDownlinkRecording(maxDurationSeconds);
        }
      });

      mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
        if (wasAcoustic) {
          try {
            mAudioManager.setSpeakerphoneOn(false);
            mAudioManager.setMicrophoneMute(true);
          } catch (Exception ignored) {}
        }
        stopPlayback();
        if (mIsSessionActive) {
          startDownlinkRecording(maxDurationSeconds);
        }
        return true;
      });

      mMediaPlayer.prepare();
      mMediaPlayer.start();
    } catch (Exception e) {
      Log.e(TAG, "Failed to play greeting via MediaPlayer: " + e.getMessage(), e);
      if (acousticRouting) {
        try {
          mAudioManager.setSpeakerphoneOn(false);
          mAudioManager.setMicrophoneMute(true);
        } catch (Exception ignored) {}
      }
      stopPlayback();
      if (mIsSessionActive) {
        startDownlinkRecording(maxDurationSeconds);
      }
    }
  }

  private synchronized void startDownlinkRecording(int maxDurationSeconds) {
    if (!mIsSessionActive || mIsRecording) {
      return;
    }
    stopRecordingThread();

    // Re-arm max recording duration timeout so the caller has the full configured duration
    if (mMaxDurationTimeoutRunnable != null) {
      mMainHandler.removeCallbacks(mMaxDurationTimeoutRunnable);
    }
    mMaxDurationTimeoutRunnable = () -> {
      Log.i(TAG, "Max recording duration reached (" + maxDurationSeconds + "s)");
      stopSessionAndNotify();
    };
    mMainHandler.postDelayed(mMaxDurationTimeoutRunnable, maxDurationSeconds * 1000L);

    mTempRecordingFile = new File(mContext.getFilesDir(), TEMP_RECORDING_NAME);
    if (mTempRecordingFile.exists()) {
      mTempRecordingFile.delete();
    }

    mIsRecording = true;
    if (mCallback != null) {
      mCallback.onRecordingStarted();
    }

    mRecordingThread =
        new Thread(() -> recordDownlinkAudio(maxDurationSeconds), "LocalVoicemailRecording");
    mRecordingThread.start();
  }

  private void recordDownlinkAudio(int maxDurationSeconds) {
    AudioRecord record = null;
    AudioFormat formatIn =
        new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build();

    int minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    int bufferSize = Math.max(minBuf * 2, 8192);

    // Tier 1: Android 16 getCallDownlinkExtractionAudioRecord
    try {
      record = mAudioManager.getCallDownlinkExtractionAudioRecord(formatIn);
      if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED) {
        Log.i(TAG, "AudioRecord initialized via getCallDownlinkExtractionAudioRecord");
      } else {
        if (record != null) record.release();
        record = null;
      }
    } catch (Throwable t) {
      Log.w(TAG, "getCallDownlinkExtractionAudioRecord failed: " + t.getMessage());
    }

    // Tier 2: MediaRecorder.AudioSource.VOICE_DOWNLINK
    if (record == null) {
      try {
        record = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            .setAudioFormat(formatIn)
            .setBufferSizeInBytes(bufferSize)
            .build();
        if (record.getState() == AudioRecord.STATE_INITIALIZED) {
          Log.i(TAG, "AudioRecord initialized via VOICE_DOWNLINK");
        } else {
          record.release();
          record = null;
        }
      } catch (Throwable t) {
        Log.w(TAG, "VOICE_DOWNLINK AudioRecord failed: " + t.getMessage());
      }
    }

    // Tier 3: MediaRecorder.AudioSource.VOICE_CALL
    if (record == null) {
      try {
        record = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            .setAudioFormat(formatIn)
            .setBufferSizeInBytes(bufferSize)
            .build();
        if (record.getState() == AudioRecord.STATE_INITIALIZED) {
          Log.i(TAG, "AudioRecord initialized via VOICE_CALL");
        } else {
          record.release();
          record = null;
        }
      } catch (Throwable t) {
        Log.w(TAG, "VOICE_CALL AudioRecord failed: " + t.getMessage());
      }
    }

    // Tier 4: MediaRecorder.AudioSource.VOICE_COMMUNICATION
    if (record == null) {
      try {
        record = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(formatIn)
            .setBufferSizeInBytes(bufferSize)
            .build();
        if (record.getState() == AudioRecord.STATE_INITIALIZED) {
          Log.i(TAG, "AudioRecord initialized via VOICE_COMMUNICATION");
        } else {
          record.release();
          record = null;
        }
      } catch (Throwable t) {
        Log.w(TAG, "VOICE_COMMUNICATION AudioRecord failed: " + t.getMessage());
      }
    }

    // Tier 5: MediaRecorder.AudioSource.MIC
    if (record == null) {
      try {
        record = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(formatIn)
            .setBufferSizeInBytes(bufferSize)
            .build();
        if (record.getState() == AudioRecord.STATE_INITIALIZED) {
          Log.i(TAG, "AudioRecord initialized via MIC");
        } else {
          record.release();
          record = null;
        }
      } catch (Throwable t) {
        Log.e(TAG, "MIC AudioRecord failed: " + t.getMessage());
      }
    }

    if (record == null) {
      Log.e(TAG, "All AudioRecord sources failed to initialize");
      if (mCallback != null) {
        mCallback.onError("Unable to initialize audio capture for voicemail", null);
      }
      return;
    }

    AudioDeviceInfo rxDevice = findTelephonyRxAudioDevice();
    if (rxDevice != null) {
      try {
        record.setPreferredDevice(rxDevice);
        Log.i(TAG, "AudioRecord preferred device set to telephony RX: " + rxDevice.getProductName());
      } catch (Exception ignored) {}
    }

    synchronized (this) {
      mAudioRecord = record;
    }

    long totalBytesWritten = 0;
    try (RandomAccessFile raf = new RandomAccessFile(mTempRecordingFile, "rw")) {
      byte[] emptyHeader = new byte[44];
      raf.write(emptyHeader);

      record.startRecording();
      byte[] buffer = new byte[2048];
      long startTime = System.currentTimeMillis();
      long maxDurationMs = maxDurationSeconds * 1000L;

      while (mIsRecording && (System.currentTimeMillis() - startTime < maxDurationMs)) {
        int read = record.read(buffer, 0, buffer.length);
        if (read > 0) {
          raf.write(buffer, 0, read);
          totalBytesWritten += read;
        } else if (read < 0) {
          Log.w(TAG, "AudioRecord.read error: " + read);
          break;
        }
      }

      writeWavHeader(raf, totalBytesWritten, SAMPLE_RATE, 1, 16);
      Log.i(TAG, "Voicemail recording complete: total PCM bytes=" + totalBytesWritten
          + " (approx " + (totalBytesWritten / 32000.0) + "s)");
    } catch (IOException e) {
      Log.e(TAG, "Error writing voicemail audio: " + e.getMessage(), e);
    } finally {
      try {
        record.stop();
        record.release();
      } catch (Exception ignored) {}
      synchronized (this) {
        if (mAudioRecord == record) {
          mAudioRecord = null;
        }
      }
    }
  }

  private static void writeWavHeader(
      RandomAccessFile raf, long audioLength, int sampleRate, int channels, int bitsPerSample)
      throws IOException {
    long byteRate = (long) sampleRate * channels * (bitsPerSample / 8);
    int blockAlign = channels * (bitsPerSample / 8);
    long totalDataLen = audioLength + 36;

    raf.seek(0);
    raf.writeBytes("RIFF");
    raf.write(intToLittleEndian((int) totalDataLen));
    raf.writeBytes("WAVE");
    raf.writeBytes("fmt ");
    raf.write(intToLittleEndian(16)); // Subchunk1Size for PCM
    raf.write(shortToLittleEndian((short) 1)); // AudioFormat: 1 = PCM
    raf.write(shortToLittleEndian((short) channels));
    raf.write(intToLittleEndian(sampleRate));
    raf.write(intToLittleEndian((int) byteRate));
    raf.write(shortToLittleEndian((short) blockAlign));
    raf.write(shortToLittleEndian((short) bitsPerSample));
    raf.writeBytes("data");
    raf.write(intToLittleEndian((int) audioLength));
  }

  private static byte[] intToLittleEndian(int value) {
    return new byte[] {
      (byte) (value & 0xff),
      (byte) ((value >> 8) & 0xff),
      (byte) ((value >> 16) & 0xff),
      (byte) ((value >> 24) & 0xff)
    };
  }

  private static byte[] shortToLittleEndian(short value) {
    return new byte[] {
      (byte) (value & 0xff),
      (byte) ((value >> 8) & 0xff)
    };
  }

  public void stopSessionAndNotify() {
    synchronized (this) {
      if (!mIsSessionActive) {
        return;
      }
      mIsSessionActive = false;
    }

    if (mMaxDurationTimeoutRunnable != null) {
      mMainHandler.removeCallbacks(mMaxDurationTimeoutRunnable);
      mMaxDurationTimeoutRunnable = null;
    }

    stopPlayback();

    mIsRecording = false;
    if (mRecordingThread != null) {
      try {
        mRecordingThread.join(1000);
      } catch (InterruptedException ignored) {}
      mRecordingThread = null;
    }

    restoreLocalAudio();

    long durationSeconds = 0;
    if (mTempRecordingFile != null && mTempRecordingFile.exists() && mTempRecordingFile.length() > 44) {
      long audioBytes = mTempRecordingFile.length() - 44;
      durationSeconds = Math.max(1, audioBytes / 32000L);
    }

    Log.i(TAG, "stopSessionAndNotify: file="
        + (mTempRecordingFile != null ? mTempRecordingFile.getAbsolutePath() : "null")
        + ", length=" + (mTempRecordingFile != null ? mTempRecordingFile.length() : 0)
        + ", duration=" + durationSeconds + "s");

    if (mCallback != null && mTempRecordingFile != null && mTempRecordingFile.exists() && mTempRecordingFile.length() > 44) {
      mCallback.onRecordingCompleted(mTempRecordingFile, durationSeconds);
    }
  }

  public static void commitVoicemailToProvider(
      @NonNull Context context,
      @NonNull String callerNumber,
      long durationSeconds,
      @Nullable PhoneAccountHandle accountHandle,
      @NonNull File recordingFile) {
    new Thread(() -> {
      if (!recordingFile.exists() || recordingFile.length() <= 44) {
        Log.w(TAG, "Cannot commit empty voicemail file");
        return;
      }

      try {
        ContentValues values = new ContentValues();
        values.put(Voicemails.DATE, String.valueOf(System.currentTimeMillis()));
        values.put(Voicemails.NUMBER, callerNumber);
        values.put(Voicemails.DURATION, durationSeconds);
        values.put(Voicemails.MIME_TYPE, "audio/wav");
        values.put(Voicemails.SOURCE_PACKAGE, context.getPackageName());
        values.put(Voicemails.SOURCE_DATA, "local_voicemail_" + System.currentTimeMillis());
        values.put(Voicemails.IS_READ, 0);
        values.put(Voicemails.HAS_CONTENT, 1);
        values.put(Voicemails.IS_OMTP_VOICEMAIL, 0);

        if (accountHandle != null) {
          if (accountHandle.getComponentName() != null) {
            values.put(Voicemails.PHONE_ACCOUNT_COMPONENT_NAME,
                accountHandle.getComponentName().flattenToString());
          }
          if (!TextUtils.isEmpty(accountHandle.getId())) {
            values.put(Voicemails.PHONE_ACCOUNT_ID, accountHandle.getId());
          }
        }

        Uri baseUri = Voicemails.buildSourceUri(context.getPackageName());
        Uri voicemailUri = context.getContentResolver().insert(baseUri, values);
        if (voicemailUri == null) {
          Log.e(TAG, "Failed to insert voicemail entry into provider");
          return;
        }

        try (InputStream in = new FileInputStream(recordingFile);
             OutputStream out = context.getContentResolver().openOutputStream(voicemailUri)) {
          if (out != null) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
              out.write(buffer, 0, read);
            }
            out.flush();
          }
        }

        ContentValues updateValues = new ContentValues();
        updateValues.put(Voicemails.HAS_CONTENT, 1);
        context.getContentResolver().update(voicemailUri, updateValues, null, null);

        Log.i(TAG, "Successfully committed local voicemail: " + voicemailUri
            + " (duration=" + durationSeconds + "s, size=" + recordingFile.length() + " bytes)");

        recordingFile.delete();
      } catch (IOException | SecurityException e) {
        Log.e(TAG, "Exception while writing voicemail audio to provider", e);
      }
    }).start();
  }

  private void stopPlayback() {
    mIsPlayingGreeting = false;
    synchronized (this) {
      if (mAudioTrack != null) {
        try {
          mAudioTrack.stop();
          mAudioTrack.release();
        } catch (Exception ignored) {}
        mAudioTrack = null;
      }
    }
    if (mMediaPlayer != null) {
      try {
        if (mMediaPlayer.isPlaying()) {
          mMediaPlayer.stop();
        }
        mMediaPlayer.release();
      } catch (Exception ignored) {}
      mMediaPlayer = null;
    }
  }

  private void stopRecordingThread() {
    mIsRecording = false;
    synchronized (this) {
      if (mAudioRecord != null) {
        try {
          mAudioRecord.stop();
          mAudioRecord.release();
        } catch (Exception ignored) {}
        mAudioRecord = null;
      }
    }
    if (mRecordingThread != null) {
      try {
        mRecordingThread.join(1000);
      } catch (InterruptedException ignored) {}
      mRecordingThread = null;
    }
  }

  public boolean isPlayingGreeting() {
    return mIsPlayingGreeting;
  }

  public boolean isRecording() {
    return mIsRecording;
  }
}
