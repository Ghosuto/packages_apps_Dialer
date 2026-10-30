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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.android.dialer.expressive

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.android.dialer.R
import com.android.incallui.answer.protocol.*
import com.android.incallui.call.CallList
import com.android.incallui.call.state.DialerCallState
import com.android.incallui.incall.protocol.PrimaryCallState

class ExpressiveAnswerFragment : ExpressiveCallFragment(), AnswerScreen {
    override val answering = true
    private lateinit var answerDelegate: AnswerScreenDelegate
    private var responses by mutableStateOf<List<String>>(emptyList())
    private var showResponses by mutableStateOf(false)
    private var takingAction by mutableStateOf(false)
    private var answerScreenReady by mutableStateOf(false)
    private var incomingCallStyle by mutableStateOf(INCOMING_CALL_STYLE_DEFAULT)
    private lateinit var preferences: SharedPreferences
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == INCOMING_CALL_STYLE || key == SEPARATE_CALL_BUTTONS) {
            incomingCallStyle = readIncomingCallStyle(prefs)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        incomingCallStyle = readIncomingCallStyle(preferences)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        answerDelegate = (requireActivity() as AnswerScreenDelegateFactory).newAnswerScreenDelegate(this)
        super.onViewCreated(view, savedInstanceState)
        answerScreenReady = true
        if (allowAnswerAndRelease()) answerDelegate.onAnswerAndReleaseButtonEnabled()
    }
    override fun onDestroyView() {
        answerScreenReady = false
        showResponses = false
        responses = emptyList()
        if (allowAnswerAndRelease()) answerDelegate.onAnswerAndReleaseButtonDisabled()
        answerDelegate.onAnswerScreenUnready()
        super.onDestroyView()
    }
    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDestroy()
    }
    private fun hasIncomingCall(): Boolean {
        if (!isIncomingState(primaryCallState.state())) return false
        val call = CallList.getInstance().getCallById(getCallId()) ?: return false
        return isIncomingState(call.state)
    }
    private fun canTakeAction() = answerScreenReady && !takingAction && !showResponses && hasIncomingCall()
    private fun answerCall() {
        if (!canTakeAction()) return
        takingAction = true
        answerDelegate.onAnswer(false)
    }
    private fun rejectCall() {
        if (!canTakeAction()) return
        takingAction = true
        answerDelegate.onReject()
    }
    private fun answerAndReleaseCall() {
        if (!canTakeAction() || !allowAnswerAndRelease()) return
        takingAction = true
        answerDelegate.onAnswerAndReleaseCall()
    }
    private fun openResponses() {
        if (!canTakeAction() || responses.isEmpty()) return
        showResponses = true
    }
    private fun rejectWithResponse(response: String) {
        if (!answerScreenReady || takingAction || !showResponses || response !in responses || !hasIncomingCall()) return
        showResponses = false
        takingAction = true
        answerDelegate.onRejectCallWithMessage(response)
    }
    @Composable
    override fun CallGestureContainer(content: @Composable () -> Unit) {
        if (incomingCallStyle == INCOMING_CALL_STYLE_IOS) {
            content()
        } else if (incomingCallStyle == INCOMING_CALL_STYLE_GOOGLE) {
            IncomingCallGoogleSwipe(enabled = canTakeAction(),
                onAnswer = ::answerCall, onReject = ::rejectCall, content = content)
        } else {
            IncomingCallSwipe(enabled = canTakeAction(),
                onAnswer = ::answerCall, onReject = ::rejectCall, content = content)
        }
    }
    @Composable
    override fun AnswerActions() {
        LaunchedEffect(takingAction) {
            if (takingAction) { kotlinx.coroutines.delay(5000); takingAction = false }
        }
        val actionsEnabled = canTakeAction()
        if (incomingCallStyle == INCOMING_CALL_STYLE_DEFAULT) IncomingCallHandle(enabled = actionsEnabled)
        if (incomingCallStyle == INCOMING_CALL_STYLE_GOOGLE) IncomingCallGoogleHandle(enabled = actionsEnabled)
        if (primaryInfoState.answeringDisconnectsOngoingCall()) Text(stringResource(R.string.expressive_answer_ends_call), Modifier.padding(12.dp))
        if (allowAnswerAndRelease()) TextButton(onClick = ::answerAndReleaseCall, enabled = actionsEnabled) {
            Text(stringResource(R.string.expressive_answer_release))
        }
        FilledTonalButton(onClick = ::openResponses, enabled = actionsEnabled && responses.isNotEmpty(),
            modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp), shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
            Text(stringResource(R.string.expressive_reply))
        }
        if (incomingCallStyle == INCOMING_CALL_STYLE_IOS) IncomingCallButtons(
            enabled = actionsEnabled, onAnswer = ::answerCall, onReject = ::rejectCall,
            modifier = Modifier.padding(top = 28.dp, bottom = 24.dp))
        if (primaryInfoState.isSpam() || primaryInfoState.carrierSpamStatus() == com.android.dialer.spam.CarrierCallIdentity.SPAM_CONFIRMED ||
                primaryInfoState.carrierSpamStatus() == com.android.dialer.spam.CarrierCallIdentity.SPAM_SUSPECTED) {
            CarrierSpamActionButtons(primaryInfoState.number().orEmpty())
        }
        if (takingAction) Text(stringResource(R.string.expressive_connecting), Modifier.padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite })
        if (showResponses) DialerModalBottomSheet(onDismissRequest = { showResponses = false }) {
            responses.forEach { response ->
                TextButton(onClick = { rejectWithResponse(response) }, enabled = answerScreenReady && !takingAction && hasIncomingCall(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { Text(response) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    override fun getCallId(): String = requireArguments().getString("call_id")!!
    override fun isRttCall() = false
    override fun isVideoCall() = false
    override fun isVideoUpgradeRequest() = false
    override fun allowAnswerAndRelease() = requireArguments().getBoolean("release")
    override fun isActionTimeout() = ::answerDelegate.isInitialized && answerDelegate.isActionTimeout
    override fun setTextResponses(textResponses: List<String>) {
        responses = textResponses.toList()
        if (responses.isEmpty()) showResponses = false
    }
    override fun setCallState(primaryCallState: PrimaryCallState) {
        super.setCallState(primaryCallState)
        if (!hasIncomingCall()) showResponses = false
    }
    override fun hasPendingDialogs() = showResponses
    override fun dismissPendingDialogs() { showResponses = false }
    override fun getAnswerScreenFragment(): Fragment = this
    companion object {
        private const val INCOMING_CALL_STYLE = "incoming_call_style"
        private const val SEPARATE_CALL_BUTTONS = "separate_call_buttons"
        private const val INCOMING_CALL_STYLE_DEFAULT = "default"
        private const val INCOMING_CALL_STYLE_IOS = "ios"
        private const val INCOMING_CALL_STYLE_GOOGLE = "google"
        private fun isIncomingState(state: Int) = state == DialerCallState.INCOMING || state == DialerCallState.CALL_WAITING
        private fun readIncomingCallStyle(prefs: SharedPreferences): String {
            return prefs.getString(INCOMING_CALL_STYLE, null)
                ?: if (prefs.getBoolean(SEPARATE_CALL_BUTTONS, false)) INCOMING_CALL_STYLE_IOS
                else INCOMING_CALL_STYLE_DEFAULT
        }
        @JvmStatic fun create(callId: String, release: Boolean) = ExpressiveAnswerFragment().apply {
            arguments = Bundle().apply { putString("call_id", callId); putBoolean("release", release) }
        }
    }
}
