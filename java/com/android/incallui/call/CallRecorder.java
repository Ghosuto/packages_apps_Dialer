/*
 * Copyright (C) 2014 The CyanogenMod Project
 * Copyright (C) 2023-2024 The LineageOS Project
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

package com.android.incallui.call;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import com.android.dialer.R;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.CallRecordingDataStore;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.common.concurrent.DialerExecutors;
import com.android.dialer.location.GeoUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * InCall UI's interface to the call recorder
 *
 * Manages the call recorder service lifecycle.  We bind to the service whenever an active call
 * is established, and unbind when all calls have been disconnected.
 */
public class CallRecorder implements CallList.Listener {
  public static final String TAG = "CallRecorder";

  public static final String[] REQUIRED_PERMISSIONS = new String[] {
    android.Manifest.permission.RECORD_AUDIO
  };
  private static final HashMap<String, Boolean> RECORD_ALLOWED_STATE_BY_COUNTRY = new HashMap<>();

  private static CallRecorder instance = null;
  private final CallList callList;
  private Context context;
  private boolean initialized = false;
  private ICallRecorderService service = null;
  private Pair<String, Long> pendingRecordLaunch = null;
  private boolean pendingAutoStart;
  private String recordingCallId;
  private String recordingNumber;
  private String lastActiveCallId;
  private String lastActiveNumber;
  private int autoStartRequestId;

  private final HashSet<RecordingProgressListener> progressListeners = new HashSet<>();
  private final Handler handler = new Handler(Looper.getMainLooper());

  private final ServiceConnection connection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      CallRecorder.this.service = ICallRecorderService.Stub.asInterface(service);
      Pair<String, Long> pending = CallRecorder.this.pendingRecordLaunch;
      boolean automatic = pendingAutoStart;
      CallRecorder.this.pendingRecordLaunch = null;
      if (pending != null) {
        DialerCall activeCall = callList.getActiveCall();
        if (activeCall != null && TextUtils.equals(recordingCallId, activeCall.getId())
            && TextUtils.equals(pending.first, activeCall.getNumber())
            && pending.second == activeCall.getCreationTimeMillis()) {
          if (automatic) {
            recordingCallId = null;
            recordingNumber = null;
            maybeStartRecordingAutomatically(activeCall);
          } else {
            startRecording(activeCall, false);
          }
        } else {
          recordingCallId = null;
          recordingNumber = null;
        }
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      CallRecorder.this.service = null;
      finishRecording();
    }
  };

  public static CallRecorder getInstance() {
    if (instance == null) {
      instance = new CallRecorder(CallList.getInstance());
    }
    return instance;
  }

  public boolean isEnabled() {
    return context != null && CallRecorderService.isEnabled(context);
  }

  public boolean canRecordInCurrentCountry() {
      if (!isEnabled()) {
          return false;
      }
      if (RECORD_ALLOWED_STATE_BY_COUNTRY.isEmpty()) {
          loadAllowedStates();
      }

      String currentCountryIso = GeoUtil.getCurrentCountryIso(context);
      Boolean allowedState = RECORD_ALLOWED_STATE_BY_COUNTRY.get(currentCountryIso);

      return allowedState != null && allowedState;
  }

  CallRecorder(CallList callList) {
    this.callList = callList;
    callList.addListener(this);
  }

  public void setUp(Context context) {
    this.context = context.getApplicationContext();
    onCallListChange(callList);
  }

  private void initialize() {
    if (isEnabled() && !initialized) {
      Intent serviceIntent = new Intent(context, CallRecorderService.class);
      initialized = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }
  }

  private void uninitialize() {
    autoStartRequestId++;
    pendingRecordLaunch = null;
    recordingCallId = null;
    recordingNumber = null;
    if (initialized) {
      context.unbindService(connection);
      service = null;
      initialized = false;
    }
  }

  public boolean startRecording(final String phoneNumber, final long creationTime) {
    DialerCall activeCall = callList.getActiveCall();
    if (activeCall == null || !TextUtils.equals(activeCall.getNumber(), phoneNumber)
        || activeCall.getCreationTimeMillis() != creationTime) {
      return false;
    }
    return startRecording(activeCall, false);
  }

  private boolean startRecording(DialerCall call, boolean automatic) {
    autoStartRequestId++;
    if (!isEnabled() || context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      recordingCallId = null;
      recordingNumber = null;
      return false;
    }
    initialize();
    if (!initialized) {
      return false;
    }
    if (recordingCallId != null && (!TextUtils.equals(recordingCallId, call.getId())
        || !TextUtils.equals(recordingNumber, call.getNumber()))) {
      finishRecording();
    }
    if (TextUtils.equals(recordingCallId, call.getId()) && isRecording()) {
      return true;
    }
    String phoneNumber = call.getNumber();
    long creationTime = call.getCreationTimeMillis();
    recordingCallId = call.getId();
    recordingNumber = phoneNumber;
    if (service == null) {
      pendingRecordLaunch = Pair.create(phoneNumber, creationTime);
      pendingAutoStart = automatic;
      return false;
    }

    try {
      if (service.startRecording(phoneNumber, creationTime)) {
        for (RecordingProgressListener l : progressListeners) {
          l.onStartRecording();
        }
        updateRecordingProgressTask.run();
        return true;
      } else {
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
            .show();
      }
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to start recording " + phoneNumber + ", " + new Date(creationTime), e);
    }

    recordingCallId = null;
    recordingNumber = null;
    return false;
  }

  public boolean isRecording() {
    if (service == null) {
      return false;
    }

    try {
      return service.isRecording();
    } catch (RemoteException e) {
      Log.w(TAG, "Exception checking recording status", e);
    }
    return false;
  }

  public CallRecording getActiveRecording() {
    if (service == null) {
      return null;
    }

    try {
      return service.getActiveRecording();
    } catch (RemoteException e) {
      Log.w("Exception getting active recording", e);
    }
    return null;
  }

  public void finishRecording() {
    autoStartRequestId++;
    pendingRecordLaunch = null;
    recordingCallId = null;
    recordingNumber = null;
    if (service != null) {
      try {
        final CallRecording recording = service.stopRecording();
        if (recording != null) {
          if (!TextUtils.isEmpty(recording.phoneNumber)) {
            new Thread(() -> {
              CallRecordingDataStore dataStore = new CallRecordingDataStore();
              try {
                dataStore.open(context);
                dataStore.putRecording(recording);
                context.sendBroadcast(new Intent(CallRecordingDataStore.ACTION_RECORDINGS_CHANGED)
                    .setPackage(context.getPackageName()));
              } catch (RuntimeException e) {
                Log.w(TAG, "Failed to index call recording", e);
              } finally {
                dataStore.close();
              }
            }).start();
          } else {
            // Data store is an index by number so that we can link recordings in the
            // call detail page.  If phone number is not available (conference call or
            // unknown number) then just display a toast.
            String msg = context.getResources().getString(
                R.string.call_recording_file_location, recording.fileName);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
          }
        }
      } catch (RemoteException e) {
        Log.w(TAG, "Failed to stop recording", e);
      }
    }

    for (RecordingProgressListener l : progressListeners) {
      l.onStopRecording();
    }
    handler.removeCallbacks(updateRecordingProgressTask);
  }

  //
  // Call list listener methods.
  //
  @Override
  public void onIncomingCall(DialerCall call) {
    // do nothing
  }

  @Override
  public void onCallListChange(final CallList callList) {
    if (context == null) {
      return;
    }
    DialerCall activeCall = callList.getActiveCall();
    String activeCallId = activeCall == null ? null : activeCall.getId();
    String activeNumber = activeCall == null ? null : activeCall.getNumber();
    boolean changed = !TextUtils.equals(lastActiveCallId, activeCallId)
        || !TextUtils.equals(lastActiveNumber, activeNumber);
    if (changed) {
      autoStartRequestId++;
    }
    lastActiveCallId = activeCallId;
    lastActiveNumber = activeNumber;
    if (recordingCallId != null && (!TextUtils.equals(recordingCallId, activeCallId)
        || !TextUtils.equals(recordingNumber, activeNumber))) {
      finishRecording();
    }
    if (activeCall != null) {
      initialize();
      if (changed) {
        maybeStartRecordingAutomatically(activeCall);
      }
    }
  }

  @Override
  public void onDisconnect(final DialerCall call) {
    if (TextUtils.equals(call.getId(), recordingCallId)) {
      // finish the current recording if the call gets disconnected
      finishRecording();
    }

    // tear down the service if there are no more active calls
    if (callList.getActiveCall() == null) {
      if (recordingCallId != null) {
        finishRecording();
      }
      uninitialize();
      lastActiveCallId = null;
      lastActiveNumber = null;
    }
  }

  @Override
  public void onUpgradeToVideo(DialerCall call) {}

  @Override
  public void onSessionModificationStateChange(DialerCall call) {}

  @Override
  public void onWiFiToLteHandover(DialerCall call) {}

  @Override
  public void onHandoverToWifiFailed(DialerCall call) {}

  @Override
  public void onInternationalCallOnWifi(DialerCall call) {}

  // allow clients to listen for recording progress updates
  public interface RecordingProgressListener {
    void onStartRecording();
    void onStopRecording();
    void onRecordingTimeProgress(long elapsedTimeMs);
  }

  public void addRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.add(listener);
  }

  public void removeRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.remove(listener);
  }

  private static final int UPDATE_INTERVAL = 500;

  private final Runnable updateRecordingProgressTask = new Runnable() {
    @Override
    public void run() {
      CallRecording active = getActiveRecording();
      if (active != null) {
        long elapsed = System.currentTimeMillis() - active.startRecordingTime;
        for (RecordingProgressListener l : progressListeners) {
          l.onRecordingTimeProgress(elapsed);
        }
      }
      handler.postDelayed(this, UPDATE_INTERVAL);
    }
  };

  private void loadAllowedStates() {
    XmlResourceParser parser = context.getResources().getXml(R.xml.call_record_states);
    try {
        // Consume all START_DOCUMENT which can appear more than once.
        while (parser.next() == XmlPullParser.START_DOCUMENT) {}

        parser.require(XmlPullParser.START_TAG, null, "call-record-allowed-flags");

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            parser.require(XmlPullParser.START_TAG, null, "country");

            String iso = parser.getAttributeValue(null, "iso");
            String allowed = parser.getAttributeValue(null, "allowed");
            if (iso != null && ("true".equals(allowed) || "false".equals(allowed))) {
                for (String splittedIso : iso.split(",")) {
                    RECORD_ALLOWED_STATE_BY_COUNTRY.put(
                            splittedIso.toUpperCase(Locale.US), Boolean.valueOf(allowed));
                }
            } else {
                throw new XmlPullParserException("Unexpected country specification", parser, null);
            }
        }
        Log.d(TAG, "Loaded " + RECORD_ALLOWED_STATE_BY_COUNTRY.size() + " country records");
    } catch (XmlPullParserException | IOException e) {
        Log.e(TAG, "Could not parse allowed country list", e);
        RECORD_ALLOWED_STATE_BY_COUNTRY.clear();
    } finally {
        parser.close();
    }
  }

  private static String getAutoRecordingNumber(DialerCall call) {
    return !call.isConferenceCall()
        && call.getNumberPresentation() == TelecomManager.PRESENTATION_ALLOWED
        ? call.getNumber() : null;
  }

  private void maybeStartRecordingAutomatically(DialerCall call) {
    if (!isEnabled() || context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    final int requestId = ++autoStartRequestId;
    final String callId = call.getId();
    final String callNumber = call.getNumber();
    final long creationTime = call.getCreationTimeMillis();
    final String number = getAutoRecordingNumber(call);
    DialerExecutors.getLowPriorityThreadPool(context).execute(() -> {
      final boolean enabled;
      try {
        enabled = CallRecordingPreferences.isEnabled(context, number);
      } catch (RuntimeException e) {
        Log.w(TAG, "Failed to read auto-recording preference", e);
        return;
      }
      handler.post(() -> {
        DialerCall activeCall = callList.getActiveCall();
        if (requestId == autoStartRequestId && recordingCallId == null && activeCall != null
            && TextUtils.equals(callId, activeCall.getId())
            && TextUtils.equals(callNumber, activeCall.getNumber())
            && creationTime == activeCall.getCreationTimeMillis()) {
          if (!TextUtils.equals(number, getAutoRecordingNumber(activeCall))) {
            maybeStartRecordingAutomatically(activeCall);
          } else if (enabled) {
            startRecording(activeCall, true);
          }
        }
      });
    });
  }
}
