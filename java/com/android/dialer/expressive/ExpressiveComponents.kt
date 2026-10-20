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

import androidx.annotation.DrawableRes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.dialer.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

/** Material Symbols Rounded, shared by the Compose screens. */
enum class DialerSymbol(@DrawableRes val drawableRes: Int) {
    Expand(R.drawable.ic_ms_expand_more_24),
    Check(R.drawable.ic_ms_check_24),
    Sim(R.drawable.ic_ms_sim_card_24),
    Bell(R.drawable.ic_ms_notifications_24),
    Vibrate(R.drawable.ic_ms_vibration_24),
    Accessibility(R.drawable.ic_ms_accessibility_new_24),
    Lock(R.drawable.ic_ms_lock_24),
    Info(R.drawable.ic_ms_info_24),
    Backspace(R.drawable.ic_ms_backspace_24),
    Copy(R.drawable.ic_ms_content_copy_24),
    Share(R.drawable.ic_ms_share_24),
    Edit(R.drawable.ic_ms_edit_24),
    Photo(R.drawable.ic_ms_photo_camera_24),
    Work(R.drawable.ic_ms_work_24),
    Headset(R.drawable.ic_ms_headphones_24),
    Bluetooth(R.drawable.ic_ms_bluetooth_24),
    EndCall(R.drawable.ic_ms_call_end_24),
    Phone(R.drawable.ic_ms_call_24),
    History(R.drawable.ic_ms_history_24),
    People(R.drawable.ic_ms_group_24),
    Star(R.drawable.ic_ms_star_24),
    Search(R.drawable.ic_ms_search_24),
    Back(R.drawable.ic_ms_arrow_back_24),
    Close(R.drawable.ic_ms_close_24),
    More(R.drawable.ic_ms_more_vert_24),
    Keypad(R.drawable.ic_ms_dialpad_24),
    Voicemail(R.drawable.ic_ms_voicemail_24),
    Delete(R.drawable.ic_ms_delete_24),
    Message(R.drawable.ic_ms_chat_24),
    Add(R.drawable.ic_ms_add_24),
    Mic(R.drawable.ic_ms_mic_24),
    Speaker(R.drawable.ic_ms_volume_up_24),
    Pause(R.drawable.ic_ms_pause_24),
    Stop(R.drawable.ic_ms_stop_24),
    Play(R.drawable.ic_ms_play_arrow_24),
    Save(R.drawable.ic_ms_save_24),
    Swap(R.drawable.ic_ms_swap_calls_24),
    Video(R.drawable.ic_ms_videocam_24),
    Record(R.drawable.ic_ms_fiber_manual_record_24),
    Block(R.drawable.ic_ms_block_24),
    Settings(R.drawable.ic_ms_settings_24),
    Person(R.drawable.ic_ms_person_24)
}

@Composable
fun Symbol(symbol: DialerSymbol, description: String? = null, modifier: Modifier = Modifier.size(24.dp)) {
    Icon(painterResource(symbol.drawableRes), description, modifier)
}

/** Connected surfaces use generous outer corners and a small gap at their shared edges. */
internal fun dialerGroupedShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 28.dp else 4.dp,
    topEnd = if (first) 28.dp else 4.dp,
    bottomStart = if (last) 28.dp else 4.dp,
    bottomEnd = if (last) 28.dp else 4.dp
)

@Composable
internal fun DialerEmblem(symbol: DialerSymbol, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialShapes.Cookie9Sided.toShape(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Symbol(symbol, modifier = Modifier.fillMaxSize(.44f))
        }
    }
}

@Composable
fun ExpressiveTile(
    onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    highlightOnPress: Boolean = false, onLongClickLabel: String? = null,
    color: Color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by produceState(false, interaction, enabled) {
        value = false
        if (enabled) {
            interaction.interactions.filterIsInstance<PressInteraction>().collectLatest { event ->
                when (event) {
                    is PressInteraction.Press -> value = true
                    is PressInteraction.Release -> {
                        delay(120)
                        value = false
                    }
                    is PressInteraction.Cancel -> value = false
                }
            }
        }
    }
    val scale by animateFloatAsState(if (pressed) .96f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
    val radius by animateDpAsState(if (pressed) 16.dp else if (selected) 22.dp else 32.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "shape")
    val background by animateColorAsState(if (pressed && highlightOnPress) MaterialTheme.colorScheme.primary else color,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "tile color")
    val foreground by animateColorAsState(if (pressed && highlightOnPress) MaterialTheme.colorScheme.onPrimary else contentColor,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "tile content")
    val haptic = LocalHapticFeedback.current
    Surface(modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .38f },
        shape = RoundedCornerShape(radius), color = background, contentColor = foreground) {
        Row(Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)
            .combinedClickable(enabled = enabled, interactionSource = interaction,
                indication = LocalIndication.current, role = Role.Button,
                onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
                }).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            content = content)
    }
}

@Composable
fun FloatingDialerBar(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    compact: Boolean = false
) {
    val icons = listOf(
        DialerSymbol.History,
        DialerSymbol.Keypad,
        DialerSymbol.Star,
        DialerSymbol.People,
        DialerSymbol.Voicemail
    )
    val haptic = LocalHapticFeedback.current
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 0.dp, tonalElevation = 2.dp,
        modifier = Modifier.widthIn(max = if (compact) 280.dp else 440.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        Row(Modifier.padding(6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) .94f else 1f,
                    MaterialTheme.motionScheme.fastSpatialSpec(), label = "nav press")
                val radius by animateDpAsState(if (pressed) 16.dp else 26.dp,
                    MaterialTheme.motionScheme.fastSpatialSpec(), label = "nav shape")
                val weight by animateFloatAsState(if (active && !compact) 2.2f else 1f,
                    MaterialTheme.motionScheme.defaultSpatialSpec(), label = "nav width")
                val color by animateColorAsState(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    MaterialTheme.motionScheme.fastEffectsSpec(), label = "nav color")
                Row(Modifier.weight(weight).heightIn(min = 52.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(radius)).background(color)
                    .selectable(active, interactionSource = interaction, indication = LocalIndication.current,
                        role = Role.Tab, onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(index)
                        }).semantics { contentDescription = label }.padding(horizontal = 8.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CompositionLocalProvider(LocalContentColor provides if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant) {
                        Symbol(icons[index], modifier = Modifier.size(22.dp))
                        AnimatedVisibility(!compact && active,
                            enter = fadeIn() + expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()),
                            exit = fadeOut() + shrinkHorizontally()) {
                            Text(label, Modifier.padding(start = 6.dp).clearAndSetSemantics {}, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLargeEmphasized)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoading(label: String) {
    Column(Modifier.fillMaxWidth().padding(48.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)) {
            LoadingIndicator(Modifier.padding(12.dp).size(64.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ContactAvatar(name: String, modifier: Modifier = Modifier, large: Boolean = false, number: String = "") {
    val photo = rememberCallerPhoto(number, if (large) 256 else 128)
    val colors = MaterialTheme.colorScheme
    val (container, foreground) = when ((name.hashCode() and Int.MAX_VALUE) % 3) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Surface(modifier.size(if (large) 112.dp else 48.dp),
        shape = RoundedCornerShape(if (large) 40.dp else 18.dp),
        color = container, contentColor = foreground) {
        Box(contentAlignment = Alignment.Center) {
            if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.trim().firstOrNull()?.uppercase() ?: "?",
                style = if (large) MaterialTheme.typography.displaySmallEmphasized
                    else MaterialTheme.typography.titleLargeEmphasized)
        }
    }
}
