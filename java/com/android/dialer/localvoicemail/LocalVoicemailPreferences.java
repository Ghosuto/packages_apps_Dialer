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
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserManager;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.WorkerThread;

public final class LocalVoicemailPreferences {
  private static final String TAG = "LocalVoicemailPreferences";

  public static final String PREF_ENABLED = "local_voicemail_enabled";
  public static final String PREF_RING_DELAY = "local_voicemail_ring_delay";
  public static final String PREF_MAX_DURATION = "local_voicemail_max_duration";
  public static final String PREF_FILTER = "local_voicemail_filter";
  public static final String PREF_CUSTOM_GREETING_PATH = "local_voicemail_custom_greeting_path";

  public static final String FILTER_ALL = "all";
  public static final String FILTER_SAVED_CONTACTS = "saved_contacts";
  public static final String FILTER_UNKNOWN_NUMBERS = "unknown_numbers";

  public static final int DEFAULT_RING_DELAY_SECONDS = 10;
  public static final int DEFAULT_MAX_DURATION_SECONDS = 60;

  private LocalVoicemailPreferences() {}

  public static SharedPreferences getPreferences(Context context) {
    Context deContext = context.isDeviceProtectedStorage()
        ? context
        : context.createDeviceProtectedStorageContext();
    SharedPreferences dePrefs = deContext.getSharedPreferences(
        context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS);

    // If key not present in DE storage (e.g. user toggled in CE storage before fix), migrate from CE
    if (!dePrefs.contains(PREF_ENABLED)) {
      try {
        Context ceContext = context.isDeviceProtectedStorage() ? null : context;
        if (ceContext != null) {
          SharedPreferences cePrefs = ceContext.getSharedPreferences(
              context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS);
          if (cePrefs.contains(PREF_ENABLED)) {
            boolean enabled = cePrefs.getBoolean(PREF_ENABLED, false);
            String delay = cePrefs.getString(PREF_RING_DELAY, String.valueOf(DEFAULT_RING_DELAY_SECONDS));
            String duration = cePrefs.getString(PREF_MAX_DURATION, String.valueOf(DEFAULT_MAX_DURATION_SECONDS));
            String filter = cePrefs.getString(PREF_FILTER, FILTER_ALL);
            String customGreeting = cePrefs.getString(PREF_CUSTOM_GREETING_PATH, null);

            SharedPreferences.Editor editor = dePrefs.edit()
                .putBoolean(PREF_ENABLED, enabled)
                .putString(PREF_RING_DELAY, delay)
                .putString(PREF_MAX_DURATION, duration)
                .putString(PREF_FILTER, filter);
            if (customGreeting != null) {
              editor.putString(PREF_CUSTOM_GREETING_PATH, customGreeting);
            }
            editor.apply();
          }
        }
      } catch (Exception ignored) {
        // Fallback gracefully
      }
    }
    return dePrefs;
  }

  public static boolean isEnabled(Context context) {
    return getPreferences(context).getBoolean(PREF_ENABLED, false);
  }

  public static void setEnabled(Context context, boolean enabled) {
    getPreferences(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
    if (!context.isDeviceProtectedStorage()) {
      try {
        context.getSharedPreferences(context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS)
            .edit().putBoolean(PREF_ENABLED, enabled).apply();
      } catch (Exception ignored) {}
    }
  }

  public static int getRingDelaySeconds(Context context) {
    String delayStr = getPreferences(context).getString(PREF_RING_DELAY, String.valueOf(DEFAULT_RING_DELAY_SECONDS));
    try {
      return Math.max(10, Math.min(45, Integer.parseInt(delayStr)));
    } catch (NumberFormatException e) {
      return DEFAULT_RING_DELAY_SECONDS;
    }
  }

  public static void setRingDelaySeconds(Context context, int delaySeconds) {
    String val = String.valueOf(delaySeconds);
    getPreferences(context).edit().putString(PREF_RING_DELAY, val).apply();
    if (!context.isDeviceProtectedStorage()) {
      try {
        context.getSharedPreferences(context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS)
            .edit().putString(PREF_RING_DELAY, val).apply();
      } catch (Exception ignored) {}
    }
  }

  public static int getMaxDurationSeconds(Context context) {
    String durationStr = getPreferences(context).getString(PREF_MAX_DURATION, String.valueOf(DEFAULT_MAX_DURATION_SECONDS));
    try {
      return Math.max(30, Math.min(120, Integer.parseInt(durationStr)));
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_DURATION_SECONDS;
    }
  }

  public static void setMaxDurationSeconds(Context context, int maxDurationSeconds) {
    String val = String.valueOf(maxDurationSeconds);
    getPreferences(context).edit().putString(PREF_MAX_DURATION, val).apply();
    if (!context.isDeviceProtectedStorage()) {
      try {
        context.getSharedPreferences(context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS)
            .edit().putString(PREF_MAX_DURATION, val).apply();
      } catch (Exception ignored) {}
    }
  }

  public static String getFilterMode(Context context) {
    return getPreferences(context).getString(PREF_FILTER, FILTER_ALL);
  }

  public static void setFilterMode(Context context, String filterMode) {
    getPreferences(context).edit().putString(PREF_FILTER, filterMode).apply();
    if (!context.isDeviceProtectedStorage()) {
      try {
        context.getSharedPreferences(context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS)
            .edit().putString(PREF_FILTER, filterMode).apply();
      } catch (Exception ignored) {}
    }
  }

  public static String getCustomGreetingPath(Context context) {
    return getPreferences(context).getString(PREF_CUSTOM_GREETING_PATH, null);
  }

  public static void setCustomGreetingPath(Context context, String path) {
    if (path == null) {
      getPreferences(context).edit().remove(PREF_CUSTOM_GREETING_PATH).apply();
      if (!context.isDeviceProtectedStorage()) {
        try {
          context.getSharedPreferences(context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS)
              .edit().remove(PREF_CUSTOM_GREETING_PATH).apply();
        } catch (Exception ignored) {}
      }
    } else {
      getPreferences(context).edit().putString(PREF_CUSTOM_GREETING_PATH, path).apply();
      if (!context.isDeviceProtectedStorage()) {
        try {
          context.getSharedPreferences(context.getPackageName() + "_local_voicemail", Context.MODE_MULTI_PROCESS)
              .edit().putString(PREF_CUSTOM_GREETING_PATH, path).apply();
        } catch (Exception ignored) {}
      }
    }
  }

  @WorkerThread
  public static boolean shouldScreenCall(Context context, String number) {
    if (!isEnabled(context)) {
      return false;
    }

    String mode = getFilterMode(context);
    if (FILTER_ALL.equals(mode)) {
      return true;
    }

    Boolean savedContact = isSavedContact(context, number);
    if (savedContact == null) {
      return false;
    }

    if (FILTER_SAVED_CONTACTS.equals(mode)) {
      return savedContact;
    } else if (FILTER_UNKNOWN_NUMBERS.equals(mode)) {
      return !savedContact;
    }

    return true;
  }

  private static Boolean isSavedContact(Context context, String number) {
    if (TextUtils.isEmpty(number)) {
      return false;
    }
    boolean sipAddress = PhoneNumberUtils.isUriNumber(number);
    String lookupNumber = sipAddress ? number : PhoneNumberUtils.extractNetworkPortion(number);
    if (TextUtils.isEmpty(lookupNumber)) {
      return false;
    }
    if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
        != PackageManager.PERMISSION_GRANTED) {
      return null;
    }
    UserManager userManager = context.getSystemService(UserManager.class);
    if (userManager != null && !userManager.isUserUnlocked()) {
      return null;
    }
    Uri uri = PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI.buildUpon()
        .appendPath(lookupNumber)
        .appendQueryParameter(PhoneLookup.QUERY_PARAMETER_SIP_ADDRESS, String.valueOf(sipAddress))
        .build();
    try (Cursor cursor = context.getContentResolver().query(
        uri, new String[] {PhoneLookup._ID}, null, null, null)) {
      return cursor != null && cursor.moveToFirst();
    } catch (RuntimeException e) {
      Log.w(TAG, "Failed to look up contact for local voicemail filtering", e);
      return null;
    }
  }
}
