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

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal fun ColorScheme.withMonochromeColors(dark: Boolean): ColorScheme = if (dark) {
    copy(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF333333),
        onPrimaryContainer = Color.White,
        inversePrimary = Color.Black,
        secondary = Color(0xFFD4D4D4),
        onSecondary = Color(0xFF141414),
        secondaryContainer = Color(0xFF2B2B2B),
        onSecondaryContainer = Color(0xFFECECEC),
        tertiary = Color(0xFFBDBDBD),
        onTertiary = Color(0xFF141414),
        tertiaryContainer = Color(0xFF242424),
        onTertiaryContainer = Color(0xFFE2E2E2),
        background = Color.Black,
        onBackground = Color(0xFFF2F2F2),
        surface = Color.Black,
        onSurface = Color(0xFFF2F2F2),
        surfaceVariant = Color(0xFF333333),
        onSurfaceVariant = Color(0xFFBDBDBD),
        surfaceTint = Color.White,
        inverseSurface = Color(0xFFE2E2E2),
        inverseOnSurface = Color(0xFF171717),
        outline = Color(0xFF757575),
        outlineVariant = Color(0xFF3A3A3A),
        surfaceBright = Color(0xFF333333),
        surfaceDim = Color.Black,
        surfaceContainer = Color(0xFF171717),
        surfaceContainerHigh = Color(0xFF222222),
        surfaceContainerHighest = Color(0xFF2E2E2E),
        surfaceContainerLow = Color(0xFF0F0F0F),
        surfaceContainerLowest = Color.Black,
        primaryFixed = Color(0xFFE2E2E2),
        primaryFixedDim = Color(0xFFC6C6C6),
        onPrimaryFixed = Color.Black,
        onPrimaryFixedVariant = Color(0xFF333333),
        secondaryFixed = Color(0xFFDADADA),
        secondaryFixedDim = Color(0xFFBEBEBE),
        onSecondaryFixed = Color(0xFF141414),
        onSecondaryFixedVariant = Color(0xFF333333),
        tertiaryFixed = Color(0xFFD0D0D0),
        tertiaryFixedDim = Color(0xFFB4B4B4),
        onTertiaryFixed = Color(0xFF141414),
        onTertiaryFixedVariant = Color(0xFF333333)
    )
} else {
    copy(
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE2E2E2),
        onPrimaryContainer = Color.Black,
        inversePrimary = Color(0xFFE2E2E2),
        secondary = Color(0xFF3D3D3D),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8E8E8),
        onSecondaryContainer = Color(0xFF171717),
        tertiary = Color(0xFF525252),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFEDEDED),
        onTertiaryContainer = Color(0xFF1F1F1F),
        background = Color.White,
        onBackground = Color(0xFF171717),
        surface = Color.White,
        onSurface = Color(0xFF171717),
        surfaceVariant = Color(0xFFE2E2E2),
        onSurfaceVariant = Color(0xFF555555),
        surfaceTint = Color.Black,
        inverseSurface = Color(0xFF222222),
        inverseOnSurface = Color.White,
        outline = Color(0xFF757575),
        outlineVariant = Color(0xFFD6D6D6),
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFDDDDDD),
        surfaceContainer = Color(0xFFF2F2F2),
        surfaceContainerHigh = Color(0xFFEBEBEB),
        surfaceContainerHighest = Color(0xFFE2E2E2),
        surfaceContainerLow = Color(0xFFF8F8F8),
        surfaceContainerLowest = Color.White,
        primaryFixed = Color(0xFFE2E2E2),
        primaryFixedDim = Color(0xFFC6C6C6),
        onPrimaryFixed = Color.Black,
        onPrimaryFixedVariant = Color(0xFF333333),
        secondaryFixed = Color(0xFFE8E8E8),
        secondaryFixedDim = Color(0xFFCCCCCC),
        onSecondaryFixed = Color(0xFF141414),
        onSecondaryFixedVariant = Color(0xFF333333),
        tertiaryFixed = Color(0xFFEDEDED),
        tertiaryFixedDim = Color(0xFFD0D0D0),
        onTertiaryFixed = Color(0xFF141414),
        onTertiaryFixedVariant = Color(0xFF333333)
    )
}
