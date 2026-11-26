/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip

import android.app.Notification

internal enum class VoipNotificationState {
    INCOMING, ONGOING, OTHER;

    companion object {
        private val answerLabel = Regex("""\b(answer|accept|join|pick up)\b""", RegexOption.IGNORE_CASE)
        private val declineLabel = Regex("""\b(decline|reject|ignore|dismiss)\b""", RegexOption.IGNORE_CASE)
        private val endLabel = Regex("""\b(hang up|end call|leave call|disconnect)\b""", RegexOption.IGNORE_CASE)
        private val incomingText = Regex("""\bincoming (?:voice |audio |video |group )?call\b""", RegexOption.IGNORE_CASE)

        private val excluded = setOf(Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL,
            Notification.CATEGORY_MISSED_CALL, Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SOCIAL)

        fun detect(notification: Notification, actions: List<String>, answer: Boolean,
            decline: Boolean, hangup: Boolean): VoipNotificationState? {
            val extras = notification.extras
            val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT).map {
                extras.getCharSequence(it) ?: ""
            }
            return classify(extras.getInt(Notification.EXTRA_CALL_TYPE), notification.category,
                notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                notification.fullScreenIntent != null, actions, text, answer, decline, hangup)
        }

        internal fun classify(type: Int, category: String?, ongoing: Boolean, fullScreen: Boolean,
            actions: List<String>, text: List<CharSequence>, answer: Boolean, decline: Boolean,
            hangup: Boolean): VoipNotificationState? {
            when (type) {
                Notification.CallStyle.CALL_TYPE_INCOMING -> return INCOMING
                Notification.CallStyle.CALL_TYPE_ONGOING -> return ONGOING
                Notification.CallStyle.CALL_TYPE_SCREENING -> return OTHER
            }
            if (category in excluded) return null
            val call = category == Notification.CATEGORY_CALL
            val hasAnswer = answer || actions.any { answerLabel.containsMatchIn(it) }
            val hasDecline = decline || actions.any { declineLabel.containsMatchIn(it) }
            val hasEnd = hangup || actions.any { endLabel.containsMatchIn(it) }
            if (hasAnswer && (hasDecline || call || fullScreen)) return INCOMING
            if (hasEnd && (call || ongoing)) return ONGOING
            if (call && fullScreen || (call || fullScreen) && text.any { incomingText.containsMatchIn(it) }) {
                return INCOMING
            }
            return if (call && ongoing) OTHER else null
        }
    }
}
