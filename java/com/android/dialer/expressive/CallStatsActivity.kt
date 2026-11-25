/*
 * Copyright (C) 2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import android.Manifest
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.android.dialer.R
import com.android.dialer.callstats.CallStatsDetails
import com.android.dialer.callstats.CallStatsDetailActivity
import com.android.dialer.callstats.CallStatsQueryHandler
import com.android.dialer.phonenumbercache.ContactInfo
import com.android.dialer.util.PermissionsUtil
import java.util.Calendar

class CallStatsActivity : FragmentActivity(), CallStatsQueryHandler.Listener {
    private var details by mutableStateOf<List<CallStatsDetails>>(emptyList())
    private var total by mutableStateOf<CallStatsDetails?>(null)
    private var loading by mutableStateOf(false)
    private var permissionGranted by mutableStateOf(false)
    private var selectedRange by mutableStateOf(DateRange.ALL_TIME)
    private var selectedType by mutableStateOf(CallType.ALL)
    private lateinit var queryHandler: CallStatsQueryHandler

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) loadCalls() else loading = false
    }

    private val callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            loadCalls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queryHandler = CallStatsQueryHandler(contentResolver, this)
        contentResolver.registerContentObserver(CallLog.CONTENT_URI, true, callLogObserver)
        setContent {
            DialerExpressiveTheme {
                CallStatsScreen(
                    details = details,
                    total = total,
                    loading = loading,
                    permissionGranted = permissionGranted,
                    range = selectedRange,
                    type = selectedType,
                    onRangeSelected = { selectedRange = it; loadCalls() },
                    onTypeSelected = { selectedType = it },
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onContactSelected = ::openDetails,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.READ_CALL_LOG) }
                )
            }
        }
        permissionGranted = PermissionsUtil.hasPermission(this, Manifest.permission.READ_CALL_LOG)
        if (permissionGranted) {
            loadCalls()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(callLogObserver)
        super.onDestroy()
    }

    override fun onCallsFetched(calls: Map<ContactInfo, CallStatsDetails>) {
        val values = calls.values.toList()
        details = values
        total = CallStatsDetails(null, 0, null, null, null, null, null, 0).also { summary ->
            values.forEach(summary::mergeWith)
        }
        loading = false
    }

    private fun loadCalls() {
        if (!permissionGranted) return
        details = emptyList()
        total = null
        loading = true
        queryHandler.fetchCalls(selectedRange.from(), selectedRange.to(), null)
    }

    private fun openDetails(item: CallStatsDetails) {
        val summary = total ?: return
        startActivity(Intent(this, CallStatsDetailActivity::class.java).apply {
            putExtra(CallStatsDetailActivity.EXTRA_DETAILS, item)
            putExtra(CallStatsDetailActivity.EXTRA_TOTAL, summary)
            putExtra(CallStatsDetailActivity.EXTRA_FROM, selectedRange.from())
            putExtra(CallStatsDetailActivity.EXTRA_TO, selectedRange.to())
        })
    }
}

private enum class DateRange {
    ALL_TIME,
    TODAY,
    SEVEN_DAYS,
    THIRTY_DAYS;

    fun from(): Long {
        if (this == ALL_TIME) return -1
        val calendar = Calendar.getInstance()
        if (this == TODAY) {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        } else {
            calendar.add(Calendar.DAY_OF_YEAR, if (this == SEVEN_DAYS) -7 else -30)
        }
        return calendar.timeInMillis
    }

    fun to(): Long = if (this == ALL_TIME) -1 else System.currentTimeMillis()
}

private enum class CallType {
    ALL,
    INCOMING,
    OUTGOING,
    MISSED
}

private data class CallMetric(val count: Int, val duration: Long)

private data class ContactMetric(
    val details: CallStatsDetails,
    val name: String,
    val number: String,
    val metric: CallMetric
)

@Composable
private fun CallStatsScreen(
    details: List<CallStatsDetails>,
    total: CallStatsDetails?,
    loading: Boolean,
    permissionGranted: Boolean,
    range: DateRange,
    type: CallType,
    onRangeSelected: (DateRange) -> Unit,
    onTypeSelected: (CallType) -> Unit,
    onBack: () -> Unit,
    onContactSelected: (CallStatsDetails) -> Unit,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val metrics = remember(details, type) {
        details.map { item ->
            val metric = when (type) {
                CallType.ALL -> CallMetric(item.getTotalCount(), item.getFullDuration())
                CallType.INCOMING -> CallMetric(item.incomingCount, item.inDuration)
                CallType.OUTGOING -> CallMetric(item.outgoingCount, item.outDuration)
                CallType.MISSED -> CallMetric(item.missedCount, 0)
            }
            val name = item.displayName?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.call_statistics_unknown)
            ContactMetric(item, name, item.displayNumber ?: item.number.orEmpty(), metric)
        }.filter { it.metric.count > 0 }
    }
    val summary = remember(total, type) {
        total?.let { item ->
            when (type) {
                CallType.ALL -> CallMetric(item.getTotalCount(), item.getFullDuration())
                CallType.INCOMING -> CallMetric(item.incomingCount, item.inDuration)
                CallType.OUTGOING -> CallMetric(item.outgoingCount, item.outDuration)
                CallType.MISSED -> CallMetric(item.missedCount, 0)
            }
        } ?: CallMetric(0, 0)
    }
    val sortByDuration = type != CallType.MISSED && summary.duration > 0
    val sortedMetrics = remember(metrics, sortByDuration) {
        if (sortByDuration) metrics.sortedByDescending { it.metric.duration }
        else metrics.sortedByDescending { it.metric.count }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 20.dp, top = 8.dp)) {
                Symbol(DialerSymbol.Back, stringResource(R.string.expressive_back))
            }
            Text(
                stringResource(R.string.call_statistics_title),
                Modifier.padding(start = 24.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                style = MaterialTheme.typography.headlineLargeEmphasized
            )
            if (!permissionGranted) {
                PermissionCard(onRequestPermission)
            } else LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ChoiceRow(
                        values = DateRange.values().toList(),
                        selected = range,
                        label = { it.label() },
                        onSelected = onRangeSelected
                    )
                }
                item {
                    ChoiceRow(
                        values = CallType.values().toList(),
                        selected = type,
                        label = { it.label() },
                        onSelected = onTypeSelected
                    )
                }
                item {
                    SummaryCard(summary, range, type)
                }
                item {
                    MetricCards(details, type)
                }
                item {
                    Text(
                        stringResource(R.string.call_statistics_top_contacts),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (loading) {
                    item { ExpressiveLoading(stringResource(R.string.expressive_loading)) }
                } else if (sortedMetrics.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.call_statistics_no_calls),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                } else {
                    val topContacts = sortedMetrics.take(8)
                    val maximum = topContacts.first().metric.let {
                        if (sortByDuration) it.duration.toFloat() else it.count.toFloat()
                    }.coerceAtLeast(1f)
                    item(key = "top_contacts_group") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            topContacts.forEachIndexed { index, item ->
                                ContactMetricCard(
                                    item = item,
                                    maximum = maximum,
                                    sortByDuration = sortByDuration,
                                    shape = dialerGroupedShape(index == 0, index == topContacts.lastIndex),
                                    onContactSelected = onContactSelected
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    ExpressiveChoiceGroup(
        values = values,
        selected = selected,
        label = label,
        onSelected = onSelected,
        modifier = Modifier.fillMaxWidth(),
        scrollable = true
    )
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.call_statistics_permission_body),
                    style = MaterialTheme.typography.bodyLarge)
                ExpressiveTile(
                    onClick = onRequestPermission,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(stringResource(R.string.call_statistics_permission_action),
                        style = MaterialTheme.typography.labelLargeEmphasized)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: CallMetric, range: DateRange, type: CallType) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                "${range.label().uppercase()} · ${type.label().uppercase()}",
                style = MaterialTheme.typography.labelLargeEmphasized
            )
            Text(
                formatDuration(summary.duration),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.displaySmallEmphasized.copy(fontFeatureSettings = "tnum")
            )
            Text(
                stringResource(R.string.call_statistics_matching_calls, summary.count),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
private fun MetricCards(details: List<CallStatsDetails>, selectedType: CallType) {
    val metrics = listOf(
        CallType.INCOMING to CallMetric(details.sumOf { it.incomingCount }, details.sumOf { it.inDuration }),
        CallType.OUTGOING to CallMetric(details.sumOf { it.outgoingCount }, details.sumOf { it.outDuration }),
        CallType.MISSED to CallMetric(details.sumOf { it.missedCount }, 0)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.forEach { (kind, metric) ->
            val selected = kind == selectedType
            Surface(
                Modifier.weight(1f).heightIn(min = 112.dp),
                shape = RoundedCornerShape(24.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(kind.label(), style = MaterialTheme.typography.labelLargeEmphasized)
                    Text(
                        pluralStringResource(R.plurals.call, metric.count, metric.count),
                        style = MaterialTheme.typography.titleLargeEmphasized.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (kind == CallType.MISSED) stringResource(R.string.call_statistics_unanswered)
                        else formatDuration(metric.duration),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactMetricCard(
    item: ContactMetric,
    maximum: Float,
    sortByDuration: Boolean,
    shape: Shape,
    onContactSelected: (CallStatsDetails) -> Unit
) {
    val value = if (sortByDuration) item.metric.duration.toFloat() else item.metric.count.toFloat()
    Surface(
        Modifier.fillMaxWidth().clickable { onContactSelected(item.details) }
            .semantics { role = Role.Button },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(item.name, Modifier.size(48.dp), number = item.number)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (item.number.isNotBlank() && item.name != item.number) {
                    Text(item.number, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth((value / maximum).coerceIn(0f, 1f)).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDuration(item.metric.duration),
                    style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum")
                )
                Text(
                    pluralStringResource(R.plurals.call, item.metric.count, item.metric.count),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DateRange.label(): String = stringResource(when (this) {
    DateRange.ALL_TIME -> R.string.call_statistics_all_time
    DateRange.TODAY -> R.string.call_statistics_today
    DateRange.SEVEN_DAYS -> R.string.call_statistics_seven_days
    DateRange.THIRTY_DAYS -> R.string.call_statistics_thirty_days
})

@Composable
private fun CallType.label(): String = stringResource(when (this) {
    CallType.ALL -> R.string.call_statistics_all
    CallType.INCOMING -> R.string.call_statistics_incoming
    CallType.OUTGOING -> R.string.call_statistics_outgoing
    CallType.MISSED -> R.string.call_statistics_missed
})

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${remainingSeconds}s"
    }
}
