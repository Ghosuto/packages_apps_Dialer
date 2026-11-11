/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.dialer.R
import com.android.dialer.expressive.ExpressivePreferenceFragment

class VoipSettingsFragment : ExpressivePreferenceFragment() {
    private lateinit var access: Preference
    private lateinit var notifications: Preference
    private lateinit var recording: SwitchPreferenceCompat
    private lateinit var autoRecording: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen.addPreference(SwitchPreferenceCompat(context).apply {
            key = VoipCalls.ENABLED
            setTitle(R.string.voip_calls_title)
            setSummary(R.string.voip_calls_summary)
            setDefaultValue(false)
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, enabled ->
                if (enabled == true) {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, VoipNotificationListener::class.java))
                    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    }
                } else {
                    VoipRecordingService.stop(context)
                }
                true
            }
        })
        access = Preference(context).apply {
            setTitle(R.string.voip_notification_access)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, VoipNotificationListener::class.java).flattenToString())
                if (intent.resolveActivity(context.packageManager) != null) startActivity(intent)
                else startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                true
            }
        }
        preferenceScreen.addPreference(access)
        notifications = Preference(context).apply {
            setTitle(R.string.voip_notifications)
            isIconSpaceReserved = false
            intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        preferenceScreen.addPreference(notifications)
        recording = SwitchPreferenceCompat(context).apply {
            key = VoipCalls.RECORDING_ENABLED
            setTitle(R.string.voip_recording_title)
            setSummary(R.string.voip_recording_summary)
            setDefaultValue(false)
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, enabled ->
                if (enabled != true) VoipRecordingService.stop(context)
                true
            }
        }
        preferenceScreen.addPreference(recording)
        recording.dependency = VoipCalls.ENABLED
        autoRecording = SwitchPreferenceCompat(context).apply {
            key = VoipCalls.AUTO_RECORDING_ENABLED
            setTitle(R.string.voip_auto_recording_title)
            setSummary(R.string.voip_auto_recording_summary)
            setDefaultValue(false)
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, enabled ->
                val missing = recordingPermissions.filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (enabled == true && missing.isNotEmpty()) {
                    requestPermissions(missing.toTypedArray(), AUTO_RECORDING_PERMISSIONS)
                    false
                } else true
            }
        }
        preferenceScreen.addPreference(autoRecording)
        autoRecording.dependency = VoipCalls.RECORDING_ENABLED
        preferenceScreen.addPreference(Preference(context).apply {
            setTitle(R.string.voip_recordings_title)
            setSummary(R.string.voip_recordings_summary)
            isIconSpaceReserved = false
            intent = Intent(context, VoipRecordingsActivity::class.java)
        })
        preferenceScreen.addPreference(Preference(context).apply {
            setTitle(R.string.voip_compatibility_title)
            setSummary(R.string.voip_compatibility_summary)
            isIconSpaceReserved = false
            isSelectable = false
        })
    }

    override fun onResume() {
        super.onResume()
        access.setSummary(if (VoipCalls.hasNotificationAccess(requireContext()))
            R.string.voip_access_granted else R.string.voip_access_needed)
        val hasPermission = requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        notifications.setSummary(if (hasPermission) R.string.voip_notifications_summary
            else R.string.voip_notifications_needed)
        val available = VoipRecordingService.hasSystemPermissions(requireContext())
        recording.isEnabled = available
        autoRecording.isEnabled = available &&
            VoipRecordingService.hasBackgroundRecordingPermission(requireContext())
        recording.setSummary(if (available) R.string.voip_recording_summary
            else R.string.voip_recording_unavailable)
        updateAutoRecordingSummary()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUTO_RECORDING_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
                recordingPermissions.all {
                    requireContext().checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                } && autoRecording.isEnabled) {
                autoRecording.isChecked = true
            }
            updateAutoRecordingSummary()
        }
    }

    private fun updateAutoRecordingSummary() {
        val context = requireContext()
        autoRecording.setSummary(when {
            !VoipRecordingService.hasSystemPermissions(context) ||
                !VoipRecordingService.hasBackgroundRecordingPermission(context) ->
                    R.string.voip_auto_recording_unavailable
            recordingPermissions.any {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            } -> R.string.voip_auto_recording_permissions_needed
            else -> R.string.voip_auto_recording_summary
        })
    }

    companion object {
        private const val AUTO_RECORDING_PERMISSIONS = 2
        private val recordingPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }
}
