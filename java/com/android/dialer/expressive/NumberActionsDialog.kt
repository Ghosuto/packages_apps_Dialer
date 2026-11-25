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

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.android.dialer.expressive

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.dialer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class SheetActionItem(
    val icon: DialerSymbol,
    val label: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

@Composable
internal fun NumberActionsDialog(
    name: String, number: String, starred: Boolean, saved: Boolean,
    onDismiss: () -> Unit, onCall: () -> Unit, onVideo: () -> Unit, onMessage: () -> Unit,
    onWhatsApp: () -> Unit,
    onFavorite: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit,
    onPhoto: () -> Unit, onRemovePhoto: () -> Unit, onRecordings: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val repository = remember(context) { DialerRepository(context) }
    val scope = rememberCoroutineScope()
    var blockState by remember(number) { mutableStateOf<NumberBlockState?>(null) }
    var confirmBlock by remember(number) { mutableStateOf(false) }
    var changingBlock by remember(number) { mutableStateOf(false) }
    LaunchedEffect(number) {
        blockState = try { repository.blockState(number) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { NumberBlockState(false, false) }
    }
    val photoState = rememberCallerPhotoState(number, 512)
    val photo = photoState.image
    val whatsappLabel = stringResource(R.string.expressive_whatsapp_message)
    val recordingsLabel = stringResource(R.string.expressive_call_recording)
    val copyLabel = stringResource(R.string.expressive_copy_number)
    val shareLabel = stringResource(R.string.expressive_share_number)
    val editLabel = stringResource(if (saved) R.string.expressive_edit_contact else R.string.expressive_create_contact)
    val photoLabel = stringResource(if (photo == null) R.string.expressive_set_caller_photo else R.string.expressive_change_caller_photo)
    val removePhotoLabel = stringResource(R.string.expressive_remove_caller_photo)
    val blockLabel = stringResource(if (blockState?.blocked == true) R.string.expressive_unblock_number else R.string.expressive_block_number)
    val reportSpamLabel = stringResource(R.string.expressive_report_spam)
    val deleteLabel = stringResource(R.string.expressive_delete_history)
    val secondaryActions = buildList {
        add(SheetActionItem(DialerSymbol.Message, whatsappLabel, enabled = number.isNotBlank(), onClick = onWhatsApp))
        add(SheetActionItem(DialerSymbol.Record, recordingsLabel, enabled = number.isNotBlank(), onClick = onRecordings))
        add(SheetActionItem(DialerSymbol.Copy, copyLabel, onClick = onCopy))
        add(SheetActionItem(DialerSymbol.Share, shareLabel, onClick = onShare))
        add(SheetActionItem(DialerSymbol.Edit, editLabel, onClick = onEdit))
        add(SheetActionItem(DialerSymbol.Photo, photoLabel, onClick = onPhoto))
        if (photoState.hasOverride) {
            add(SheetActionItem(DialerSymbol.Person, removePhotoLabel, onClick = onRemovePhoto))
        }
        add(
            SheetActionItem(
                icon = if (blockState?.blocked == true) DialerSymbol.Phone else DialerSymbol.Block,
                label = blockLabel,
                enabled = blockState?.available == true && !changingBlock,
                destructive = blockState?.blocked != true,
                onClick = { confirmBlock = true }
            )
        )
        add(
            SheetActionItem(
                icon = DialerSymbol.Block,
                label = reportSpamLabel,
                enabled = number.isNotBlank(),
                destructive = true,
                onClick = { showCarrierBlockDialog(context, number, true) }
            )
        )
        if (onDelete != null) {
            add(SheetActionItem(DialerSymbol.Delete, deleteLabel, destructive = true, onClick = onDelete))
        }
    }
    DialerModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (photo != null) {
                    Image(photo, null, Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Surface(
                        Modifier.size(80.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                name.trim().firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.headlineLargeEmphasized
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        name,
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (name != number && number.isNotBlank()) {
                        Text(
                            number,
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.Ltr,
                                fontFeatureSettings = "tnum"
                            ),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickNumberAction(
                    icon = DialerSymbol.Phone,
                    label = stringResource(R.string.expressive_call),
                    modifier = Modifier.weight(1f),
                    primary = true,
                    onClick = onCall
                )
                QuickNumberAction(
                    icon = DialerSymbol.Message,
                    label = stringResource(R.string.expressive_message),
                    modifier = Modifier.weight(1f),
                    onClick = onMessage
                )
                QuickNumberAction(
                    icon = DialerSymbol.Video,
                    label = stringResource(R.string.expressive_video),
                    modifier = Modifier.weight(1f),
                    onClick = onVideo
                )
                QuickNumberAction(
                    icon = if (saved) DialerSymbol.Star else DialerSymbol.Add,
                    label = stringResource(
                        if (!saved) R.string.expressive_save
                        else if (starred) R.string.expressive_unfavorite
                        else R.string.expressive_favorite
                    ),
                    modifier = Modifier.weight(1f),
                    selected = saved && starred,
                    onClick = onFavorite
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                secondaryActions.forEachIndexed { index, item ->
                    val contentColor = when {
                        !item.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                        item.destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Surface(
                        onClick = item.onClick,
                        enabled = item.enabled,
                        shape = dialerGroupedShape(index == 0, index == secondaryActions.lastIndex),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = contentColor
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Symbol(item.icon, modifier = Modifier.size(20.dp))
                            Text(
                                item.label,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (blockState?.available == false) {
                Text(
                    stringResource(R.string.expressive_block_unavailable),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (confirmBlock) {
        val block = blockState?.blocked != true
        AlertDialog(onDismissRequest = { if (!changingBlock) confirmBlock = false },
            title = { Text(stringResource(if (block) R.string.expressive_block_number else R.string.expressive_unblock_number)) },
            text = { Text(stringResource(if (block) R.string.expressive_block_number_body else R.string.expressive_unblock_number_body, number)) },
            confirmButton = {
                TextButton(enabled = !changingBlock, onClick = {
                    changingBlock = true
                    scope.launch {
                        try {
                            blockState = repository.setBlocked(number, block)
                            confirmBlock = false
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            Toast.makeText(context, R.string.expressive_action_failed, Toast.LENGTH_LONG).show()
                        } finally { changingBlock = false }
                    }
                }) { Text(stringResource(if (block) R.string.expressive_block_number else R.string.expressive_unblock_number)) }
            },
            dismissButton = {
                TextButton(enabled = !changingBlock, onClick = { confirmBlock = false }) {
                    Text(stringResource(R.string.expressive_cancel))
                }
            })
    }
}

@Composable
private fun QuickNumberAction(
    icon: DialerSymbol,
    label: String,
    modifier: Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val container: Color = when {
        primary -> colors.primary
        selected -> colors.secondaryContainer
        else -> colors.surfaceContainerHigh
    }
    val foreground: Color = when {
        primary -> colors.onPrimary
        selected -> colors.onSecondaryContainer
        else -> colors.onSurface
    }
    ExpressiveTile(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        restingRadius = 24.dp,
        selectedRadius = 18.dp,
        pressedRadius = 14.dp,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
        color = container,
        contentColor = foreground
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(icon, modifier = Modifier.size(22.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
