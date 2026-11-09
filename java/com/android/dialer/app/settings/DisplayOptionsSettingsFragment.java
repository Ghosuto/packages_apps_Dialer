/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.dialer.app.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.PreferenceManager;

import com.android.dialer.R;
import com.android.dialer.expressive.CallerScreenStylePreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DisplayOptionsSettingsFragment extends com.android.dialer.expressive.ExpressivePreferenceFragment
    implements Preference.OnPreferenceChangeListener {

  private SharedPreferences mPrefs;
  private ListPreference mCallerScreenStyle;
  private MultiSelectListPreference mHiddenNavItems;

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
    String callerStyle = CallerScreenStylePreferences.readAndMigrate(mPrefs);
    addPreferencesFromResource(R.xml.display_options_settings);

    findPreference(getString(R.string.display_options_sort_list_by_key)).setVisible(
        getResources().getBoolean(R.bool.config_sort_order_user_changeable));
    findPreference(getString(R.string.display_options_view_names_as_key)).setVisible(
        getResources().getBoolean(R.bool.config_display_order_user_changeable));

    Preference callerPreference = findPreference(CallerScreenStylePreferences.KEY);
    if (callerPreference instanceof ListPreference) {
      mCallerScreenStyle = (ListPreference) callerPreference;
      mCallerScreenStyle.setValue(callerStyle);
      mCallerScreenStyle.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      mCallerScreenStyle.setOnPreferenceChangeListener(this);
    }

    Preference hiddenNavPref = findPreference("hidden_nav_items");
    if (hiddenNavPref instanceof MultiSelectListPreference) {
      mHiddenNavItems = (MultiSelectListPreference) hiddenNavPref;
      updateHiddenNavItemsSummary(mHiddenNavItems.getValues());
      mHiddenNavItems.setOnPreferenceChangeListener(this);
    }
  }

  private void updateHiddenNavItemsSummary(Set<String> values) {
    if (mHiddenNavItems == null) {
      return;
    }
    if (values == null || values.isEmpty()) {
      mHiddenNavItems.setSummary(R.string.hidden_nav_items_summary);
      return;
    }
    List<String> labels = new ArrayList<>();
    CharSequence[] entries = mHiddenNavItems.getEntries();
    CharSequence[] entryValues = mHiddenNavItems.getEntryValues();
    if (entries != null && entryValues != null) {
      for (int i = 0; i < entryValues.length; i++) {
        if (values.contains(entryValues[i].toString())) {
          labels.add(entries[i].toString());
        }
      }
    }
    if (labels.isEmpty()) {
      mHiddenNavItems.setSummary(R.string.hidden_nav_items_summary);
    } else {
      mHiddenNavItems.setSummary(String.join(", ", labels));
    }
  }

  @Override
  public boolean onPreferenceChange(Preference preference, Object objValue) {
    if (preference == mCallerScreenStyle && objValue instanceof String) {
      return mCallerScreenStyle.findIndexOfValue((String) objValue) >= 0;
    }
    if (preference == mHiddenNavItems && objValue instanceof Set) {
      @SuppressWarnings("unchecked")
      Set<String> selected = (Set<String>) objValue;
      if (selected.size() > 2) {
        Toast.makeText(requireContext(), R.string.hidden_nav_items_max_limit_toast, Toast.LENGTH_SHORT).show();
        return false;
      }
      updateHiddenNavItemsSummary(selected);
      return true;
    }
    return false;
  }
}
