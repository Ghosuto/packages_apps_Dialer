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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.android.dialer.expressive

import android.os.Bundle
import android.widget.LinearLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.android.dialer.R
import com.android.dialer.app.settings.DialerSettingsActivity

class ExpressiveSettingsActivity : FragmentActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private var heading by mutableStateOf<CharSequence>("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val sections = mapOf(
            DialerSettingsActivity.PrefsFragment::class.java.name to R.string.expressive_settings,
            "com.android.dialer.app.settings.DisplayOptionsSettingsFragment" to R.string.display_options_title,
            "com.android.dialer.app.settings.SoundSettingsFragment" to R.string.sounds_and_vibration_title,
            "com.android.dialer.app.settings.OtherSettingsFragment" to R.string.other_settings_label,
            "com.android.dialer.lookup.LookupSettingsFragment" to R.string.lookup_settings_label,
            "com.android.dialer.localvoicemail.LocalVoicemailSettingsFragment" to R.string.local_voicemail_title)
        val rootRoute = intent.data?.schemeSpecificPart?.takeIf { it in sections }
            ?: DialerSettingsActivity.PrefsFragment::class.java.name
        val rootHeading = getString(sections.getValue(rootRoute))
        heading = savedInstanceState?.getCharSequence("heading") ?: rootHeading
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) heading = rootHeading
            else heading = supportFragmentManager.getBackStackEntryAt(supportFragmentManager.backStackEntryCount - 1).name.orEmpty()
        }
        val content = FragmentContainerView(this).apply { id = R.id.content_frame }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        val toolbar = ComposeView(this).apply {
            id = R.id.expressive_settings_toolbar
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DialerExpressiveTheme {
                    val background = MaterialTheme.colorScheme.surface
                    SideEffect { content.setBackgroundColor(background.toArgb()) }
                    Surface(color = background) {
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 20.dp)) {
                            FilledTonalIconButton(onClick = { onBackPressedDispatcher.onBackPressed() },
                                modifier = Modifier.padding(top = 8.dp)) {
                                Symbol(DialerSymbol.Back, stringResource(R.string.expressive_back))
                            }
                            val headingEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                            val headingSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                            AnimatedContent(heading.toString(),
                                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 16.dp),
                                transitionSpec = {
                                    (fadeIn(headingEffects) + slideInVertically(headingSpatial) { it / 5 })
                                        .togetherWith(fadeOut(headingEffects))
                                }, label = "settings heading") { title ->
                                Text(title, Modifier.semantics { heading() },
                                    style = MaterialTheme.typography.headlineLargeEmphasized)
                            }
                        }
                    }
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (savedInstanceState == null) {
            val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, rootRoute)
            supportFragmentManager.beginTransaction().setReorderingAllowed(true)
                .replace(R.id.content_frame, fragment).commit()
        }
    }
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment!!)
        fragment.arguments = pref.extras
        supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN).replace(R.id.content_frame, fragment)
            .addToBackStack(pref.title?.toString()).commit()
        heading = pref.title ?: ""
        return true
    }
    override fun setTitle(title: CharSequence?) { super.setTitle(title); heading = title ?: "" }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putCharSequence("heading", heading); super.onSaveInstanceState(outState)
    }
}
