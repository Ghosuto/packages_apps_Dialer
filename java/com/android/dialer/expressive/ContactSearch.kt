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

import android.telephony.PhoneNumberUtils
import java.text.Normalizer
import java.util.Locale

private val DIALABLE_QUERY = Regex("[0-9+*#()./\\- ]+")
private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

private const val RANK_NAME_PREFIX = 0
private const val RANK_WORD_PREFIX = 1
private const val RANK_NAME_CONTAINS = 2
private const val RANK_NUMBER = 3
private const val RANK_KEYPAD_LETTERS = 4

/**
 * Returns how well [name]/[number] match [query] (lower is better), or null when they don't match.
 *
 * Number and keypad-letter (T9) matching only apply when the query is dialable. Text queries must
 * not go through [PhoneNumberUtils.normalizeNumber], which turns letters into keypad digits and
 * makes e.g. "vi" (-> "84") match every contact whose number contains "84".
 */
internal fun matchRank(name: String, number: String, query: String): Int? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return RANK_NAME_PREFIX
    val folded = foldForSearch(name)
    val needle = foldForSearch(trimmed)
    if (folded.startsWith(needle)) return RANK_NAME_PREFIX
    val words = folded.split(WORD_SEPARATORS).filter(String::isNotEmpty)
    val tokens = needle.split(WORD_SEPARATORS).filter(String::isNotEmpty)
    if (tokens.isNotEmpty() && tokens.all { token -> words.any { it.startsWith(token) } }) return RANK_WORD_PREFIX
    if (folded.contains(needle)) return RANK_NAME_CONTAINS
    if (!DIALABLE_QUERY.matches(trimmed)) return null
    val digits = PhoneNumberUtils.normalizeNumber(trimmed)
    if (digits.isEmpty()) return null
    if (PhoneNumberUtils.normalizeNumber(number).contains(digits)) return RANK_NUMBER
    if (PhoneNumberUtils.convertKeypadLettersToDigits(folded).filter(Char::isDigit).contains(digits)) return RANK_KEYPAD_LETTERS
    return null
}

internal fun matches(name: String, number: String, query: String): Boolean = matchRank(name, number, query) != null

/** Filters [people] by [query], best matches first; ties keep their original (alphabetical) order. */
internal fun searchPeople(people: List<Person>, query: String): List<Person> {
    if (query.isBlank()) return people
    return people.mapNotNull { person -> matchRank(person.name, person.number, query)?.let { person to it } }
        .sortedBy { it.second }
        .map { it.first }
}

private fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)
