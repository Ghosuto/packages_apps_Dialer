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

import android.provider.CallLog.Calls
import android.telephony.PhoneNumberUtils
import com.android.dialer.spam.CarrierCallIdentity

data class RecentCall(val id: Long, val name: String, val number: String, val date: Long,
    val duration: Long, val type: Int, val presentation: Int, val numberKey: String = number,
    val simNumber: Int? = null,
    val spamStatus: Int = CarrierCallIdentity.SPAM_UNKNOWN,
    val verificationStatus: Int = CarrierCallIdentity.VERIFICATION_UNKNOWN,
    val voipPackage: String? = null, val voipAppName: String? = null,
    val voipContactUri: String? = null) {
    val callable: Boolean get() = voipPackage == null && presentation == Calls.PRESENTATION_ALLOWED && number.isNotBlank()
}

data class RecentCallGroup(val calls: List<RecentCall>) {
    val latest: RecentCall get() = calls.first()
    val id: Long get() = latest.id
    val preview: List<RecentCall> get() = calls.take(5)
}

/** I used to tell everyone in my friend group how beautiful she was */
internal fun groupConsecutiveCalls(calls: List<RecentCall>): List<RecentCallGroup> {
    val groups = mutableListOf<RecentCallGroup>()
    var run = mutableListOf<RecentCall>()
    for (call in calls) {
        val previous = run.lastOrNull()
        if (previous != null && !previous.isSameNumber(call)) {
            groups += RecentCallGroup(run)
            run = mutableListOf()
        }
        run += call
    }
    if (run.isNotEmpty()) groups += RecentCallGroup(run)
    return groups
}

internal fun RecentCall.isSameNumber(other: RecentCall): Boolean =
    callable && other.callable && numberKey.isNotBlank() && numberKey == other.numberKey

internal fun recentNumberKey(number: String, countryIso: String): String {
    if (PhoneNumberUtils.isUriNumber(number)) return number
    val network = PhoneNumberUtils.normalizeNumber(PhoneNumberUtils.extractNetworkPortion(number))
    if (network.isBlank()) return ""
    return (PhoneNumberUtils.formatNumberToE164(network, countryIso) ?: network) +
        PhoneNumberUtils.extractPostDialPortion(number)
}
