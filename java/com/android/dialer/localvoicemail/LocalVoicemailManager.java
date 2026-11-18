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

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.incallui.audiomode.AudioModeProvider;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.TelecomAdapter;
import com.android.incallui.call.state.DialerCallState;

import java.io.File;

public class LocalVoicemailManager implements CallList.Listener, AudioModeProvider.AudioModeListener {
  private static final String TAG = "LocalVoicemailManager";

  private static LocalVoicemailManager sInstance;

  private final Context mContext;
  private final AudioManager mAudioManager;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private LocalVoicemailAudioController mAudioController;

  private DialerCall mRingingCall;
  private DialerCall mActiveVoicemailCall;
  private Runnable mAutoAnswerRunnable;
  private boolean mVoicemailSessionStarted = false;

  private LocalVoicemailManager(@NonNull Context context) {
    mContext = context.getApplicationContext();
    mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    mAudioController = new LocalVoicemailAudioController(mContext);
  }

  public static synchronized void init(@NonNull Context context) {
    if (sInstance == null) {
      sInstance = new LocalVoicemailManager(context);
      CallList.getInstance().addListener(sInstance);
      AudioModeProvider.getInstance().addListener(sInstance);
      Log.i(TAG, "LocalVoicemailManager initialized and registered to CallList and AudioModeProvider");
    }
  }

  public static synchronized LocalVoicemailManager getInstance() {
    return sInstance;
  }

  @Override
  public void onIncomingCall(DialerCall call) {
    if (call == null) return;
    if (LocalVoicemailPreferences.isEnabled(mContext)) {
      silenceMicrophone();
    }
    scheduleAutoAnswerIfNeeded(call);
  }

  private void scheduleAutoAnswerIfNeeded(@NonNull DialerCall call) {
    String number = call.getNumber();

    new Thread(() -> {
      boolean shouldScreen = LocalVoicemailPreferences.shouldScreenCall(mContext, number);
      if (!shouldScreen) {
        return;
      }

      int delaySeconds = LocalVoicemailPreferences.getRingDelaySeconds(mContext);
      Log.i(TAG, "Scheduling auto-answer for local voicemail in " + delaySeconds + "s for: " + number);

      mHandler.post(() -> {
        // Verify call is still incoming before scheduling
        if (call.getState() == DialerCallState.INCOMING
            || call.getState() == DialerCallState.CALL_WAITING) {
          mRingingCall = call;
          cancelPendingAutoAnswer();

          mAutoAnswerRunnable = () -> {
            Log.i(TAG, "Ring timeout expired; routing call to local voicemail: " + call.getId());
            sendToVoicemail(call);
          };
          mHandler.postDelayed(mAutoAnswerRunnable, delaySeconds * 1000L);
        }
      });
    }).start();
  }

  public void sendToVoicemail(@NonNull DialerCall call) {
    cancelPendingAutoAnswer();
    silenceMicrophone();

    if (call.getState() != DialerCallState.INCOMING
        && call.getState() != DialerCallState.CALL_WAITING) {
      Log.w(TAG, "Call is no longer in ringing state (" + call.getState() + "); aborting sendToVoicemail");
      return;
    }

    Log.i(TAG, "Silently answering incoming call for local voicemail recording: " + call.getId());
    mActiveVoicemailCall = call;
    mRingingCall = null;
    mVoicemailSessionStarted = false;

    // Answer silently without video
    call.answer(VideoProfile.STATE_AUDIO_ONLY);

    // Wait until call transitions to ACTIVE before starting audio greeting prompt
    scheduleVoicemailSessionWhenActive(call);
  }

  private void scheduleVoicemailSessionWhenActive(@NonNull DialerCall call) {
    mHandler.post(new Runnable() {
      private int mAttempts = 0;

      @Override
      public void run() {
        if (mActiveVoicemailCall != call || call.getState() == DialerCallState.DISCONNECTED) {
          Log.i(TAG, "Call disconnected or changed before voicemail session could start");
          return;
        }

        if (call.getState() == DialerCallState.ACTIVE) {
          startVoicemailSessionForActiveCall(call);
        } else if (mAttempts < 30) {
          mAttempts++;
          mHandler.postDelayed(this, 100);
        } else {
          Log.w(TAG, "Call did not become ACTIVE within timeout; forcing session start");
          startVoicemailSessionForActiveCall(call);
        }
      }
    });
  }

  private synchronized void startVoicemailSessionForActiveCall(@NonNull DialerCall call) {
    if (mVoicemailSessionStarted || mActiveVoicemailCall != call) {
      return;
    }
    mVoicemailSessionStarted = true;
    silenceMicrophone();

    // Small delay to let the cellular modem audio paths stabilize
    mHandler.postDelayed(() -> {
      if (mActiveVoicemailCall != call || call.getState() == DialerCallState.DISCONNECTED) {
        return;
      }

      String callerNumber = !TextUtils.isEmpty(call.getNumber()) ? call.getNumber() : "Unknown";
      int maxDuration = LocalVoicemailPreferences.getMaxDurationSeconds(mContext);
      PhoneAccountHandle accountHandle = call.getAccountHandle();

      Log.i(TAG, "Starting active local voicemail audio session for " + callerNumber);
      mAudioController.startVoicemailSession(
          callerNumber,
          maxDuration,
          new LocalVoicemailAudioController.AudioControllerCallback() {
            @Override
            public void onGreetingStarted() {
              Log.i(TAG, "Local voicemail session: greeting started");
            }

            @Override
            public void onRecordingStarted() {
              Log.i(TAG, "Local voicemail session: caller recording started");
            }

            @Override
            public void onRecordingCompleted(File recordedFile, long durationSeconds) {
              Log.i(TAG, "Local voicemail recording completed (" + durationSeconds + "s)");
              if (mActiveVoicemailCall != null) {
                mActiveVoicemailCall.disconnect();
                mActiveVoicemailCall = null;
              }
              mVoicemailSessionStarted = false;
              LocalVoicemailAudioController.commitVoicemailToProvider(
                  mContext, callerNumber, durationSeconds, accountHandle, recordedFile);
            }

            @Override
            public void onError(String message, Exception e) {
              Log.e(TAG, "Local voicemail error: " + message, e);
              if (mActiveVoicemailCall != null) {
                mActiveVoicemailCall.disconnect();
                mActiveVoicemailCall = null;
              }
              mVoicemailSessionStarted = false;
            }
          });
    }, 500);
  }

  public void cancelVoicemailAndPickup(@NonNull DialerCall call) {
    if (call == mActiveVoicemailCall) {
      Log.i(TAG, "User interrupted local voicemail; picking up call");
      mAudioController.stopSessionAndNotify();
      mActiveVoicemailCall = null;
      mVoicemailSessionStarted = false;
    }
    cancelPendingAutoAnswer();
    restoreMicrophone();
  }

  @Override
  public void onDisconnect(DialerCall call) {
    if (call == null) return;

    if (call == mRingingCall) {
      cancelPendingAutoAnswer();
      mRingingCall = null;
    }

    if (call == mActiveVoicemailCall) {
      Log.i(TAG, "Active voicemail call disconnected by remote party");
      mAudioController.stopSessionAndNotify();
      mActiveVoicemailCall = null;
      mVoicemailSessionStarted = false;
    }

    restoreMicrophone();
  }

  @Override
  public void onUpgradeToVideo(DialerCall call) {}

  @Override
  public void onSessionModificationStateChange(DialerCall call) {}

  @Override
  public void onCallListChange(CallList callList) {
    if (callList == null) return;

    DialerCall incomingCall = callList.getIncomingCall();
    if (incomingCall != null) {
      if (LocalVoicemailPreferences.isEnabled(mContext)) {
        silenceMicrophone();
      }
      if (mRingingCall == null && mActiveVoicemailCall == null) {
        scheduleAutoAnswerIfNeeded(incomingCall);
      }
    } else {
      if (mRingingCall != null && mRingingCall.getState() != DialerCallState.INCOMING
          && mRingingCall.getState() != DialerCallState.CALL_WAITING) {
        cancelPendingAutoAnswer();
        mRingingCall = null;
      }
    }

    DialerCall activeCall = callList.getActiveCall();
    if (activeCall != null && activeCall != mActiveVoicemailCall) {
      // User is on a normal active call; unmute microphone
      cancelPendingAutoAnswer();
      restoreMicrophone();
    }

    if (incomingCall == null && activeCall == null && mActiveVoicemailCall == null) {
      restoreMicrophone();
    }

    if (mActiveVoicemailCall != null) {
      if (mActiveVoicemailCall.getState() == DialerCallState.ACTIVE && !mVoicemailSessionStarted) {
        silenceMicrophone();
        startVoicemailSessionForActiveCall(mActiveVoicemailCall);
      } else if (mActiveVoicemailCall.getState() == DialerCallState.DISCONNECTED) {
        Log.i(TAG, "Active voicemail call state changed to disconnected");
        mAudioController.stopSessionAndNotify();
        mActiveVoicemailCall = null;
        mVoicemailSessionStarted = false;
        restoreMicrophone();
      }
    }
  }

  @Override
  public void onAudioStateChanged(CallAudioState audioState) {
    if (audioState == null) return;
    if (mActiveVoicemailCall != null || (mRingingCall != null && LocalVoicemailPreferences.isEnabled(mContext))) {
      if (!audioState.isMuted()) {
        Log.i(TAG, "CallAudioState unmuted while voicemail/incoming call active; re-enforcing mute");
        silenceMicrophone();
      }
    }
  }

  @Override
  public void onWiFiToLteHandover(DialerCall call) {}

  @Override
  public void onHandoverToWifiFailed(DialerCall call) {}

  @Override
  public void onInternationalCallOnWifi(@NonNull DialerCall call) {}

  private void cancelPendingAutoAnswer() {
    if (mAutoAnswerRunnable != null) {
      mHandler.removeCallbacks(mAutoAnswerRunnable);
      mAutoAnswerRunnable = null;
    }
  }

  public void silenceMicrophone() {
    try {
      if (mAudioManager != null && !mAudioManager.isMicrophoneMute()) {
        Log.i(TAG, "Muting microphone for incoming call with local voicemail enabled");
        mAudioManager.setMicrophoneMute(true);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to mute microphone via AudioManager: " + e.getMessage());
    }
    try {
      CallAudioState audioState = AudioModeProvider.getInstance().getAudioState();
      if (audioState == null || !audioState.isMuted()) {
        TelecomAdapter.getInstance().mute(true);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to mute TelecomAdapter: " + e.getMessage());
    }
  }

  public void restoreMicrophone() {
    try {
      if (mAudioManager != null && mAudioManager.isMicrophoneMute()) {
        Log.i(TAG, "Restoring microphone to unmuted state via AudioManager");
        mAudioManager.setMicrophoneMute(false);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to unmute microphone via AudioManager: " + e.getMessage());
    }
    try {
      CallAudioState audioState = AudioModeProvider.getInstance().getAudioState();
      if (audioState != null && audioState.isMuted()) {
        TelecomAdapter.getInstance().mute(false);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to unmute TelecomAdapter: " + e.getMessage());
    }
  }

  public boolean isVoicemailActiveForCall(@Nullable DialerCall call) {
    return call != null && call == mActiveVoicemailCall;
  }
}
