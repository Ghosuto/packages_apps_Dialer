/*
 * Copyright (C) 2026 Lunaris AOSP
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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android.dialer.expressive

import android.content.Intent
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.android.dialer.R

class QuickResponsesActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DialerExpressiveTheme {
                QuickResponsesScreen(onBack = { onBackPressedDispatcher.onBackPressed() })
            }
        }
    }
}

@Composable
private fun QuickResponsesScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { QuickResponseStore(context) }
    val systemResponsesIntent = remember {
        Intent(TelecomManager.ACTION_SHOW_RESPOND_VIA_SMS_SETTINGS)
    }
    val canEditSystemResponses = remember(context) {
        systemResponsesIntent.resolveActivity(context.packageManager) != null
    }
    var responses by remember(store) { mutableStateOf(store.load()) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.respond_via_sms_setting_title),
                    style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(DialerSymbol.Back, stringResource(R.string.expressive_back))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = 20.dp,
                bottom = contentPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.expressive_quick_responses_body),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.expressive_system_responses_title),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        Text(
                            text = stringResource(R.string.expressive_system_responses_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            enabled = canEditSystemResponses,
                            onClick = {
                                context.startActivity(systemResponsesIntent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Symbol(DialerSymbol.Settings, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.expressive_edit_system_responses),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.expressive_custom_responses_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    FilledTonalButton(
                        onClick = { showAddDialog = true },
                    ) {
                        Symbol(DialerSymbol.Add, modifier = Modifier.size(20.dp))
                        Text(
                            text = stringResource(R.string.expressive_add_custom_response),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            if (responses.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            text = stringResource(R.string.expressive_custom_responses_empty),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "custom_responses_group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        responses.forEachIndexed { index, response ->
                            QuickResponseRow(
                                response = response,
                                shape = dialerGroupedShape(index == 0, index == responses.lastIndex)
                            ) {
                                store.remove(response)
                                responses = store.load()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddQuickResponseDialog(
            onDismiss = { showAddDialog = false },
            onSave = { response ->
                if (store.add(response)) {
                    responses = store.load()
                    showAddDialog = false
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun QuickResponseRow(response: String, shape: Shape, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Symbol(DialerSymbol.Message)
                }
            }
            Text(
                text = response,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            IconButton(onClick = onDelete) {
                Symbol(DialerSymbol.Delete, stringResource(R.string.expressive_delete))
            }
        }
    }
}

@Composable
private fun AddQuickResponseDialog(onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var duplicate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expressive_add_custom_response_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    duplicate = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.expressive_custom_response_hint)) },
                minLines = 2,
                maxLines = 5,
                isError = duplicate,
                supportingText = if (duplicate) {
                    { Text(stringResource(R.string.expressive_custom_response_duplicate)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = text.trim()
                    if (value.isNotEmpty()) duplicate = !onSave(value) else duplicate = true
                },
                enabled = text.trim().isNotEmpty(),
            ) {
                Text(stringResource(R.string.expressive_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.expressive_cancel))
            }
        },
    )
}
