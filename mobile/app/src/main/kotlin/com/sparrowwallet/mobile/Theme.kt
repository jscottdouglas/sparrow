package com.sparrowwallet.mobile

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * "One wallet, two layers" palette (design direction ui-direction-v2): the public layer
 * wears Litecoin blue on ink navy, the private (MWEB) layer wears copper on aubergine ink.
 * Greens/ambers/reds are semantic only and never used as accents.
 */
internal object LtcPalette {
    val blueDark = Color(0xFF5E8FD8)
    val blueLight = Color(0xFF345D9D)
    val copperDark = Color(0xFFC98F52)
    val copperLight = Color(0xFFA96F35)
    val green = Color(0xFF46B380)
    val greenLight = Color(0xFF2E8B62)
    val amber = Color(0xFFD9A83B)
    val red = Color(0xFFC75B54)
}

internal fun sparrowColorScheme(privateLayer: Boolean, dark: Boolean): ColorScheme = when {
    dark && !privateLayer -> darkColorScheme(
        primary = LtcPalette.blueDark, onPrimary = Color(0xFF0B1120),
        tertiary = LtcPalette.copperDark, onTertiary = Color(0xFF130E1D),
        background = Color(0xFF0B1120), onBackground = Color(0xFFE8EDF6),
        surface = Color(0xFF0B1120), onSurface = Color(0xFFE8EDF6),
        surfaceVariant = Color(0xFF141E33), onSurfaceVariant = Color(0xFF8CA0BE),
        outline = Color(0xFF22304C), outlineVariant = Color(0xFF1B2740),
        error = LtcPalette.red, onError = Color(0xFF0B1120)
    )
    dark && privateLayer -> darkColorScheme(
        primary = LtcPalette.copperDark, onPrimary = Color(0xFF130E1D),
        tertiary = LtcPalette.blueDark, onTertiary = Color(0xFF0B1120),
        background = Color(0xFF130E1D), onBackground = Color(0xFFEDE8F2),
        surface = Color(0xFF130E1D), onSurface = Color(0xFFEDE8F2),
        surfaceVariant = Color(0xFF1C1529), onSurfaceVariant = Color(0xFFA292BC),
        outline = Color(0xFF322747), outlineVariant = Color(0xFF251C36),
        error = LtcPalette.red, onError = Color(0xFF130E1D)
    )
    !dark && !privateLayer -> lightColorScheme(
        primary = LtcPalette.blueLight, onPrimary = Color.White,
        tertiary = LtcPalette.copperLight, onTertiary = Color.White,
        background = Color(0xFFF2F5FA), onBackground = Color(0xFF16233B),
        surface = Color(0xFFF2F5FA), onSurface = Color(0xFF16233B),
        surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF64758F),
        outline = Color(0xFFDCE3EE), outlineVariant = Color(0xFFE9EEF6),
        error = LtcPalette.red, onError = Color.White
    )
    else -> lightColorScheme(
        primary = LtcPalette.copperLight, onPrimary = Color.White,
        tertiary = LtcPalette.blueLight, onTertiary = Color.White,
        background = Color(0xFFF7F4F1), onBackground = Color(0xFF2A2233),
        surface = Color(0xFFF7F4F1), onSurface = Color(0xFF2A2233),
        surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF776A85),
        outline = Color(0xFFE2D9E6), outlineVariant = Color(0xFFEFE9E3),
        error = LtcPalette.red, onError = Color.White
    )
}
