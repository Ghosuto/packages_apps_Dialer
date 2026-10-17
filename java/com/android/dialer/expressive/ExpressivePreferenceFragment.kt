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

import android.os.Bundle
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.drawable.Drawable
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.preference.*
import androidx.fragment.app.FragmentTransaction
import androidx.recyclerview.widget.RecyclerView
import com.android.dialer.R

abstract class ExpressivePreferenceFragment : PreferenceFragmentCompat() {
    private var revision by mutableIntStateOf(0)
    private var dialog by mutableStateOf<Preference?>(null)
    private var adapter: RecyclerView.Adapter<*>? = null
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { revision++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { revision++ }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { revision++ }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { revision++ }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { revision++ }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val backingView = super.onCreateView(inflater, container, state)
        return FrameLayout(requireContext()).apply {
            addView(backingView.apply { visibility = View.GONE })
            addView(ComposeView(context).apply {
                id = R.id.expressive_preferences
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    DialerExpressiveTheme {
                        val entries = remember(revision, preferenceScreen) { flatten(preferenceScreen) }
                        Surface(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                                start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
                                itemsIndexed(entries) { index, pref ->
                                    if (pref is PreferenceCategory) SectionLabel(pref.title?.toString().orEmpty())
                                    else PreferenceRow(
                                        title = pref.title?.toString().orEmpty(),
                                        summary = pref.summary?.toString().orEmpty(),
                                        enabled = pref.isEnabled,
                                        selectable = pref.isSelectable,
                                        checked = (pref as? TwoStatePreference)?.isChecked,
                                        symbol = preferenceSymbol(pref), icon = pref.icon,
                                        first = index == 0 || entries[index - 1] is PreferenceCategory,
                                        last = index == entries.lastIndex || entries[index + 1] is PreferenceCategory
                                    ) { pref.performClick(); revision++ }
                                }
                            }
                        }
                        dialog?.let { PreferenceDialog(it, { dialog = null }) { revision++ } }
                    }
                }
            }, FrameLayout.LayoutParams(-1, -1))
        }
    }
    override fun onBindPreferences() {
        super.onBindPreferences()
        adapter?.unregisterAdapterDataObserver(observer)
        adapter = listView.adapter
        adapter?.registerAdapterDataObserver(observer)
        revision++
    }
    override fun onUnbindPreferences() {
        adapter?.unregisterAdapterDataObserver(observer); adapter = null
        super.onUnbindPreferences()
    }
    override fun onDestroyView() {
        dialog = null
        super.onDestroyView()
    }
    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? {
        if (transit != FragmentTransaction.TRANSIT_FRAGMENT_OPEN && transit != FragmentTransaction.TRANSIT_FRAGMENT_CLOSE)
            return super.onCreateAnimator(transit, enter, nextAnim)
        val content = view ?: return null
        val forward = transit == FragmentTransaction.TRANSIT_FRAGMENT_OPEN
        val direction = if (content.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val distance = resources.displayMetrics.density * direction * if (forward == enter) 32f else -16f
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(content, View.TRANSLATION_X, if (enter) distance else 0f, if (enter) 0f else distance),
                ObjectAnimator.ofFloat(content, View.ALPHA, if (enter) 0f else 1f, if (enter) 1f else 0f))
            duration = if (enter) 300L else 220L
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
        }
    }
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is ListPreference || preference is EditTextPreference || preference is MultiSelectListPreference) dialog = preference
        else super.onDisplayPreferenceDialog(preference)
    }
    private fun flatten(group: PreferenceGroup?): List<Preference> = buildList {
        if (group != null) for (index in 0 until group.preferenceCount) {
            val pref = group.getPreference(index)
            if (pref.isVisible) {
                if (pref is PreferenceCategory) { add(pref); addAll(flatten(pref)) }
                else add(pref)
            }
        }
    }
}

@Composable
private fun PreferenceRow(
    title: String, summary: String, enabled: Boolean, selectable: Boolean,
    checked: Boolean?, symbol: DialerSymbol, icon: Drawable?, first: Boolean, last: Boolean, onClick: () -> Unit
) {
    val view = LocalView.current
    Surface(modifier = Modifier.padding(bottom = if (last) 0.dp else 1.dp)
        .animateContentSize(spring(.9f, 450f)), shape = RoundedCornerShape(
        topStart = if (first) 28.dp else 0.dp, topEnd = if (first) 28.dp else 0.dp,
        bottomStart = if (last) 28.dp else 0.dp, bottomEnd = if (last) 28.dp else 0.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().semantics {
                if (checked != null) toggleableState = ToggleableState(checked)
            }.clickable(enabled = enabled && selectable,
                role = if (checked != null) Role.Switch else Role.Button, onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onClick()
                }).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.size(44.dp), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        val bitmap = remember(icon) { icon?.toBitmap(96, 96)?.asImageBitmap() }
                        if (bitmap != null) Image(bitmap, null, Modifier.size(24.dp))
                        else Symbol(symbol)
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
                    if (summary.isNotBlank()) {
                        Text(summary, Modifier.padding(top = if (title.isNotBlank()) 4.dp else 0.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
                    }
                }
                if (checked != null) Switch(checked, null, enabled = enabled,
                    thumbContent = if (checked) {
                        { Symbol(DialerSymbol.Check, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null)
            }
        }
    }
}

private fun preferenceSymbol(pref: Preference): DialerSymbol {
    val route = pref.fragment.orEmpty().substringAfterLast('.')
    val action = pref.intent?.action.orEmpty()
    val key = pref.key.orEmpty()
    return when {
        route == "DisplayOptionsSettingsFragment" || key.contains("photo") -> DialerSymbol.Photo
        route == "SoundSettingsFragment" -> DialerSymbol.Speaker
        route == "CallHistoryBackupFragment" -> DialerSymbol.History
        route == "LookupSettingsFragment" || key.contains("lookup") -> DialerSymbol.Search
        route == "VoicemailSettingsFragment" || route == "PhoneAccountSelectionFragment" -> DialerSymbol.Voicemail
        action.contains("ACCESSIBILITY") -> DialerSymbol.Accessibility
        action.contains("BLOCKED") || key.contains("dnd") -> DialerSymbol.Block
        action.contains("SMS") || key.contains("post_call") -> DialerSymbol.Message
        action.contains("PHONE_ACCOUNTS") || pref.javaClass.simpleName == "AccountPreference" -> DialerSymbol.Sim
        action.contains("CALL_SETTINGS") -> DialerSymbol.Phone
        key.contains("record") -> DialerSymbol.Record
        key.contains("vibrat") -> DialerSymbol.Vibrate
        key.contains("monochrome") -> DialerSymbol.Photo
        key.contains("hide_call_status_bar") -> DialerSymbol.Accessibility
        key.contains("compact_floating_bar") -> DialerSymbol.History
        key == "bottom_dialpad_backspace" -> DialerSymbol.Backspace
        key.contains("hide_keyboard_on_scroll") -> DialerSymbol.Keypad
        key == "group_contacts_by_name" -> DialerSymbol.People
        key.contains("hide_saved_contact") -> DialerSymbol.Lock
        key.contains("caller_screen_spam_warning") -> DialerSymbol.Block
        key.contains("ringtone") || key.contains("notification") -> DialerSymbol.Bell
        key.contains("dtmf") -> DialerSymbol.Keypad
        key.contains("pin") -> DialerSymbol.Lock
        key.contains("archive") -> DialerSymbol.History
        key.contains("voicemail") -> DialerSymbol.Voicemail
        key.contains("sort") -> DialerSymbol.Swap
        key.contains("display") -> DialerSymbol.People
        key.contains("mute") -> DialerSymbol.Mic
        key.contains("announcement") -> DialerSymbol.Speaker
        key.contains("auto_answer_external_audio") -> DialerSymbol.Headset
        key.contains("export_call_history") -> DialerSymbol.Share
        key.contains("import_call_history") -> DialerSymbol.Save
        key == "incoming_call_style" -> DialerSymbol.Phone
        key.contains("proximity") -> DialerSymbol.Phone
        !pref.isSelectable -> DialerSymbol.Info
        else -> DialerSymbol.Settings
    }
}

@Composable
private fun PreferenceDialog(pref: Preference, dismiss: () -> Unit, changed: () -> Unit) {
    var text by remember(pref) { mutableStateOf((pref as? EditTextPreference)?.text.orEmpty()) }
    var values by remember(pref) { mutableStateOf((pref as? MultiSelectListPreference)?.values.orEmpty().toSet()) }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text((pref as? DialogPreference)?.dialogTitle?.toString() ?: pref.title?.toString().orEmpty()) },
        text = {
            when (pref) {
                is ListPreference -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pref.entries.size) { index ->
                        val selected = pref.findIndexOfValue(pref.value) == index
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)
                                else Color.Transparent
                        ) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                    val value = pref.entryValues[index].toString()
                                    if (pref.callChangeListener(value)) { pref.value = value; changed() }; dismiss()
                                }).padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected, onClick = null)
                                Text(pref.entries[index].toString(), Modifier.weight(1f).padding(start = 16.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                is MultiSelectListPreference -> LazyColumn {
                    items(pref.entries.size) { index ->
                        val value = pref.entryValues[index].toString()
                        Row(Modifier.fillMaxWidth().clickable {
                            values = if (value in values) values - value else values + value
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(value in values, null); Text(pref.entries[index].toString(), Modifier.padding(start = 12.dp))
                        }
                    }
                }
                is EditTextPreference -> OutlinedTextField(text, { text = it }, singleLine = true)
            }
        },
        confirmButton = { if (pref !is ListPreference) TextButton(onClick = {
            when (pref) {
                is EditTextPreference -> if (pref.callChangeListener(text)) pref.text = text
                is MultiSelectListPreference -> if (pref.callChangeListener(values)) pref.values = values
            }
            changed(); dismiss()
        }) { Text(stringResource(R.string.expressive_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.expressive_cancel)) } })
}
