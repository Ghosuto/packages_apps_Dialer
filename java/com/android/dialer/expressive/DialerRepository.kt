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
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog.Calls
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.android.dialer.telecom.TelecomUtil
import com.android.dialer.spam.CarrierCallIdentityStore
import com.android.dialer.voip.VoipCallHistory
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Person(val id: Long, val lookupKey: String, val name: String, val number: String, val starred: Boolean) {
    val uri: Uri get() = ContactsContract.Contacts.getLookupUri(id, lookupKey)
}
data class DialerData(val people: List<Person> = emptyList(), val calls: List<RecentCall> = emptyList(),
    val contactsAllowed: Boolean = false, val callsAllowed: Boolean = false,
    val recentGroups: List<RecentCallGroup> = groupConsecutiveCalls(calls))
internal data class NumberBlockState(val blocked: Boolean, val available: Boolean)

class DialerRepository(private val context: Context) {
    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    suspend fun load(): DialerData = withContext(Dispatchers.IO) {
        val people = mutableListOf<Person>()
        val calls = mutableListOf<RecentCall>()
        val contactsAllowed = has(Manifest.permission.READ_CONTACTS)
        val callsAllowed = has(Manifest.permission.READ_CALL_LOG)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val country = (telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: telephony?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country).uppercase(Locale.ROOT)
        val numberKeys = mutableMapOf<String, String>()
        val simNumbers = mutableMapOf<PhoneAccountHandle, Int?>()
        if (contactsAllowed) context.contentResolver.query(Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER,
                Phone.STARRED, Phone.IS_SUPER_PRIMARY, Phone.IS_PRIMARY),
            null, null, "${Phone.SORT_KEY_PRIMARY} ASC, ${Phone.IS_SUPER_PRIMARY} DESC, " +
                "${Phone.IS_PRIMARY} DESC, ${Phone._ID} ASC")?.use { cursor ->
            val numberKeysByContact = mutableMapOf<Long, MutableSet<String>>()
            while (cursor.moveToNext()) {
                val number = cursor.getString(3).orEmpty()
                val contactId = cursor.getLong(0)
                val numberKey = recentNumberKey(number, country).ifBlank { number }
                if (!numberKeysByContact.getOrPut(contactId) { mutableSetOf() }.add(numberKey)) continue
                people += Person(contactId, cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty().ifBlank { number }, number, cursor.getInt(4) != 0)
            }
        }
        if (callsAllowed) context.contentResolver.query(Calls.CONTENT_URI,
            arrayOf(Calls._ID, Calls.CACHED_NAME, Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE,
                Calls.NUMBER_PRESENTATION, Calls.PHONE_ACCOUNT_COMPONENT_NAME, Calls.PHONE_ACCOUNT_ID),
            "${Calls.TYPE} != ?", arrayOf(Calls.VOICEMAIL_TYPE.toString()), "${Calls.DATE} DESC, ${Calls._ID} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(2).orEmpty()
                val account = TelecomUtil.composePhoneAccountHandle(cursor.getString(7), cursor.getString(8))
                val simNumber = account?.let {
                    if (!simNumbers.containsKey(it)) simNumbers[it] = resolveSimNumber(it)
                    simNumbers[it]
                }
                val identity = CarrierCallIdentityStore.get(context, number, account)
                calls += RecentCall(cursor.getLong(0), cursor.getString(1).orEmpty(), number,
                    cursor.getLong(3), cursor.getLong(4), cursor.getInt(5), cursor.getInt(6),
                    numberKeys.getOrPut(number) { recentNumberKey(number, country) }, simNumber,
                    identity.getSpamStatus(), identity.getVerificationStatus())
            }
        }
        calls += VoipCallHistory.load(context)
        calls.sortByDescending { it.date }
        DialerData(people.distinctBy { it.id to it.number }, calls, contactsAllowed, callsAllowed)
    }
    private fun resolveSimNumber(account: PhoneAccountHandle): Int? {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            val subscriptionId = context.getSystemService(TelephonyManager::class.java)
                ?.createForPhoneAccountHandle(account)?.subscriptionId ?: return null
            if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) return null
            val slot = context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex ?: return null
            slot.takeIf { it >= 0 }?.plus(1)
        } catch (_: SecurityException) {
            null
        }
    }
    suspend fun setStarred(person: Person) = withContext(Dispatchers.IO) {
        context.contentResolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, person.id),
            ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (person.starred) 0 else 1) }, null, null)
    }
    suspend fun deleteCalls(calls: List<RecentCall>) = withContext(Dispatchers.IO) {
        if (calls.any { it.voipPackage != null }) VoipCallHistory.delete(context, calls)
        calls.filter { it.voipPackage == null }.map { it.id }.distinct().chunked(200).forEach { ids ->
            context.contentResolver.delete(Calls.CONTENT_URI,
                "${Calls._ID} IN (${ids.joinToString(",") { "?" }})", ids.map { it.toString() }.toTypedArray())
        }
    }
    internal suspend fun blockState(number: String): NumberBlockState = withContext(Dispatchers.IO) {
        val available = number.isNotBlank() && BlockedNumberContract.canCurrentUserBlockNumbers(context)
        NumberBlockState(available && BlockedNumberContract.isBlocked(context, number), available)
    }
    internal suspend fun setBlocked(number: String, blocked: Boolean): NumberBlockState = withContext(Dispatchers.IO) {
        check(number.isNotBlank() && BlockedNumberContract.canCurrentUserBlockNumbers(context))
        if (blocked) {
            if (!BlockedNumberContract.isBlocked(context, number)) {
                checkNotNull(context.contentResolver.insert(BlockedNumbers.CONTENT_URI,
                    ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }))
            }
        } else {
            BlockedNumberContract.unblock(context, number)
        }
        blockState(number).also { check(it.available && it.blocked == blocked) }
    }
}
