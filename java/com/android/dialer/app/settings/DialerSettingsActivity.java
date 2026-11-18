/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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
package com.android.dialer.app.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.BlockedNumberContract;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.android.dialer.R;
import com.android.dialer.app.BaseActivity;
import com.android.dialer.common.LogUtil;
import com.android.dialer.compat.telephony.TelephonyManagerCompat;
import com.android.dialer.expressive.CallStatsActivity;
import com.android.dialer.expressive.QuickResponsesActivity;
import com.android.dialer.lookup.LookupSettingsFragment;
import com.android.dialer.util.PermissionsUtil;

/** Activity for dialer settings. */
public class DialerSettingsActivity extends BaseActivity implements
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

  protected SharedPreferences preferences;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    LogUtil.enterBlock("DialerSettingsActivity.onCreate");
    super.onCreate(savedInstanceState);
    // Keep existing settings deep links and manifest entry points in the Compose UI.
    Intent expressive = new Intent(this,
        com.android.dialer.expressive.ExpressiveSettingsActivity.class);
    expressive.setData(getIntent().getData());
    startActivity(expressive);
    finish();
  }

  @Override
  public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller,
                                           @NonNull Preference pref) {
    Fragment fragment = getSupportFragmentManager()
            .getFragmentFactory()
            .instantiate(getClassLoader(), pref.getFragment());
    fragment.setArguments(pref.getExtras());
    getSupportFragmentManager().beginTransaction()
            .replace(R.id.content_frame, fragment, "")
            .addToBackStack(null)
            .commit();
    setTitle(pref.getTitle());
    return true;
  }

  public static class PrefsFragment extends com.android.dialer.expressive.ExpressivePreferenceFragment implements
          Preference.OnPreferenceClickListener {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
      setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));

      // Caller layout is configurable even in locales with a fixed contact name order.
      Preference displayOptions = new Preference(getContext());
      displayOptions.setTitle(R.string.display_options_title);
      displayOptions.setSummary(R.string.expressive_settings_display_summary);
      displayOptions.setFragment(DisplayOptionsSettingsFragment.class.getName());
      displayOptions.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(displayOptions);

      Preference soundSettings = new Preference(getContext());
      soundSettings.setTitle(R.string.sounds_and_vibration_title);
      soundSettings.setSummary(R.string.expressive_settings_sound_summary);
      soundSettings.setFragment(SoundSettingsFragment.class.getName());
      soundSettings.setViewId(R.id.settings_header_sounds_and_vibration);
      soundSettings.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(soundSettings);

      Preference quickResponseSettings = new Preference(getContext());
      Intent quickResponseSettingsIntent =
              new Intent(requireContext(), QuickResponsesActivity.class);
      quickResponseSettings.setTitle(R.string.respond_via_sms_setting_title);
      quickResponseSettings.setSummary(R.string.expressive_settings_responses_summary);
      quickResponseSettings.setIntent(quickResponseSettingsIntent);
      quickResponseSettings.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(quickResponseSettings);

      final Preference lookupSettings = new Preference(getContext());
      lookupSettings.setTitle(R.string.lookup_settings_label);
      lookupSettings.setSummary(R.string.expressive_settings_lookup_summary);
      lookupSettings.setFragment(LookupSettingsFragment.class.getName());
      lookupSettings.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(lookupSettings);

      TelephonyManager telephonyManager = getContext().getSystemService(TelephonyManager.class);
      TelecomManager telecomManager = (TelecomManager) getContext().getSystemService(
              Context.TELECOM_SERVICE);

      // "Call Settings" (full settings) is shown if the current user is primary user and there
      // is only one SIM. Otherwise, "Calling accounts" is shown.
      boolean isPrimaryUser = isPrimaryUser();
      if (isPrimaryUser && TelephonyManagerCompat.getPhoneCount(telephonyManager) <= 1) {
        Preference callSettings = new Preference(getContext());
        Intent callSettingsIntent = new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS);
        callSettingsIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        callSettings.setTitle(R.string.call_settings_label);
        callSettings.setSummary(R.string.expressive_settings_calls_summary);
        callSettings.setIntent(callSettingsIntent);
        callSettings.setIconSpaceReserved(false);
        getPreferenceScreen().addPreference(callSettings);
      } else {
        Preference phoneAccountSettings = new Preference(getContext());
        Intent phoneAccountSettingsIntent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
        phoneAccountSettingsIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        phoneAccountSettings.setTitle(R.string.phone_account_settings_label);
        phoneAccountSettings.setSummary(R.string.expressive_settings_accounts_summary);
        phoneAccountSettings.setIntent(phoneAccountSettingsIntent);
        phoneAccountSettings.setIconSpaceReserved(false);
        getPreferenceScreen().addPreference(phoneAccountSettings);
      }
      if (BlockedNumberContract.canCurrentUserBlockNumbers(getContext())) {
        Preference blockedCalls = new Preference(getContext());
        blockedCalls.setTitle(R.string.manage_blocked_numbers_label);
        blockedCalls.setSummary(R.string.expressive_settings_blocked_summary);
        blockedCalls.setIntent(getContext().getSystemService(TelecomManager.class)
                .createManageBlockedNumbersIntent());
        blockedCalls.setIconSpaceReserved(false);
        getPreferenceScreen().addPreference(blockedCalls);
      }

      Preference localVoicemailSettings = new Preference(getContext());
      localVoicemailSettings.setTitle(R.string.local_voicemail_title);
      localVoicemailSettings.setSummary(R.string.local_voicemail_summary);
      localVoicemailSettings.setFragment(
          com.android.dialer.localvoicemail.LocalVoicemailSettingsFragment.class.getName());
      localVoicemailSettings.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(localVoicemailSettings);

      if (isPrimaryUser
              && (TelephonyManagerCompat.isTtyModeSupported(telecomManager)
              || TelephonyManagerCompat.isHearingAidCompatibilitySupported(telephonyManager))) {
        Preference accessibilitySettings = new Preference(getContext());
        Intent accessibilitySettingsIntent =
                new Intent(TelecomManager.ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS);
        accessibilitySettings.setTitle(R.string.accessibility_settings_title);
        accessibilitySettings.setSummary(R.string.expressive_settings_accessibility_summary);
        accessibilitySettings.setIntent(accessibilitySettingsIntent);
        accessibilitySettings.setIconSpaceReserved(false);
        getPreferenceScreen().addPreference(accessibilitySettings);
      }

      final Preference otherSettings = new Preference(getContext());
      otherSettings.setTitle(R.string.other_settings_label);
      otherSettings.setSummary(R.string.expressive_settings_other_summary);
      otherSettings.setFragment(OtherSettingsFragment.class.getName());
      otherSettings.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(otherSettings);

      Preference voipSettings = new Preference(getContext());
      voipSettings.setTitle(R.string.voip_calls_title);
      voipSettings.setSummary(R.string.voip_calls_summary);
      voipSettings.setFragment(com.android.dialer.voip.VoipSettingsFragment.class.getName());
      voipSettings.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(voipSettings);

      final Preference callHistoryBackup = new Preference(getContext());
      callHistoryBackup.setTitle(R.string.call_history_backup_title);
      callHistoryBackup.setSummary(R.string.call_history_backup_summary);
      callHistoryBackup.setFragment(CallHistoryBackupFragment.class.getName());
      callHistoryBackup.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(callHistoryBackup);

      Preference callStatistics = new Preference(getContext());
      callStatistics.setKey("call_statistics");
      callStatistics.setTitle(R.string.call_statistics_title);
      callStatistics.setSummary(R.string.call_statistics_summary);
      callStatistics.setIntent(new Intent(requireContext(), CallStatsActivity.class));
      callStatistics.setIconSpaceReserved(false);
      getPreferenceScreen().addPreference(callStatistics);
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      if (preference.getTitle().equals(getString(R.string.sounds_and_vibration_title))) {
        // If we don't have the permission to write to system settings, go to system sound
        // settings instead. Otherwise, perform the super implementation (which launches our
        // own preference fragment.
        if (!Settings.System.canWrite(getContext())) {
          Toast.makeText(getContext(),
                          getResources().getString(R.string.toast_cannot_write_system_settings),
                          Toast.LENGTH_SHORT)
                  .show();
          startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
          return true;
        }
      }
      super.onPreferenceTreeClick(preference);
      return false;
    }

    /**
     * @return Whether the current user is the primary user.
     */
    private boolean isPrimaryUser() {
      return requireContext().getSystemService(UserManager.class).isSystemUser();
    }
  }
}
