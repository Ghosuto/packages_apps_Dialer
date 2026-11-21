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
import android.os.SystemClock;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.common.concurrent.DialerExecutors;
import com.android.incallui.InCallPresenter;
import com.android.incallui.audiomode.AudioModeProvider;
import com.android.incallui.call.CallList;
import com.android.incallui.call.CallRecorder;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.TelecomAdapter;
import com.android.incallui.call.state.DialerCallState;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public final class LocalVoicemailManager
    implements CallList.Listener, AudioModeProvider.AudioModeListener {
  private static final String TAG = "LocalVoicemailManager";
  private static LocalVoicemailManager sInstance;

  private final Context mContext;
  private final AudioManager mAudioManager;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final LocalVoicemailAudioController mAudioController;
  private final Set<Runnable> mListeners = new HashSet<>();
  private DialerCall mRingingCall;
  private DialerCall mActiveVoicemailCall;
  private DialerCall mUserAnsweredCall;
  private Runnable mAutoAnswerRunnable;
  private Runnable mAwaitActiveRunnable;
  private int mScheduleGeneration;
  private boolean mVoicemailSessionStarted;
  private boolean mRecording;
  private boolean mEndingSession;
  private boolean mOwnsMute;
  private boolean mPreviousMicrophoneMute;
  private boolean mPreviousTelecomMute;

  private LocalVoicemailManager(Context context) {
    mContext = context.getApplicationContext();
    mAudioManager = mContext.getSystemService(AudioManager.class);
    mAudioController = new LocalVoicemailAudioController(mContext);
  }

  public static synchronized void init(@NonNull Context context) {
    if (sInstance == null) {
      sInstance = new LocalVoicemailManager(context);
      CallList.getInstance().addListener(sInstance);
      AudioModeProvider.getInstance().addListener(sInstance);
    }
    LocalVoicemailStore.retryPending(context);
  }

  @Nullable
  public static synchronized LocalVoicemailManager getInstance() {
    return sInstance;
  }

  public void addListener(Runnable listener) {
    mListeners.add(listener);
    listener.run();
  }

  public void removeListener(Runnable listener) {
    mListeners.remove(listener);
  }

  private void notifySessionChanged() {
    for (Runnable listener : new HashSet<>(mListeners)) listener.run();
    InCallPresenter.getInstance().onAudioStateChanged(
        AudioModeProvider.getInstance().getAudioState());
  }

  public boolean isRecordingForCall(@Nullable DialerCall call) {
    return isVoicemailActiveForCall(call) && mRecording;
  }

  public boolean isEndingForCall(@Nullable DialerCall call) {
    return isVoicemailActiveForCall(call) && mEndingSession;
  }

  public boolean canPickUpVoicemail(@Nullable DialerCall call) {
    return isVoicemailActiveForCall(call) && !mEndingSession
        && call.getState() == DialerCallState.ACTIVE;
  }

  public boolean isVoicemailActiveForCall(@Nullable DialerCall call) {
    return call != null && call == mActiveVoicemailCall;
  }

  public boolean isVoicemailSessionBusy() {
    return mActiveVoicemailCall != null || mAudioController.isSessionBusy();
  }

  @Nullable
  public DialerCall getVoicemailCall() {
    return mActiveVoicemailCall;
  }

  public boolean canSendToVoicemail(@Nullable DialerCall call) {
    return call != null && canScreen(call);
  }

  public static void retryPendingVoicemails(Context context) {
    LocalVoicemailStore.retryPending(context);
  }

  private boolean canScreen(DialerCall call) {
    if (!LocalVoicemailPreferences.isEnabled(mContext)
        || call == mUserAnsweredCall || mActiveVoicemailCall != null || mAudioController.isSessionBusy()
        || call.isEmergencyCall() || !LocalVoicemailAudioController.isSupported(mContext)) return false;
    TelecomManager telecom = mContext.getSystemService(TelecomManager.class);
    if (telecom == null || call.getAccountHandle() == null) return false;
    PhoneAccount account;
    try { account = telecom.getPhoneAccount(call.getAccountHandle()); }
    catch (RuntimeException exception) {
      Log.w(TAG, "Unable to check the incoming phone account", exception);
      return false;
    }
    if (account == null || !account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
      return false;
    }
    for (DialerCall other : CallList.getInstance().getAllCalls()) {
      int state = other.getState();
      if (other != call && DialerCallState.isConnectingOrConnected(state)) return false;
    }
    return isRinging(call);
  }

  private static boolean isRinging(DialerCall call) {
    return call.getState() == DialerCallState.INCOMING
        || call.getState() == DialerCallState.CALL_WAITING;
  }

  @Override
  public void onIncomingCall(DialerCall call) {
    if (call != null) scheduleAutoAnswerIfNeeded(call);
  }

  private void scheduleAutoAnswerIfNeeded(DialerCall call) {
    if (mRingingCall == call || !canScreen(call)) return;
    cancelPendingAutoAnswer();
    mRingingCall = call;
    int generation = mScheduleGeneration;
    long deadline = SystemClock.uptimeMillis()
        + LocalVoicemailPreferences.getRingDelaySeconds(mContext) * 1000L;
    DialerExecutors.getLowPriorityThreadPool(mContext).execute(() -> {
      boolean screen = LocalVoicemailPreferences.shouldScreenCall(mContext, call.getNumber());
      mHandler.post(() -> {
        if (generation != mScheduleGeneration || mRingingCall != call) return;
        if (!screen || !canScreen(call)) {
          cancelPendingAutoAnswer();
          return;
        }
        mAutoAnswerRunnable = () -> sendToVoicemail(call);
        mHandler.postDelayed(mAutoAnswerRunnable, Math.max(0, deadline - SystemClock.uptimeMillis()));
      });
    });
  }

  public void sendToVoicemail(@NonNull DialerCall call) {
    if (!canScreen(call)) {
      if (mRingingCall == call) cancelPendingAutoAnswer();
      return;
    }
    cancelPendingAutoAnswer();
    mActiveVoicemailCall = call;
    mVoicemailSessionStarted = false;
    mRecording = false;
    mEndingSession = false;
    try {
      acquireMute();
      CallRecorder.getInstance().finishRecording();
      notifySessionChanged();
      call.answer(VideoProfile.STATE_AUDIO_ONLY);
    } catch (RuntimeException exception) {
      failSession(call, exception);
      return;
    }
    long deadline = SystemClock.uptimeMillis() + 5000;
    mAwaitActiveRunnable = new Runnable() {
      @Override
      public void run() {
        if (mActiveVoicemailCall != call) return;
        int state = call.getState();
        if (state == DialerCallState.ACTIVE && isCallAudioReady()) {
          startVoicemailSession(call);
        } else if (state == DialerCallState.DISCONNECTED || state == DialerCallState.DISCONNECTING
            || SystemClock.uptimeMillis() >= deadline) {
          failSession(call, new IllegalStateException("Call did not become active"));
        } else {
          mHandler.postDelayed(this, 100);
        }
      }
    };
    mHandler.post(mAwaitActiveRunnable);
  }

  private void startVoicemailSession(DialerCall call) {
    if (mActiveVoicemailCall != call || mVoicemailSessionStarted || mEndingSession
        || call.getState() != DialerCallState.ACTIVE || !isCallAudioReady()) return;
    mVoicemailSessionStarted = true;
    if (mAwaitActiveRunnable != null) mHandler.removeCallbacks(mAwaitActiveRunnable);
    mAwaitActiveRunnable = null;
    try {
      mAudioController.startVoicemailSession(
          TextUtils.isEmpty(call.getNumber()) ? "Unknown" : call.getNumber(),
          LocalVoicemailPreferences.getMaxDurationSeconds(mContext), call.getAccountHandle(),
          new LocalVoicemailAudioController.AudioControllerCallback() {
            @Override
            public void onGreetingStarted() { notifySessionChanged(); }

            @Override
            public void onRecordingStarted() {
              if (mActiveVoicemailCall == call) {
                mRecording = true;
                notifySessionChanged();
              }
            }

            @Override
            public void onRecordingCompleted(File file, long duration) {
              boolean owned = mActiveVoicemailCall == call;
              if (owned) endVoicemailCall(call);
              if (!owned) CallRecorder.getInstance().onCallListChange(CallList.getInstance());
              onCallListChange(CallList.getInstance());
            }

            @Override
            public void onError(String message, Exception exception) {
              boolean owned = mActiveVoicemailCall == call;
              if (owned) failSession(call, exception);
              else CallRecorder.getInstance().onCallListChange(CallList.getInstance());
              onCallListChange(CallList.getInstance());
            }
          });
    } catch (RuntimeException exception) {
      failSession(call, exception);
    }
  }

  private boolean isCallAudioReady() {
    if (mAudioManager == null) return false;
    try {
      int mode = mAudioManager.getMode();
      return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING
          || mode == AudioManager.MODE_CALL_REDIRECT;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  public void cancelVoicemailAndPickup(@NonNull DialerCall call) {
    if (!canPickUpVoicemail(call)) return;
    onUserAnswer(call);
  }

  public void onUserAnswer(@NonNull DialerCall call) {
    mUserAnsweredCall = call;
    if (mRingingCall == call) cancelPendingAutoAnswer();
    if (!isVoicemailActiveForCall(call) || mEndingSession
        || (!isRinging(call) && call.getState() != DialerCallState.ACTIVE)) return;
    mAudioController.stopSessionAndNotify();
    clearSession();
    if (!mAudioController.isSessionBusy()) {
      CallRecorder.getInstance().onCallListChange(CallList.getInstance());
    }
  }

  private void failSession(DialerCall call, Exception exception) {
    Log.w(TAG, "Local voicemail failed", exception);
    endVoicemailCall(call);
    Toast.makeText(mContext, R.string.local_voicemail_failed, Toast.LENGTH_LONG).show();
  }

  private void endVoicemailCall(DialerCall call) {
    if (mActiveVoicemailCall != call) return;
    mAudioController.stopSessionAndNotify();
    if (mAwaitActiveRunnable != null) mHandler.removeCallbacks(mAwaitActiveRunnable);
    mAwaitActiveRunnable = null;
    mEndingSession = true;
    mRecording = false;
    if (call.getState() == DialerCallState.DISCONNECTED) {
      clearSession();
      return;
    }
    if (call.getState() != DialerCallState.DISCONNECTING) call.disconnect();
    notifySessionChanged();
  }

  private void clearSession() {
    if (mAwaitActiveRunnable != null) mHandler.removeCallbacks(mAwaitActiveRunnable);
    mAwaitActiveRunnable = null;
    mActiveVoicemailCall = null;
    mVoicemailSessionStarted = false;
    mRecording = false;
    mEndingSession = false;
    restoreMicrophone();
    notifySessionChanged();
  }

  @Override
  public void onDisconnect(DialerCall call) {
    if (call == mUserAnsweredCall) mUserAnsweredCall = null;
    if (call == mRingingCall) cancelPendingAutoAnswer();
    if (call != null && call == mActiveVoicemailCall) {
      mAudioController.stopSessionAndNotify();
      clearSession();
    }
  }

  @Override
  public void onCallListChange(CallList calls) {
    if (mRingingCall != null && !isRinging(mRingingCall)) cancelPendingAutoAnswer();
    if (mActiveVoicemailCall != null) {
      DialerCall call = mActiveVoicemailCall;
      int state = call.getState();
      if (!calls.getAllCalls().contains(call) || state == DialerCallState.IDLE
          || state == DialerCallState.INVALID) {
        mAudioController.stopSessionAndNotify();
        clearSession();
      } else if (state == DialerCallState.DISCONNECTED) {
        onDisconnect(call);
      } else if (state == DialerCallState.DISCONNECTING) {
        if (!mEndingSession) endVoicemailCall(call);
      } else if (state == DialerCallState.ONHOLD && calls.getActiveCall() != null
          && calls.getActiveCall() != call) {
        mAudioController.stopSessionAndNotify();
        call.disconnect();
        clearSession();
      } else if (state == DialerCallState.ACTIVE) {
        startVoicemailSession(call);
      } else if (mVoicemailSessionStarted) {
        failSession(call, new IllegalStateException("Voicemail call is no longer active"));
      }
      return;
    }
    DialerCall incoming = calls.getIncomingCall();
    if (incoming != null) scheduleAutoAnswerIfNeeded(incoming);
  }

  @Override
  public void onAudioStateChanged(CallAudioState audio) {
    DialerCall call = mActiveVoicemailCall;
    if (call != null && audio != null && !audio.isMuted()) {
      try { silenceMicrophone(); }
      catch (RuntimeException exception) { failSession(call, exception); }
    }
  }

  private void cancelPendingAutoAnswer() {
    mScheduleGeneration++;
    if (mAutoAnswerRunnable != null) mHandler.removeCallbacks(mAutoAnswerRunnable);
    mAutoAnswerRunnable = null;
    mRingingCall = null;
  }

  private void acquireMute() {
    CallAudioState audio = AudioModeProvider.getInstance().getAudioState();
    mPreviousTelecomMute = audio != null && audio.isMuted();
    mPreviousMicrophoneMute = mAudioManager != null && mAudioManager.isMicrophoneMute();
    mOwnsMute = true;
    silenceMicrophone();
  }

  private void silenceMicrophone() {
    if (mAudioManager != null) mAudioManager.setMicrophoneMute(true);
    TelecomAdapter.getInstance().mute(true);
  }

  private void restoreMicrophone() {
    if (!mOwnsMute) return;
    mOwnsMute = false;
    try {
      if (mAudioManager != null) mAudioManager.setMicrophoneMute(mPreviousMicrophoneMute);
      TelecomAdapter.getInstance().mute(mPreviousTelecomMute);
    } catch (RuntimeException exception) {
      Log.w(TAG, "Unable to restore voicemail audio state", exception);
    }
  }

  @Override public void onUpgradeToVideo(DialerCall call) { }
  @Override public void onSessionModificationStateChange(DialerCall call) { }
  @Override public void onWiFiToLteHandover(DialerCall call) { }
  @Override public void onHandoverToWifiFailed(DialerCall call) { }
  @Override public void onInternationalCallOnWifi(@NonNull DialerCall call) { }
}
