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
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.ComponentDialog;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.android.dialer.R;
import com.android.dialer.expressive.ExpressivePreferenceFragment;
import com.android.dialer.expressive.ExpressiveDialogs;

import java.io.File;

public class LocalVoicemailSettingsFragment extends ExpressivePreferenceFragment
    implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

  private SwitchPreference mEnabledPref;
  private ListPreference mRingDelayPref;
  private ListPreference mMaxDurationPref;
  private ListPreference mFilterPref;
  private Preference mGreetingPref;
  private Preference mRecordGreetingPref;
  private Preference mResetGreetingPref;
  private LocalVoicemailGreetingRecorder mGreetingRecorder;
  private ComponentDialog mGreetingDialog;
  private boolean mRequestEnable;
  private final ActivityResultLauncher<String> mMicrophonePermission = registerForActivityResult(
      new ActivityResultContracts.RequestPermission(), granted -> {
        if (!isAdded()) return;
        if (granted) {
          if (mRequestEnable && LocalVoicemailAudioController.isSupported(requireContext())) {
            LocalVoicemailPreferences.setEnabled(requireContext(), true);
            mEnabledPref.setChecked(true);
          } else if (!mRequestEnable) {
            startGreetingRecording();
          }
        } else {
          Toast.makeText(requireContext(), R.string.local_voicemail_unsupported, Toast.LENGTH_LONG).show();
        }
        mRequestEnable = false;
      });

  private MediaPlayer mMediaPlayer;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mRequestEnable = savedInstanceState != null
        && savedInstanceState.getBoolean("request_enable_voicemail");
    getPreferenceManager().setStorageDeviceProtected();
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("request_enable_voicemail", mRequestEnable);
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    getPreferenceManager().setStorageDeviceProtected();
    getPreferenceManager().setSharedPreferencesName(
        requireContext().getPackageName() + "_local_voicemail");
    getPreferenceManager().setSharedPreferencesMode(Context.MODE_MULTI_PROCESS);

    addPreferencesFromResource(R.xml.local_voicemail_settings);

    mEnabledPref = findPreference(LocalVoicemailPreferences.PREF_ENABLED);
    mRingDelayPref = findPreference(LocalVoicemailPreferences.PREF_RING_DELAY);
    mMaxDurationPref = findPreference(LocalVoicemailPreferences.PREF_MAX_DURATION);
    mFilterPref = findPreference(LocalVoicemailPreferences.PREF_FILTER);
    mGreetingPref = findPreference("local_voicemail_greeting");
    mRecordGreetingPref = findPreference("local_voicemail_record_greeting");
    mResetGreetingPref = findPreference("local_voicemail_reset_greeting");
    if (mRecordGreetingPref != null) mRecordGreetingPref.setOnPreferenceClickListener(this);
    if (mResetGreetingPref != null) mResetGreetingPref.setOnPreferenceClickListener(this);

    if (mEnabledPref != null) {
      mEnabledPref.setOnPreferenceChangeListener(this);
    }

    if (mRingDelayPref != null) {
      mRingDelayPref.setOnPreferenceChangeListener(this);
      updateListSummary(mRingDelayPref, mRingDelayPref.getValue());
    }

    if (mMaxDurationPref != null) {
      mMaxDurationPref.setOnPreferenceChangeListener(this);
      updateListSummary(mMaxDurationPref, mMaxDurationPref.getValue());
    }

    if (mFilterPref != null) {
      mFilterPref.setOnPreferenceChangeListener(this);
      updateListSummary(mFilterPref, mFilterPref.getValue());
    }

    if (mGreetingPref != null) {
      mGreetingPref.setOnPreferenceClickListener(this);
      updateGreetingSummary();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    Context context = getContext();
    if (context == null) return;

    if (mEnabledPref != null) {
      mEnabledPref.setChecked(LocalVoicemailPreferences.isEnabled(context));
      boolean supported = LocalVoicemailAudioController.hasCallAudioSupport(context);
      mEnabledPref.setEnabled(supported || mEnabledPref.isChecked());
      mEnabledPref.setSummary(supported ? R.string.local_voicemail_enable_summary
          : R.string.local_voicemail_unsupported);
    }
    if (mRingDelayPref != null) {
      String delay = String.valueOf(LocalVoicemailPreferences.getRingDelaySeconds(context));
      mRingDelayPref.setValue(delay);
      updateListSummary(mRingDelayPref, delay);
    }
    if (mMaxDurationPref != null) {
      String duration = String.valueOf(LocalVoicemailPreferences.getMaxDurationSeconds(context));
      mMaxDurationPref.setValue(duration);
      updateListSummary(mMaxDurationPref, duration);
    }
    if (mFilterPref != null) {
      String filter = LocalVoicemailPreferences.getFilterMode(context);
      mFilterPref.setValue(filter);
      updateListSummary(mFilterPref, filter);
    }
    updateGreetingSummary();
  }

  @Override
  public boolean onPreferenceChange(Preference preference, Object newValue) {
    Context context = requireContext();
    if (preference == mEnabledPref) {
      boolean enabled = (Boolean) newValue;
      if (enabled && !LocalVoicemailAudioController.hasCallAudioSupport(context)) return false;
      if (enabled && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED) {
        mRequestEnable = true;
        mMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO);
        return false;
      }
      LocalVoicemailPreferences.setEnabled(context, enabled);
      return true;
    } else if (preference == mRingDelayPref) {
      String delayVal = (String) newValue;
      try {
        LocalVoicemailPreferences.setRingDelaySeconds(context, Integer.parseInt(delayVal));
      } catch (NumberFormatException ignored) {}
      updateListSummary(mRingDelayPref, delayVal);
      return true;
    } else if (preference == mMaxDurationPref) {
      String durVal = (String) newValue;
      try {
        LocalVoicemailPreferences.setMaxDurationSeconds(context, Integer.parseInt(durVal));
      } catch (NumberFormatException ignored) {}
      updateListSummary(mMaxDurationPref, durVal);
      return true;
    } else if (preference == mFilterPref) {
      String filterVal = (String) newValue;
      LocalVoicemailPreferences.setFilterMode(context, filterVal);
      updateListSummary(mFilterPref, filterVal);
      return true;
    }
    return true;
  }

  @Override
  public boolean onPreferenceClick(Preference preference) {
    if (preference == mRecordGreetingPref) {
      if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED) {
        mRequestEnable = false;
        mMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO);
      } else {
        startGreetingRecording();
      }
      return true;
    }
    if (preference == mResetGreetingPref) {
      if (mGreetingRecorder != null) return true;
      stopPreview();
      String old = LocalVoicemailPreferences.getCustomGreetingPath(requireContext());
      LocalVoicemailPreferences.setCustomGreetingPath(requireContext(), null);
      if (old != null) new File(old).delete();
      updateGreetingSummary();
      Toast.makeText(requireContext(), R.string.local_voicemail_greeting_reset_done, Toast.LENGTH_SHORT).show();
      return true;
    }
    if ("local_voicemail_greeting".equals(preference.getKey())) {
      toggleGreetingPreview();
      return true;
    }
    return false;
  }

  private void startGreetingRecording() {
    if (mGreetingRecorder != null) return;
    stopPreview();
    mRecordGreetingPref.setEnabled(false);
    final LocalVoicemailGreetingRecorder[] request = new LocalVoicemailGreetingRecorder[1];
    request[0] = new LocalVoicemailGreetingRecorder(requireContext(),
        new LocalVoicemailGreetingRecorder.Callback() {
          @Override
          public void onStarted() {
            if (!isResumed() || mGreetingRecorder != request[0]) {
              request[0].stop(false);
              return;
            }
            mGreetingDialog = ExpressiveDialogs.showGreetingRecording(
                LocalVoicemailSettingsFragment.this,
                () -> { if (mGreetingRecorder != null) mGreetingRecorder.stop(true); },
                () -> cancelGreetingRecording());
            if (mGreetingDialog == null) cancelGreetingRecording();
          }

          @Override
          public void onCompleted(File greeting, Exception exception) {
            if (mGreetingRecorder != request[0]) {
              if (mGreetingRecorder == null && mRecordGreetingPref != null) mRecordGreetingPref.setEnabled(true);
              return;
            }
            boolean accepted = isResumed();
            mGreetingRecorder = null;
            if (mGreetingDialog != null) mGreetingDialog.dismiss();
            mGreetingDialog = null;
            if (mRecordGreetingPref != null) mRecordGreetingPref.setEnabled(true);
            if (!accepted) {
              return;
            }
            if (greeting != null && exception == null) {
              updateGreetingSummary();
              Toast.makeText(requireContext(), R.string.local_voicemail_greeting_recorded_toast, Toast.LENGTH_SHORT).show();
            }
          }
        });
    mGreetingRecorder = request[0];
    mGreetingRecorder.start();
  }

  private void cancelGreetingRecording() {
    if (mGreetingRecorder != null) mGreetingRecorder.stop(false);
    mGreetingRecorder = null;
    if (mGreetingDialog != null) mGreetingDialog.dismiss();
    mGreetingDialog = null;
  }

  private void updateListSummary(ListPreference listPreference, String value) {
    int index = listPreference.findIndexOfValue(value);
    if (index >= 0) {
      CharSequence entry = listPreference.getEntries()[index];
      listPreference.setSummary(listPreference == mRingDelayPref
          ? getString(R.string.local_voicemail_ring_delay_summary, entry) : entry);
    }
  }

  private void updateGreetingSummary() {
    if (mGreetingPref == null) return;
    String customPath = LocalVoicemailPreferences.getCustomGreetingPath(requireContext());
    if (customPath != null && new File(customPath).exists()) {
      mGreetingPref.setSummary(R.string.local_voicemail_greeting_recorded_toast);
    } else {
      mGreetingPref.setSummary(R.string.local_voicemail_greeting_play);
    }
  }

  private void toggleGreetingPreview() {
    if (mGreetingRecorder != null) return;
    if (mMediaPlayer != null) {
      stopPreview();
      return;
    }

    Context context = requireContext();
    String customPath = LocalVoicemailPreferences.getCustomGreetingPath(context);
    try {
      mMediaPlayer = new MediaPlayer();
      if (customPath != null && new File(customPath).exists()) {
        mMediaPlayer.setDataSource(customPath);
      } else {
        try (AssetFileDescriptor afd =
                 context.getResources().openRawResourceFd(R.raw.local_voicemail_default_greeting)) {
          if (afd != null) {
            mMediaPlayer.setDataSource(
                afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
          }
        }
      }
      MediaPlayer player = mMediaPlayer;
      player.setOnPreparedListener(mp -> { if (mMediaPlayer == mp) mp.start(); });
      player.setOnCompletionListener(mp -> { if (mMediaPlayer == mp) stopPreview(); });
      player.setOnErrorListener((mp, what, extra) -> {
        if (mMediaPlayer == mp) stopPreview();
        return true;
      });
      player.prepareAsync();
      Toast.makeText(context, R.string.local_voicemail_greeting_play, Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
      stopPreview();
    }
  }

  private void stopPreview() {
    MediaPlayer player = mMediaPlayer;
    mMediaPlayer = null;
    if (player != null) {
      try { player.release(); } catch (RuntimeException ignored) { }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    cancelGreetingRecording();
    stopPreview();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    stopPreview();
  }
}
