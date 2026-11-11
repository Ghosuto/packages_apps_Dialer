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
 * limitations under the License
 */

package com.android.dialer.callrecord;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.UserManager;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.android.dialer.R;

public final class CallRecordingPreferences {
  public static final String KEY_WARNING_PRESENTED = "recording_warning_presented";

  private static final String TAG = "CallRecordingPreferences";
  private static final String MODE_ALL = "all";
  private static final String MODE_SAVED_CONTACTS = "saved_contacts";
  private static final String MODE_UNKNOWN_NUMBERS = "unknown_numbers";

  private CallRecordingPreferences() {}

  public static SharedPreferences defaultPreferences(Context context) {
    return context.createDeviceProtectedStorageContext().getSharedPreferences(
        context.getPackageName() + "_preferences", Context.MODE_MULTI_PROCESS);
  }

  @WorkerThread
  public static boolean isEnabled(Context context, String number) {
    String key = CallRecordingNumbers.key(number, CallRecordingNumbers.countryIso(context));
    if (!TextUtils.isEmpty(key)) {
      try (PreferencesDatabase helper = new PreferencesDatabase(context);
          Cursor cursor = helper.getReadableDatabase().query("auto_recording",
              new String[] {"enabled"}, "number_key = ?", new String[] {key},
              null, null, null)) {
        if (cursor.moveToFirst()) {
          return cursor.getInt(0) != 0;
        }
      }
    }
    SharedPreferences preferences = defaultPreferences(context);
    if (!preferences.getBoolean(context.getString(R.string.call_recording_autostart_key), false)) {
      return false;
    }
    String mode = preferences.getString(
        context.getString(R.string.call_recording_autostart_mode_key), MODE_ALL);
    if (!MODE_SAVED_CONTACTS.equals(mode) && !MODE_UNKNOWN_NUMBERS.equals(mode)) {
      return true;
    }
    Boolean savedContact = isSavedContact(context, number);
    return savedContact != null && (MODE_SAVED_CONTACTS.equals(mode) == savedContact);
  }

  private static Boolean isSavedContact(Context context, String number) {
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
      return cursor == null ? null : cursor.moveToFirst();
    } catch (RuntimeException e) {
      Log.w(TAG, "Failed to look up contact for automatic recording", e);
      return null;
    }
  }

  public static void setEnabled(Context context, String number, boolean enabled) {
    String key = CallRecordingNumbers.key(number, CallRecordingNumbers.countryIso(context));
    if (TextUtils.isEmpty(key)) {
      throw new IllegalArgumentException("A phone number is required");
    }
    ContentValues values = new ContentValues(2);
    values.put("number_key", key);
    values.put("enabled", enabled ? 1 : 0);
    try (PreferencesDatabase helper = new PreferencesDatabase(context)) {
      helper.getWritableDatabase().replaceOrThrow("auto_recording", null, values);
    }
  }

  private static final class PreferencesDatabase extends SQLiteOpenHelper {
    PreferencesDatabase(Context context) {
      super(context.createDeviceProtectedStorageContext(), "call_recording_preferences.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
      db.execSQL("CREATE TABLE auto_recording (number_key TEXT PRIMARY KEY NOT NULL, "
          + "enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
  }
}
