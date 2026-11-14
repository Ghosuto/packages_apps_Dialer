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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.android.dialer.expressive

import android.Manifest
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.CallAudioState
import android.text.format.DateUtils
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.graphics.drawable.toBitmap
import androidx.preference.PreferenceManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.android.dialer.R
import com.android.incallui.incall.protocol.*
import com.android.incallui.incall.protocol.InCallButtonIds.*
import com.android.incallui.call.state.DialerCallState
import com.android.incallui.audioroute.AudioRouteSelectorDialogFragment.AudioRouteSelectorPresenter
import kotlinx.coroutines.delay
import com.android.dialer.spam.CarrierCallIdentity

private const val ROUNDED_MESSAGE = -1002
private const val CALL_CONTROLS_PER_PAGE = 6
private const val CALL_CONTROL_COLUMNS = 3
private const val CALL_CONTROL_ROWS = 2
private val CALL_CONTROL_ROW_HEIGHT = 104.dp
private val CALL_CONTROL_PAGER_HEIGHT = CALL_CONTROL_ROW_HEIGHT * CALL_CONTROL_ROWS.toFloat() + 8.dp
private val ROUNDED_CONTROL_IDS = listOf(
    BUTTON_UPGRADE_TO_VIDEO, BUTTON_ADD_CALL, ROUNDED_MESSAGE,
    BUTTON_MUTE, BUTTON_HOLD, BUTTON_RECORD_CALL,
    BUTTON_SWAP, BUTTON_MERGE,
    BUTTON_UPGRADE_TO_RTT, BUTTON_SWAP_SIM,
    BUTTON_MANAGE_VOICE_CONFERENCE)

open class ExpressiveCallFragment : Fragment(), InCallScreen, InCallButtonUi, AudioRouteSelectorPresenter {
    protected var primaryInfoState by mutableStateOf(PrimaryInfo.empty())
    protected var primaryCallState by mutableStateOf(PrimaryCallState.empty())
    protected var secondaryInfoState by mutableStateOf(SecondaryInfo.builder().build())
    protected lateinit var screenDelegate: InCallScreenDelegate
    private lateinit var buttonDelegate: InCallButtonUiDelegate
    private val shown = mutableStateMapOf<Int, Boolean>()
    private val enabledButtons = mutableStateMapOf<Int, Boolean>()
    private var buttonUiReady by mutableStateOf(false)
    private var controlsEnabled by mutableStateOf(false)
    private var endEnabled by mutableStateOf(false)
    private var held by mutableStateOf(false)
    private var audio by mutableStateOf<CallAudioState?>(null)
    private var recording by mutableStateOf(false)
    private var recordingDuration by mutableLongStateOf(0)
    private var conference by mutableStateOf(false)
    private var dialpad by mutableStateOf(false)
    private var locationVisible by mutableStateOf(false)
    private var showAudioRoutes by mutableStateOf(false)
    protected open val answering = false
    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it } && !recording && isControlEnabled(BUTTON_RECORD_CALL))
            buttonDelegate.callRecordClicked(true)
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (isAdded) openAudioRoutes()
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return FrameLayout(requireContext()).apply {
            addView(ComposeView(context).apply {
                id = R.id.expressive_call
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { DialerExpressiveTheme { CallGestureContainer { CallContent() } } }
            }, FrameLayout.LayoutParams(-1, -1))
            addView(FrameLayout(context).apply { id = R.id.incall_dialpad_container },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    bottomMargin = (104 * resources.displayMetrics.density).toInt()
                })
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val roundedControls = PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(ROUNDED_CALL_CONTROLS_KEY, false)
                findViewById<FrameLayout>(R.id.incall_dialpad_container).updateLayoutParams<FrameLayout.LayoutParams> {
                    bottomMargin = ((if (roundedControls) 176 else 80) * resources.displayMetrics.density).toInt() +
                        insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                }
                insets
            }
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        screenDelegate = (requireActivity() as InCallScreenDelegateFactory).newInCallScreenDelegate()
        screenDelegate.onInCallScreenDelegateInit(this)
        screenDelegate.onInCallScreenReady()
        if (!answering) {
            buttonDelegate = (requireActivity() as InCallButtonUiDelegateFactory).newInCallButtonUiDelegate()
            buttonDelegate.onInCallButtonUiReady(this)
            buttonUiReady = true
        }
    }
    override fun onResume() { super.onResume(); screenDelegate.onInCallScreenResumed() }
    override fun onPause() { screenDelegate.onInCallScreenPaused(); super.onPause() }
    override fun onDestroyView() {
        buttonUiReady = false
        controlsEnabled = false
        endEnabled = false
        showAudioRoutes = false
        shown.clear()
        enabledButtons.clear()
        conference = false
        if (!answering) buttonDelegate.onInCallButtonUiUnready()
        screenDelegate.onInCallScreenUnready()
        super.onDestroyView()
    }
    @Composable
    private fun rememberCallerSpamWarning(): Boolean {
        val context = LocalContext.current
        val preferences = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
        var enabled by remember(preferences) { mutableStateOf(preferences.getBoolean("caller_screen_spam_warning", true)) }
        DisposableEffect(preferences) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == "caller_screen_spam_warning")
                    enabled = preferences.getBoolean("caller_screen_spam_warning", true)
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return enabled
    }
    @Composable
    private fun CallContent() {
        val name = primaryInfoState.name()?.takeIf { it.isNotBlank() } ?: primaryInfoState.number()?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.expressive_unknown)
        val number = if (primaryInfoState.numberPresentation() == android.provider.CallLog.Calls.PRESENTATION_ALLOWED)
            primaryInfoState.number().orEmpty() else ""
        val callerStyle = rememberCallerScreenStyle()
        val hyperOs = callerStyle == CALLER_STYLE_HYPEROS
        val roundedControls = rememberRoundedCallControls()
        LaunchedEffect(roundedControls) { view?.let { ViewCompat.requestApplyInsets(it) } }
        val fullScreenCaller = callerStyle == CALLER_STYLE_FULLSCREEN || hyperOs
        val callerSpamWarning = rememberCallerSpamWarning()
        val callerPhoto = rememberCallerPhoto(number, maxSize = if (fullScreenCaller) 1600 else 512)
        val contactPhoto = remember(primaryInfoState.photo(), primaryInfoState.photoType(), number) {
            if (number.isNotBlank() && primaryInfoState.photoType() == ContactPhotoType.CONTACT)
                primaryInfoState.photo()?.toBitmap()?.asImageBitmap() else null
        }
        val photo = callerPhoto ?: contactPhoto
        val fallbackPhotoResId = primaryInfoState.fallbackPhotoResId()
        val hyperOsForeground = if (hyperOs) rememberHyperOsForeground(photo, fallbackPhotoResId != 0)
            else MaterialTheme.colorScheme.onSurface
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (hyperOs) hyperOsForeground
                else MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize()) {
                if (hyperOs) HyperOsCallerBackground(photo, Modifier.fillMaxSize(), fallbackPhotoResId)
                else if (fullScreenCaller) CallerPortrait(photo, Modifier.fillMaxSize(), fallbackPhotoResId,
                    if (callerSpamWarning && primaryInfoState.isSpam()) CarrierCallIdentity.SPAM_CONFIRMED
                    else if (callerSpamWarning)
                        primaryInfoState.carrierSpamStatus()
                    else CarrierCallIdentity.SPAM_UNKNOWN)
                if (callerSpamWarning && !fullScreenCaller) {
                    CarrierSpamWarningOverlay(
                        if (primaryInfoState.isSpam()) CarrierCallIdentity.SPAM_CONFIRMED
                        else primaryInfoState.carrierSpamStatus(), Modifier.fillMaxSize())
                }
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = if (answering) 0.dp else if (roundedControls) 168.dp else 88.dp)) {
                    val availableHeight = maxHeight
                    val compactIdentity = dialpad || availableHeight < 520.dp
                    val hyperOsTopInset = if (hyperOs && !compactIdentity)
                        (availableHeight * .16f).coerceIn(0.dp, 112.dp) else 0.dp
                    val avatarSize = minOf(if (compactIdentity) 64.dp else 150.dp, maxWidth / 3) * 1.2f
                    CallerContentLayout(
                        viewportHeight = (availableHeight - 32.dp - hyperOsTopInset).coerceAtLeast(0.dp),
                        centerOffset = (if (answering) 0.dp else 44.dp) - 48.dp,
                        identityAtTop = fullScreenCaller || compactIdentity,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .padding(top = hyperOsTopInset),
                        header = {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (primaryInfoState.isWorkCall()) {
                                        Symbol(DialerSymbol.Work, modifier = Modifier.size(18.dp))
                                        Text(stringResource(R.string.expressive_work), Modifier.padding(start = 6.dp),
                                            style = MaterialTheme.typography.labelLarge)
                                    } else primaryCallState.connectionLabel()?.takeIf { it.isNotBlank() }?.let {
                                        Surface(shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .9f),
                                            contentColor = MaterialTheme.colorScheme.onSurface) {
                                            Text(it, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                if (!fullScreenCaller) {
                                    Spacer(Modifier.height(20.dp))
                                    CallerNameAndNumber(name, number, compactIdentity)
                                    Spacer(Modifier.height(16.dp))
                                    CallStatus()
                                }
                            }
                        }, identity = {
                            if (!fullScreenCaller) {
                                RoundedCallerAvatar(photo, Modifier.size(avatarSize), fallbackPhotoResId)
                                Spacer(Modifier.height(if (compactIdentity) 12.dp else 20.dp))
                            } else {
                                if (hyperOs) {
                                    HyperOsCallerAvatar(photo, Modifier.size(if (compactIdentity) 64.dp else 76.dp), fallbackPhotoResId)
                                    Spacer(Modifier.height(14.dp))
                                }
                                CallerNameAndNumber(name, number, compactIdentity, hyperOs)
                                if (hyperOs) primaryInfoState.location()?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge,
                                        color = LocalContentColor.current.copy(alpha = .76f))
                                }
                                Spacer(Modifier.height(if (hyperOs) 10.dp else 16.dp))
                                CallStatus(hyperOs)
                            }
                            val spamStatus = primaryInfoState.carrierSpamStatus()
                            if (primaryInfoState.isSpam() || spamStatus == CarrierCallIdentity.SPAM_CONFIRMED)
                                Text(stringResource(R.string.expressive_confirmed_spam), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                            else if (spamStatus == CarrierCallIdentity.SPAM_SUSPECTED)
                                Text(stringResource(R.string.expressive_suspected_spam), Modifier.padding(top = 8.dp), color = Color(0xFFB26A00))
                            else if (primaryInfoState.callerVerificationStatus() == CarrierCallIdentity.VERIFICATION_VERIFIED)
                                Text(stringResource(R.string.expressive_verified_caller), Modifier.padding(top = 8.dp), color = Color(0xFF16834B))
                            else if (primaryInfoState.callerVerificationStatus() == CarrierCallIdentity.VERIFICATION_UNVERIFIED)
                                Text(stringResource(R.string.expressive_unverified_caller), Modifier.padding(top = 8.dp),
                                    color = if (hyperOs) LocalContentColor.current.copy(alpha = .76f) else MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (spamStatus == CarrierCallIdentity.SPAM_NOT_SPAM)
                                Text(stringResource(R.string.expressive_not_spam), Modifier.padding(top = 8.dp),
                                    color = if (hyperOs) LocalContentColor.current.copy(alpha = .76f) else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!hyperOs) primaryInfoState.location()?.takeIf { it.isNotBlank() }?.let {
                                Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                                    color = if (hyperOs) LocalContentColor.current.copy(alpha = .76f) else LocalContentColor.current)
                            }
                            primaryCallState.callSubject()?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(12.dp)) }
                            AndroidView(factory = { ctx -> FrameLayout(ctx).apply { id = R.id.incall_location_holder } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = if (locationVisible) 120.dp else 0.dp))
                            if (secondaryInfoState.shouldShow()) {
                                FilledTonalButton(onClick = { clickControl(BUTTON_SWITCH_TO_SECONDARY) },
                                    colors = if (roundedControls) ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.White.copy(alpha = .94f),
                                        contentColor = Color.Black,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .35f),
                                        disabledContentColor = LocalContentColor.current.copy(alpha = .38f))
                                    else ButtonDefaults.filledTonalButtonColors(),
                                    enabled = isControlEnabled(BUTTON_SWITCH_TO_SECONDARY)) {
                                    Symbol(DialerSymbol.Swap)
                                    Text(secondaryInfoState.name() ?: stringResource(R.string.expressive_on_hold), Modifier.padding(start = 8.dp))
                                }
                            }
                        }, actions = {
                            if (answering) AnswerActions() else {
                                AnimatedVisibility(!dialpad && primaryInfoState.showInCallButtonGrid()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val buttons = if (roundedControls) ROUNDED_CONTROL_IDS
                                        else listOf(BUTTON_AUDIO, BUTTON_MUTE, BUTTON_DIALPAD, BUTTON_HOLD,
                                            BUTTON_ADD_CALL, BUTTON_RECORD_CALL, BUTTON_MERGE, BUTTON_UPGRADE_TO_VIDEO,
                                            BUTTON_UPGRADE_TO_RTT, BUTTON_SWAP_SIM, BUTTON_SWAP,
                                            BUTTON_MANAGE_VOICE_CONFERENCE)
                                        val totalPages = { (buttons.size + CALL_CONTROLS_PER_PAGE - 1) /
                                            CALL_CONTROLS_PER_PAGE }
                                        val pagerState = rememberPagerState(initialPage = 0, pageCount = totalPages)
                                        LaunchedEffect(totalPages()) {
                                            val lastPage = (totalPages() - 1).coerceAtLeast(0)
                                            if (pagerState.currentPage > lastPage) pagerState.scrollToPage(lastPage)
                                        }
                                        if (totalPages() > 1) {
                                            Row(
                                                Modifier.fillMaxWidth().alpha(.6f).semantics {
                                                    contentDescription = getString(
                                                        R.string.expressive_call_controls_page,
                                                        pagerState.currentPage + 1,
                                                        totalPages())
                                                },
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                repeat(totalPages()) { index ->
                                                    Box(
                                                        Modifier.padding(horizontal = 4.dp).size(8.dp)
                                                            .background(
                                                                if (index == pagerState.currentPage)
                                                                    MaterialTheme.colorScheme.primary
                                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                CircleShape))
                                                }
                                            }
                                        }
                                        HorizontalPager(
                                            state = pagerState,
                                            modifier = Modifier.fillMaxWidth().height(CALL_CONTROL_PAGER_HEIGHT),
                                        ) { page ->
                                            Column(
                                                Modifier.fillMaxWidth().height(CALL_CONTROL_PAGER_HEIGHT),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                repeat(CALL_CONTROL_ROWS) { row ->
                                                    Row(
                                                        Modifier.fillMaxWidth().height(CALL_CONTROL_ROW_HEIGHT),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        repeat(CALL_CONTROL_COLUMNS) { column ->
                                                            val index = page * CALL_CONTROLS_PER_PAGE +
                                                                row * CALL_CONTROL_COLUMNS + column
                                                            val id = buttons.getOrNull(index)
                                                            if (id == null) {
                                                                Spacer(Modifier.weight(1f).fillMaxHeight())
                                                            } else {
                                                                key(id) {
                                                                    CallControl(id, Modifier.weight(1f).fillMaxHeight(), roundedControls)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (recording) Text(stringResource(R.string.expressive_recording_duration, DateUtils.formatElapsedTime(recordingDuration / 1000)),
                                            color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        })
                }
                if (!answering) Box(Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                    .padding(horizontal = 24.dp).padding(top = 16.dp, bottom = if (roundedControls) 64.dp else 16.dp)) {
                    if (roundedControls) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top) {
                        if (primaryInfoState.showInCallButtonGrid())
                            CallControl(BUTTON_AUDIO, Modifier.weight(1f), roundedControls = true)
                        else Spacer(Modifier.weight(1f))
                        HyperOsCallControl(DialerSymbol.EndCall, stringResource(R.string.expressive_end_call),
                            onClick = { endCall() }, enabled = endEnabled,
                            endCall = true, modifier = Modifier.weight(1f))
                        if (primaryInfoState.showInCallButtonGrid())
                            CallControl(BUTTON_DIALPAD, Modifier.weight(1f), roundedControls = true)
                        else Spacer(Modifier.weight(1f))
                    } else ExpressiveTile(onClick = { endCall() }, enabled = endEnabled,
                        modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().heightIn(min = 64.dp),
                        color = EndCallRed, contentColor = Color.White) {
                        Symbol(DialerSymbol.EndCall)
                        Text(stringResource(R.string.expressive_end_call), Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        val audioEnabled = isControlEnabled(BUTTON_AUDIO)
        LaunchedEffect(audioEnabled) { if (!audioEnabled) showAudioRoutes = false }
        if (showAudioRoutes && audioEnabled) AudioRouteSheet(audio ?: buttonDelegate.currentAudioState,
            onDismiss = { showAudioRoutes = false }, onSelect = { route ->
                showAudioRoutes = false; onAudioRouteSelected(route)
            })
    }
    @Composable
    private fun CallerNameAndNumber(name: String, number: String, compact: Boolean, hyperOs: Boolean = false) {
        val context = LocalContext.current
        val preferences = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
        var hideSavedNumber by remember(preferences) {
            mutableStateOf(preferences.getBoolean("hide_saved_contact_number", false))
        }
        DisposableEffect(preferences) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == "hide_saved_contact_number") {
                    hideSavedNumber = preferences.getBoolean("hide_saved_contact_number", false)
                }
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            hideSavedNumber = preferences.getBoolean("hide_saved_contact_number", false)
            onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        Text(name, Modifier.fillMaxWidth(),
            style = when {
                hyperOs && compact -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light)
                hyperOs -> MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light)
                compact -> MaterialTheme.typography.headlineSmallEmphasized
                else -> MaterialTheme.typography.displaySmallEmphasized
            }, textAlign = TextAlign.Center)
        if (number.isNotBlank() && name != number && !(hideSavedNumber && primaryInfoState.isLocalContact()))
            Text(number, Modifier.fillMaxWidth().padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = if (hyperOs) LocalContentColor.current.copy(alpha = .76f) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable
    protected open fun AnswerActions() {}
    @Composable
    protected open fun CallGestureContainer(content: @Composable () -> Unit) { content() }
    @Composable
    private fun CallStatus(hyperOs: Boolean = false) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(primaryCallState.state(), primaryCallState.connectTimeMillis()) {
            while (primaryCallState.state() == DialerCallState.ACTIVE) { now = System.currentTimeMillis(); delay(1000) }
        }
        val status = when (primaryCallState.state()) {
            DialerCallState.ACTIVE -> DateUtils.formatElapsedTime(((now - primaryCallState.connectTimeMillis()) / 1000).coerceAtLeast(0))
            DialerCallState.INCOMING, DialerCallState.CALL_WAITING -> stringResource(R.string.expressive_incoming_call)
            DialerCallState.ONHOLD -> stringResource(R.string.expressive_on_hold)
            DialerCallState.DISCONNECTED, DialerCallState.DISCONNECTING ->
                primaryCallState.disconnectCause().label?.toString() ?: stringResource(R.string.expressive_call_ended)
            else -> primaryCallState.customLabel() ?: stringResource(R.string.expressive_calling)
        }
        if (status.isBlank()) return
        if (hyperOs) {
            Text(status, style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = .76f))
            return
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.labelLargeEmphasized)
            }
        }
    }
    @Composable
    private fun CallControl(id: Int, modifier: Modifier, roundedControls: Boolean = false) {
        val symbol = when (id) {
            ROUNDED_MESSAGE -> DialerSymbol.Message
            BUTTON_AUDIO -> when (audio?.route) {
                CallAudioState.ROUTE_BLUETOOTH -> DialerSymbol.Bluetooth
                CallAudioState.ROUTE_WIRED_HEADSET -> DialerSymbol.Headset
                CallAudioState.ROUTE_SPEAKER -> DialerSymbol.Speaker
                else -> DialerSymbol.Phone
            }; BUTTON_MUTE -> DialerSymbol.Mic
            BUTTON_DIALPAD -> DialerSymbol.Keypad; BUTTON_HOLD -> DialerSymbol.Pause
            BUTTON_ADD_CALL -> DialerSymbol.Add; BUTTON_UPGRADE_TO_VIDEO -> DialerSymbol.Video
            BUTTON_RECORD_CALL -> DialerSymbol.Record; BUTTON_UPGRADE_TO_RTT -> DialerSymbol.Message
            BUTTON_MANAGE_VOICE_CONFERENCE, BUTTON_MERGE -> DialerSymbol.People
            else -> DialerSymbol.Swap
        }
        val label = stringResource(when (id) {
            ROUNDED_MESSAGE -> R.string.expressive_message
            BUTTON_AUDIO -> when (audio?.route) {
                CallAudioState.ROUTE_BLUETOOTH -> R.string.expressive_bluetooth
                CallAudioState.ROUTE_WIRED_HEADSET -> R.string.expressive_headset
                CallAudioState.ROUTE_SPEAKER -> R.string.expressive_speaker
                else -> R.string.expressive_audio
            }; BUTTON_MUTE -> R.string.expressive_mute
            BUTTON_DIALPAD -> R.string.expressive_keypad; BUTTON_HOLD -> R.string.expressive_hold
            BUTTON_ADD_CALL -> R.string.expressive_add_call; BUTTON_UPGRADE_TO_VIDEO -> R.string.expressive_video
            BUTTON_RECORD_CALL -> if (recording) R.string.expressive_stop_recording else R.string.expressive_record
            BUTTON_UPGRADE_TO_RTT -> R.string.expressive_rtt; BUTTON_MERGE -> R.string.expressive_merge
            BUTTON_MANAGE_VOICE_CONFERENCE -> R.string.expressive_manage_conference
            BUTTON_SWAP_SIM -> R.string.expressive_swap_sim; else -> R.string.expressive_swap
        })
        val selected = when (id) { BUTTON_AUDIO -> audio?.route == CallAudioState.ROUTE_SPEAKER
            BUTTON_MUTE -> audio?.isMuted == true; BUTTON_HOLD -> held; BUTTON_RECORD_CALL -> recording;
            BUTTON_DIALPAD -> roundedControls && dialpad; else -> false }
        val enabled = isControlEnabled(id)
        if (roundedControls) {
            HyperOsCallControl(symbol, label, onClick = { clickControl(id) }, modifier = modifier,
                selected = selected, enabled = enabled)
            return
        }
        val monochromeActive = selected && LocalMonochromeTheme.current
        val dark = isSystemInDarkTheme()
        val colors = MaterialTheme.colorScheme
        ExpressiveTile(onClick = { clickControl(id) }, modifier = modifier.semantics { this.selected = selected },
            selected = selected, enabled = enabled,
            color = if (monochromeActive) {
                if (dark) Color.White else Color.Black
            } else if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (monochromeActive) {
                if (dark) Color.Black else Color.White
            } else if (selected) colors.onPrimaryContainer else colors.onSurface) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(symbol); Text(label, style = if (selected) MaterialTheme.typography.labelMediumEmphasized
                    else MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
    }
    private fun controlAction(id: Int): Int =
        if (id == BUTTON_SWAP && shown[BUTTON_SWAP] != true) BUTTON_SWITCH_TO_SECONDARY else id

    private fun isControlEnabled(id: Int): Boolean {
        if (!buttonUiReady || !controlsEnabled || !primaryInfoState.showInCallButtonGrid()) return false
        if (dialpad && id != BUTTON_AUDIO && id != BUTTON_DIALPAD && id != BUTTON_SWITCH_TO_SECONDARY) return false
        if (id == ROUNDED_MESSAGE) {
            return primaryInfoState.numberPresentation() == android.provider.CallLog.Calls.PRESENTATION_ALLOWED &&
                primaryInfoState.number().orEmpty().isNotBlank()
        }
        val action = controlAction(id)
        return shown[action] == true && enabledButtons[action] != false
    }

    private fun endCall() {
        if (endEnabled && isAdded && ::screenDelegate.isInitialized) screenDelegate.onEndCallClicked()
    }

    private fun clickControl(id: Int) {
        if (!isControlEnabled(id)) return
        when (controlAction(id)) {
            ROUNDED_MESSAGE -> {
                val number = primaryInfoState.number().orEmpty()
                if (number.isNotBlank()) runCatching {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
                }
            }
            BUTTON_AUDIO -> {
                val audioState = buttonDelegate.currentAudioState
                if (audioState.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH == 0) {
                    buttonDelegate.toggleSpeakerphone()
                } else {
                    showAudioRouteSelector()
                }
            }
            BUTTON_MUTE -> buttonDelegate.muteClicked(audio?.isMuted != true, true)
            BUTTON_DIALPAD -> buttonDelegate.showDialpadClicked(true)
            BUTTON_HOLD -> buttonDelegate.holdClicked(!held)
            BUTTON_ADD_CALL -> buttonDelegate.addCallClicked()
            BUTTON_SWAP -> buttonDelegate.swapClicked()
            BUTTON_MERGE -> buttonDelegate.mergeClicked()
            BUTTON_UPGRADE_TO_VIDEO -> buttonDelegate.changeToVideoClicked()
            BUTTON_UPGRADE_TO_RTT -> buttonDelegate.changeToRttClicked()
            BUTTON_SWAP_SIM -> buttonDelegate.swapSimClicked()
            BUTTON_RECORD_CALL -> buttonDelegate.callRecordClicked(!recording)
            BUTTON_MANAGE_VOICE_CONFERENCE -> screenDelegate.onManageConferenceClicked()
            BUTTON_SWITCH_TO_SECONDARY -> screenDelegate.onSecondaryInfoClicked()
        }
    }
    override fun setPrimary(primaryInfo: PrimaryInfo) { primaryInfoState = primaryInfo }
    override fun setSecondary(secondaryInfo: SecondaryInfo) { secondaryInfoState = secondaryInfo }
    override fun setCallState(primaryCallState: PrimaryCallState) {
        this.primaryCallState = primaryCallState
        val secondaryState = primaryCallState.swapToSecondaryButtonState()
        showButton(BUTTON_SWITCH_TO_SECONDARY, secondaryState != PrimaryCallState.ButtonState.NOT_SUPPORT)
        enableButton(BUTTON_SWITCH_TO_SECONDARY, secondaryState == PrimaryCallState.ButtonState.ENABLED)
    }
    override fun setEndCallButtonEnabled(enabled: Boolean, animate: Boolean) { endEnabled = enabled }
    override fun showManageConferenceCallButton(visible: Boolean) {
        conference = visible
        showButton(BUTTON_MANAGE_VOICE_CONFERENCE, visible)
        enableButton(BUTTON_MANAGE_VOICE_CONFERENCE, visible)
    }
    override fun isManageConferenceVisible() = conference
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent) { event.text.add(primaryInfoState.name() ?: primaryInfoState.number()) }
    override fun showNoteSentToast() { Toast.makeText(context, R.string.expressive_note_sent, Toast.LENGTH_SHORT).show() }
    override fun updateInCallScreenColors() {}
    override fun onInCallScreenDialpadVisibilityChange(isShowing: Boolean) { dialpad = isShowing }
    override fun getAnswerAndDialpadContainerResourceId() = R.id.incall_dialpad_container
    override fun showLocationUi(locationUi: Fragment?) {
        if (!isAdded) return
        val old = childFragmentManager.findFragmentById(R.id.incall_location_holder)
        if (locationUi != null) {
            locationVisible = true
            view?.post { if (isAdded && view != null) childFragmentManager.beginTransaction()
                .replace(R.id.incall_location_holder, locationUi).commitAllowingStateLoss() }
        } else {
            locationVisible = false
            if (old != null) childFragmentManager.beginTransaction().remove(old).commitAllowingStateLoss()
        }
    }
    override fun isShowingLocationUi() = locationVisible
    override fun getInCallScreenFragment(): Fragment = this
    override fun getInCallButtonUiFragment(): Fragment = this
    override fun showButton(buttonId: Int, show: Boolean) { shown[buttonId] = show }
    override fun enableButton(buttonId: Int, enable: Boolean) { enabledButtons[buttonId] = enable }
    override fun setEnabled(on: Boolean) { controlsEnabled = on }
    override fun setHold(on: Boolean) { held = on }
    override fun setCameraSwitched(isBackFacingCamera: Boolean) {}
    override fun setVideoPaused(isPaused: Boolean) {}
    override fun setAudioState(audioState: CallAudioState) { audio = audioState }
    override fun setCallRecordingState(isRecording: Boolean) { recording = isRecording }
    override fun setCallRecordingDuration(durationMs: Long) { recordingDuration = durationMs }
    override fun requestCallRecordingPermissions(permissions: Array<String>) {
        if (isControlEnabled(BUTTON_RECORD_CALL)) recordPermission.launch(permissions)
    }
    override fun updateButtonStates() {}
    override fun showAudioRouteSelector() {
        if (!isControlEnabled(BUTTON_AUDIO)) return
        if (requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        else openAudioRoutes()
    }
    private fun openAudioRoutes() {
        if (isControlEnabled(BUTTON_AUDIO)) showAudioRoutes = true
    }
    override fun onAudioRouteSelected(audioRoute: Int) {
        if (isControlEnabled(BUTTON_AUDIO)) buttonDelegate.setAudioRoute(audioRoute)
    }
    override fun onAudioRouteSelectorDismiss() {}
}
