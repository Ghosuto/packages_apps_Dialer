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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android.dialer.expressive

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.telecom.TelecomManager
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.contacts.common.compat.PhoneAccountCompat
import com.android.contacts.common.widget.SelectPhoneAccountDialogOptions
import com.android.contacts.common.widget.SelectPhoneAccountDialogOptionsUtil
import com.android.dialer.R
import com.android.dialer.location.GeoUtil
import com.android.dialer.phonenumberutil.PhoneNumberHelper
import com.android.dialer.telecom.TelecomUtil
import java.util.Locale
import java.util.WeakHashMap
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ExpressiveDialogs {
    private val recordingDialogs = WeakHashMap<Fragment, ComponentDialog>()

    @JvmStatic
    fun createSimPicker(
        context: Context, options: SelectPhoneAccountDialogOptions, initiallyDefault: Boolean,
        onDefaultChanged: Consumer<Boolean>, onSelect: IntConsumer
    ): Dialog = createDialog(context) { dialog ->
        var useDefault by rememberSaveable { mutableStateOf(initiallyDefault) }
        val accounts by produceState<List<SimAccount>?>(null, context, options) {
            value = withContext(Dispatchers.IO) {
                options.entriesList.mapIndexed { index, entry -> loadAccount(context, index, entry) }
            }
        }
        DialogContent(
            title = stringResource(if (options.hasTitle()) options.title else R.string.select_account_dialog_title),
            icon = DialerSymbol.Phone
        ) {
            val entries = accounts
            if (entries == null) ExpressiveLoading(stringResource(R.string.expressive_loading))
            else if (entries.isEmpty()) Text(stringResource(R.string.expressive_no_calling_accounts))
            else entries.forEach { account ->
                ExpressiveTile(onClick = { onSelect.accept(account.index); dialog.dismiss() },
                    modifier = Modifier.fillMaxWidth(), enabled = account.enabled, highlightOnPress = true) {
                    if (account.icon != null) Image(account.icon, null, Modifier.size(32.dp))
                    else Symbol(DialerSymbol.Phone)
                    Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(account.label, style = MaterialTheme.typography.titleMediumEmphasized)
                        if (account.number.isNotBlank()) Text(account.number, style = MaterialTheme.typography.bodyMedium)
                        if (account.hint.isNotBlank()) Text(account.hint, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (options.canSetDefault) {
                Row(Modifier.fillMaxWidth().toggleable(useDefault, role = Role.Checkbox,
                    onValueChange = { useDefault = it; onDefaultChanged.accept(it) }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useDefault, onCheckedChange = null)
                    Text(stringResource(if (options.hasSetDefaultLabel()) options.setDefaultLabel else R.string.set_default_account),
                        Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            TextButton(onClick = { dialog.cancel() }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }

    @JvmStatic
    fun showRecordingWarning(owner: Fragment, onConfirmed: Runnable) {
        val activity = owner.activity ?: return
        if (owner.view == null || recordingDialogs[owner]?.isShowing == true) return
        val lifecycle = owner.viewLifecycleOwner.lifecycle
        val dialog = createDialog(activity) { window ->
            DialogContent(stringResource(R.string.recording_warning_title), DialerSymbol.Record) {
                Text(stringResource(R.string.recording_warning_text), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { window.cancel() }) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { window.dismiss(); onConfirmed.run() }) {
                        Text(stringResource(R.string.onscreenCallRecordText))
                    }
                }
            }
        }
        recordingDialogs[owner] = dialog
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            lifecycle.removeObserver(observer)
            recordingDialogs.remove(owner)
        }
        dialog.show()
    }

    @JvmStatic
    fun showGreetingRecording(owner: Fragment, onSave: Runnable, onCancel: Runnable): ComponentDialog? {
        val activity = owner.activity ?: return null
        if (owner.view == null) return null
        val lifecycle = owner.viewLifecycleOwner.lifecycle
        val dialog = createDialog(activity) { window ->
            DialogContent(stringResource(R.string.local_voicemail_greeting_recording), DialerSymbol.Mic) {
                Text(stringResource(R.string.local_voicemail_greeting_recording_body),
                    style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { window.cancel() }) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { window.dismiss(); onSave.run() }) {
                        Text(stringResource(R.string.local_voicemail_greeting_save))
                    }
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.cancel()
        }
        lifecycle.addObserver(observer)
        dialog.setOnCancelListener { onCancel.run() }
        dialog.setOnDismissListener { lifecycle.removeObserver(observer) }
        dialog.show()
        return dialog
    }

    private fun createDialog(context: Context, content: @Composable (ComponentDialog) -> Unit): ComponentDialog {
        val dialog = ComponentDialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(ComposeView(dialog.context).apply {
            id = R.id.expressive_dialog
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DialerExpressiveTheme { content(dialog) } }
        })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.45f)
        }
        dialog.setOnShowListener {
            val metrics = context.resources.displayMetrics
            val width = minOf(metrics.widthPixels - (48 * metrics.density).toInt(), (560 * metrics.density).toInt())
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    private fun loadAccount(context: Context, index: Int, entry: SelectPhoneAccountDialogOptions.Entry): SimAccount {
        val fallback = context.getString(R.string.expressive_sim_number, index + 1)
        return try {
            val handle = SelectPhoneAccountDialogOptionsUtil.getPhoneAccountHandle(entry)
            val account = context.getSystemService(TelecomManager::class.java).getPhoneAccount(handle)
            val country = TelecomUtil.getSubscriptionInfo(context, handle).map { it.countryIso.uppercase(Locale.ROOT) }
                .orElseGet { GeoUtil.getCurrentCountryIso(context) }
            val number = account?.address?.schemeSpecificPart.orEmpty()
            SimAccount(index, account?.label?.toString()?.takeIf { it.isNotBlank() } ?: fallback,
                if (number.isBlank()) "" else PhoneNumberHelper.formatNumberForDisplay(context, number, country)?.toString().orEmpty(),
                entry.hint, entry.enabled && account != null,
                account?.let { PhoneAccountCompat.createIconDrawable(it, context)?.toBitmap(96, 96)?.asImageBitmap() })
        } catch (_: SecurityException) {
            SimAccount(index, fallback, "", context.getString(R.string.expressive_action_failed), false, null)
        }
    }
}

private data class SimAccount(val index: Int, val label: String, val number: String,
    val hint: String, val enabled: Boolean, val icon: ImageBitmap?)

@Composable
private fun DialogContent(title: String, icon: DialerSymbol, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 64.dp).coerceAtLeast(120.dp)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Symbol(icon, modifier = Modifier.padding(14.dp).size(28.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
            content()
        }
    }
}
