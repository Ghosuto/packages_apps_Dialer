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
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.android.dialer.R;
import com.android.dialer.expressive.ExpressivePreferenceFragment;

import java.io.File;

public class LocalVoicemailSettingsFragment extends ExpressivePreferenceFragment
    implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

  private SwitchPreference mEnabledPref;
  private ListPreference mRingDelayPref;
  private ListPreference mMaxDurationPref;
  private ListPreference mFilterPref;
  private Preference mGreetingPref;

  private MediaPlayer mMediaPlayer;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getPreferenceManager().setStorageDeviceProtected();
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
    if ("local_voicemail_greeting".equals(preference.getKey())) {
      toggleGreetingPreview();
      return true;
    }
    return false;
  }

  private void updateListSummary(ListPreference listPreference, String value) {
    int index = listPreference.findIndexOfValue(value);
    if (index >= 0) {
      listPreference.setSummary(listPreference.getEntries()[index]);
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
    if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
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
      mMediaPlayer.setOnCompletionListener(mp -> stopPreview());
      mMediaPlayer.prepare();
      mMediaPlayer.start();
      Toast.makeText(context, R.string.local_voicemail_greeting_play, Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
      stopPreview();
    }
  }

  private void stopPreview() {
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

  @Override
  public void onPause() {
    super.onPause();
    stopPreview();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    stopPreview();
  }
}
