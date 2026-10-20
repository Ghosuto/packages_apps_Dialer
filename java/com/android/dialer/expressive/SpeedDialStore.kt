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

import android.content.Context

data class SpeedDialEntry(val name: String, val number: String)

class SpeedDialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Map<Int, SpeedDialEntry> = buildMap {
        for (key in SPEED_DIAL_KEYS) {
            val number = preferences.getString(numberKey(key), null)?.trim().orEmpty()
            if (number.isBlank()) continue
            val name = preferences.getString(nameKey(key), null)?.trim().orEmpty().ifBlank { number }
            put(key, SpeedDialEntry(name, number))
        }
    }

    fun assign(key: Int, entry: SpeedDialEntry) {
        require(key in SPEED_DIAL_KEYS)
        require(entry.number.isNotBlank())
        preferences.edit()
            .putString(nameKey(key), entry.name.ifBlank { entry.number })
            .putString(numberKey(key), entry.number)
            .apply()
    }

    fun remove(key: Int) {
        require(key in SPEED_DIAL_KEYS)
        preferences.edit().remove(nameKey(key)).remove(numberKey(key)).apply()
    }

    private fun nameKey(key: Int) = "slot_${key}_name"

    private fun numberKey(key: Int) = "slot_${key}_number"

    companion object {
        private const val PREFERENCES = "expressive_speed_dial"
        val SPEED_DIAL_KEYS = 2..9
    }
}
