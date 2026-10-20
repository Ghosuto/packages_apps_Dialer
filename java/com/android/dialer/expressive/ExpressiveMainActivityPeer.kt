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

package com.android.dialer.expressive

import android.Manifest
import android.content.*
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.android.dialer.R
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.callintent.CallInitiationType
import com.android.dialer.callintent.CallIntentBuilder
import com.android.dialer.location.GeoUtil
import com.android.dialer.main.MainActivityPeer
import com.android.dialer.main.impl.MainActivity
import com.android.dialer.precall.PreCall
import com.android.dialer.dialpadview.SpecialCharSequenceMgr
import kotlinx.coroutines.*

class ExpressiveMainActivityPeer(private val activity: MainActivity) : MainActivityPeer {
    private val repository = DialerRepository(activity)
    private val speedDialStore = SpeedDialStore(activity)
    private var data by mutableStateOf(DialerData())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf(false)
    private var tab by mutableIntStateOf(0)
    private var query by mutableStateOf("")
    private var digits by mutableStateOf("")
    private var keypad by mutableStateOf(false)
    private var compactFloatingBar by mutableStateOf(false)
    private var hideKeyboardOnScroll by mutableStateOf(true)
    private var groupContactsByName by mutableStateOf(true)
    private var bottomDialpadBackspace by mutableStateOf(true)
    private var speedDial by mutableStateOf<Map<Int, SpeedDialEntry>>(emptyMap())
    private var job: Job? = null
    private var observing = false
    private var photoNumber: String? = null
    private val photos = CallerPhotoStore(activity)
    private var pendingWrite: (suspend () -> Unit)? = null
    private var pendingWriteResult: ((Boolean) -> Unit)? = null
    private val writePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingWrite
        val completed = pendingWriteResult ?: {}
        pendingWrite = null
        pendingWriteResult = null
        if (granted && action != null) mutate(action, completed)
        else { if (!granted) fail(permissionDenied = true); completed(false) }
    }
    private val photoPicker = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val number = photoNumber
        photoNumber = null
        if (uri != null && number != null) activity.lifecycleScope.launch {
            try { photos.save(number, uri) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { Toast.makeText(activity, R.string.expressive_photo_failed, Toast.LENGTH_LONG).show() }
        }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    override fun onActivityCreate(saveInstanceState: Bundle?) {
        activity.supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (saveInstanceState != null) {
            photoNumber = saveInstanceState.getString("expressive.photo_number")
            tab = saveInstanceState.getInt("expressive.tab").coerceIn(0, 4)
            query = saveInstanceState.getString("expressive.query", "")
            digits = saveInstanceState.getString("expressive.digits", "")
            keypad = saveInstanceState.getBoolean("expressive.keypad")
        } else onNewIntent(activity.intent)
        speedDial = speedDialStore.load()
        activity.setContentView(ComposeView(activity).apply {
            id = R.id.expressive_main
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DialerExpressiveTheme {
                    DialerHome(data, loading, error, tab, query, digits, keypad,
                        compactFloatingBar = compactFloatingBar,
                        hideKeyboardOnScroll = hideKeyboardOnScroll,
                        groupContactsByName = groupContactsByName,
                        bottomDialpadBackspace = bottomDialpadBackspace,
                        onTab = { tab = it; query = "" }, onQuery = { query = it },
                        onDigits = { updateDigits(it) }, onKeypad = { keypad = it },
                        onCall = { call(it) }, onVideo = { call(it, true) }, onVoicemail = { voicemail() },
                        onPermissions = { permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS,
                            Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS)) },
                        onRetry = { refresh() }, onStar = { person -> write(Manifest.permission.WRITE_CONTACTS) { repository.setStarred(person) } },
                        speedDial = speedDial,
                        onAssignSpeedDial = { key, entry ->
                            speedDialStore.assign(key, entry)
                            speedDial = speedDialStore.load()
                        },
                        onRemoveSpeedDial = { key ->
                            speedDialStore.remove(key)
                            speedDial = speedDialStore.load()
                        },
                        onSpeedDial = { key ->
                            val entry = speedDial[key]
                            if (entry == null) {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.expressive_speed_dial_unassigned_key, key),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                call(entry.number)
                            }
                        },
                        onDelete = { calls, completed -> write(Manifest.permission.WRITE_CALL_LOG, completed) { repository.deleteCalls(calls) } },
                        onAddContact = { number -> launch(Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                            putExtra(ContactsContract.Intents.Insert.PHONE, number)
                        }) },
                        onMessage = { number -> launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))) },
                        onWhatsApp = { number -> openWhatsApp(number) },
                        onCopy = { number -> activity.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.expressive_phone), number)) },
                        onShare = { number -> launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, number)
                        }, activity.getString(R.string.expressive_share_number))) },
                        onEditContact = { person -> launch(Intent(Intent.ACTION_EDIT, person.uri)) },
                        onPhoto = { number -> photoNumber = number; photoPicker.launch("image/*") },
                        onRemovePhoto = { number -> mutate(action = { photos.remove(number) }) },
                        onAutoRecording = { number, enabled, completed ->
                            val action: suspend () -> Unit = {
                                withContext(Dispatchers.IO) {
                                    CallRecordingPreferences.setEnabled(activity, number, enabled)
                                }
                            }
                            if (enabled) write(Manifest.permission.RECORD_AUDIO, completed, action)
                            else mutate(action, completed)
                        },
                        onSettings = { launch(Intent(activity, ExpressiveSettingsActivity::class.java)) })
                }
            }
        })
    }
    private fun updateDigits(value: String): String {
        digits = sanitizeDialpadNumber(value)
        if ((digits.endsWith('#') || digits.endsWith('*')) &&
            SpecialCharSequenceMgr.handleChars(activity, digits, null)) digits = ""
        return digits
    }
    private fun call(number: String, video: Boolean = false) {
        if (number.isBlank()) return
        PreCall.start(activity, CallIntentBuilder(number, CallInitiationType.Type.DIALPAD).setIsVideoCall(video))
    }
    private fun voicemail() {
        PreCall.start(activity, CallIntentBuilder.forVoicemail(CallInitiationType.Type.VOICEMAIL_LOG))
    }
    private fun launch(intent: Intent) {
        try { activity.startActivity(intent) }
        catch (_: ActivityNotFoundException) { fail() }
        catch (_: SecurityException) { fail(permissionDenied = true) }
    }
    private fun openWhatsApp(number: String) {
        val e164 = if (PhoneNumberUtils.isUriNumber(number)) null else PhoneNumberUtils.formatNumberToE164(
            PhoneNumberUtils.normalizeNumber(PhoneNumberUtils.extractNetworkPortion(number)),
            GeoUtil.getCurrentCountryIso(activity))
        if (e164 == null) {
            Toast.makeText(activity, R.string.expressive_whatsapp_invalid_number, Toast.LENGTH_LONG).show()
            return
        }
        val uri = Uri.Builder().scheme("whatsapp").authority("send")
            .appendQueryParameter("phone", e164.removePrefix("+")).build()
        for (packageName in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName))
                return
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                fail(permissionDenied = true)
                return
            }
        }
        Toast.makeText(activity, R.string.expressive_whatsapp_unavailable, Toast.LENGTH_LONG).show()
    }
    private fun fail(permissionDenied: Boolean = false) = Toast.makeText(activity,
        if (permissionDenied) R.string.expressive_permission_denied else R.string.expressive_action_failed,
        Toast.LENGTH_LONG).show()
    private fun write(permission: String, completed: (Boolean) -> Unit = {}, action: suspend () -> Unit) {
        if (repository.has(permission)) mutate(action, completed)
        else { pendingWrite = action; pendingWriteResult = completed; writePermission.launch(permission) }
    }
    private fun mutate(action: suspend () -> Unit, completed: (Boolean) -> Unit = {}) {
        activity.lifecycleScope.launch {
            try { action(); refresh(); completed(true) }
            catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { fail(permissionDenied = true); refresh(); completed(false) }
            catch (_: Exception) { fail(); refresh(); completed(false) }
        }
    }
    private fun refresh() {
        job?.cancel()
        job = activity.lifecycleScope.launch {
            try { data = repository.load(); error = false } catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
            finally { loading = false }
        }
    }
    override fun onActivityResume() {
        speedDial = speedDialStore.load()
        compactFloatingBar = PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean("compact_floating_bar", false)
        hideKeyboardOnScroll = PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean("hide_keyboard_on_scroll", true)
        groupContactsByName = PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean("group_contacts_by_name", true)
        bottomDialpadBackspace = PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean("bottom_dialpad_backspace", true)
        refresh()
        if (!observing) {
            activity.contentResolver.registerContentObserver(Calls.CONTENT_URI, true, observer)
            activity.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
        }
    }
    override fun onActivityPause() {
        if (observing) activity.contentResolver.unregisterContentObserver(observer)
        observing = false
    }
    override fun onActivityStop() { job?.cancel() }
    override fun onUserLeaveHint() {}
    override fun onNewIntent(intent: Intent) {
        if (intent.type == Calls.CONTENT_TYPE) tab = if (intent.getIntExtra(Calls.EXTRA_CALL_TYPE_FILTER, 0) == Calls.VOICEMAIL_TYPE) 4 else 0
        if (intent.hasExtra("EXTRA_SHOW_TAB")) tab = when (intent.getIntExtra("EXTRA_SHOW_TAB", 0)) {
            0 -> 1; 1 -> 0; 2 -> 3; 3 -> 4; else -> 0
        }
        if (intent.data?.scheme == "voicemail") { tab = 4; return }
        if (intent.action == Intent.ACTION_DIAL || intent.data?.scheme == "tel" || intent.getBooleanExtra("add_call_mode", false)) {
            keypad = true
            activity.lifecycleScope.launch {
                val incoming = withContext(Dispatchers.IO) {
                    try { PhoneNumberUtils.getNumberFromIntent(intent, activity).orEmpty() }
                    catch (_: Exception) { "" }
                }
                digits = sanitizeDialpadNumber(incoming)
            }
        }
        if (intent.getBooleanExtra(com.android.dialer.app.MainComponent.EXTRA_CLEAR_NEW_VOICEMAILS, false)) {
            com.android.dialer.app.calllog.CallLogNotificationsService.markAllNewVoicemailsAsOld(activity)
        }
    }
    override fun onSaveInstanceState(bundle: Bundle) {
        bundle.putString("expressive.photo_number", photoNumber)
        bundle.putInt("expressive.tab", tab); bundle.putString("expressive.query", query)
        bundle.putString("expressive.digits", digits); bundle.putBoolean("expressive.keypad", keypad)
    }
    override fun onBackPressed(): Boolean = when {
        keypad -> { keypad = false; true }
        query.isNotEmpty() -> { query = ""; true }
        tab != 0 -> { tab = 0; true }
        else -> false
    }
}
