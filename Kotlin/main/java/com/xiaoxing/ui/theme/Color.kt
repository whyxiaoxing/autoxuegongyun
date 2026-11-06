package com.xiaoxing.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Dark palette — deep navy surfaces, blue action colour, mint running state.
// ---------------------------------------------------------------------------
internal val DarkPrimary = Color(0xFF8CB4FF)
internal val DarkOnPrimary = Color(0xFF0B1B34)
internal val DarkPrimaryContainer = Color(0xFF1D3A63)
internal val DarkOnPrimaryContainer = Color(0xFFD9E4FF)
internal val DarkSecondary = Color(0xFFA8B8CF)
internal val DarkOnSecondary = Color(0xFF0D1A2B)
internal val DarkSecondaryContainer = Color(0xFF223247)
internal val DarkOnSecondaryContainer = Color(0xFFDCE5F2)
internal val DarkTertiary = Color(0xFF61E2A5)
internal val DarkOnTertiary = Color(0xFF002114)
internal val DarkTertiaryContainer = Color(0xFF0E3B2A)
internal val DarkOnTertiaryContainer = Color(0xFFB6F5D5)
internal val DarkError = Color(0xFFFF8A80)
internal val DarkOnError = Color(0xFF3B0905)
internal val DarkErrorContainer = Color(0xFF5C1A14)
internal val DarkOnErrorContainer = Color(0xFFFFDAD5)
internal val DarkBackground = Color(0xFF070C14)
internal val DarkOnBackground = Color(0xFFECF2FA)
internal val DarkSurface = Color(0xFF0D1521)
internal val DarkOnSurface = Color(0xFFECF2FA)
internal val DarkSurfaceVariant = Color(0xFF182334)
internal val DarkOnSurfaceVariant = Color(0xFF9BAEC7)
internal val DarkOutline = Color(0xFF3B4C63)
internal val DarkOutlineVariant = Color(0xFF223048)
internal val DarkSurfaceContainerLowest = Color(0xFF070C14)
internal val DarkSurfaceContainerLow = Color(0xFF0B1320)
internal val DarkSurfaceContainer = Color(0xFF101A29)
internal val DarkSurfaceContainerHigh = Color(0xFF162234)
internal val DarkSurfaceContainerHighest = Color(0xFF1C2A3E)
internal val DarkInverseSurface = Color(0xFFE3EAF4)
internal val DarkInverseOnSurface = Color(0xFF1B2635)
internal val DarkInversePrimary = Color(0xFF345B9D)

// ---------------------------------------------------------------------------
// Light palette — cool paper, strong ink, same semantic accents.
// ---------------------------------------------------------------------------
internal val LightPrimary = Color(0xFF2A57C8)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFDCE6FF)
internal val LightOnPrimaryContainer = Color(0xFF001945)
internal val LightSecondary = Color(0xFF526275)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFD6E3F6)
internal val LightOnSecondaryContainer = Color(0xFF0F1D2B)
internal val LightTertiary = Color(0xFF0F7A4C)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFC7F5DD)
internal val LightOnTertiaryContainer = Color(0xFF002114)
internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)
internal val LightBackground = Color(0xFFF4F7FC)
internal val LightOnBackground = Color(0xFF101828)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightOnSurface = Color(0xFF101828)
internal val LightSurfaceVariant = Color(0xFFE9EEF6)
internal val LightOnSurfaceVariant = Color(0xFF56667A)
internal val LightOutline = Color(0xFF7D8CA1)
internal val LightOutlineVariant = Color(0xFFD7E0EC)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF8FAFD)
internal val LightSurfaceContainer = Color(0xFFF1F5FA)
internal val LightSurfaceContainerHigh = Color(0xFFEAF0F7)
internal val LightSurfaceContainerHighest = Color(0xFFE2EAF3)
internal val LightInverseSurface = Color(0xFF2B3440)
internal val LightInverseOnSurface = Color(0xFFF1F5FA)
internal val LightInversePrimary = Color(0xFFAEC6FF)

/**
 * Colours Material 3 does not expose as first-class roles.
 * Accessed via [LocalExtendedColors] so screens never hard-code state colours.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val glow: Color,
)

internal val DarkExtendedColors = ExtendedColors(
    success = DarkTertiary,
    successContainer = DarkTertiaryContainer,
    warning = Color(0xFFFFC857),
    warningContainer = Color(0xFF4A370B),
    glow = Color(0xFF3E6CC4),
)

internal val LightExtendedColors = ExtendedColors(
    success = LightTertiary,
    successContainer = LightTertiaryContainer,
    warning = Color(0xFF9A5B00),
    warningContainer = Color(0xFFFFE2B0),
    glow = Color(0xFF7BA4FF),
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtendedColors }
