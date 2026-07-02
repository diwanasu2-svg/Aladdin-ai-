package com.aladdin.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7B8CFF),
    onPrimary = Color(0xFF0A0F52),
    primaryContainer = Color(0xFF1E2970),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF00D0C4),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF004F4B),
    onSecondaryContainer = Color(0xFF6FF7F0),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF0F1117),
    surface = Color(0xFF191C24),
    surfaceVariant = Color(0xFF1E2230),
    onSurface = Color(0xFFE2E4EF),
    onSurfaceVariant = Color(0xFFB0B4C8),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF690005)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3A4AE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF0A0F52),
    secondary = Color(0xFF006B65),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9EF2EB),
    onSecondaryContainer = Color(0xFF00201E),
    tertiary = Color(0xFF9A4C00),
    background = Color(0xFFFAFAFD),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEEFF8),
    onSurface = Color(0xFF1A1B25),
    onSurfaceVariant = Color(0xFF44475A)
)

@Composable
fun AladdinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
