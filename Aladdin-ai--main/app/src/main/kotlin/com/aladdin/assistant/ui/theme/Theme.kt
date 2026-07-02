package com.aladdin.assistant.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Color Palette ────────────────────────────────────────────────────────────
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

val AladdinBlue = Color(0xFF1565C0)
val AladdinIndigo = Color(0xFF3949AB)
val AladdinTeal = Color(0xFF00897B)
val AladdinSurface = Color(0xFF1A1A2E)
val AladdinOnSurface = Color(0xFFE8EAF6)
val AladdinPrimary = Color(0xFF7C4DFF)
val AladdinSecondary = Color(0xFF448AFF)
val AladdinTertiary = Color(0xFF69F0AE)
val AladdinError = Color(0xFFCF6679)
val AladdinBackground = Color(0xFF0D0D1A)
val AladdinCard = Color(0xFF16213E)
val AladdinCardDark = Color(0xFF0F3460)

private val DarkColorScheme = darkColorScheme(
    primary = AladdinPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1F99),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = AladdinSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A3A6B),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = AladdinTertiary,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF005140),
    onTertiaryContainer = Color(0xFF86F7CA),
    error = AladdinError,
    errorContainer = Color(0xFF8C1D18),
    onError = Color.White,
    onErrorContainer = Color(0xFFF9DEDC),
    background = AladdinBackground,
    onBackground = Color(0xFFE8EAF6),
    surface = AladdinSurface,
    onSurface = AladdinOnSurface,
    surfaceVariant = Color(0xFF1E1E3F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    inversePrimary = AladdinIndigo,
    surfaceTint = AladdinPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = AladdinIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = AladdinBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF001B3F),
    tertiary = AladdinTeal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA7F3E1),
    onTertiaryContainer = Color(0xFF002117),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onError = Color.White,
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Purple80,
    surfaceTint = AladdinIndigo
)

// ─── Theme Enum ───────────────────────────────────────────────────────────────
enum class AppTheme { LIGHT, DARK, SYSTEM }

// ─── Custom accent colors ─────────────────────────────────────────────────────
val AccentColors = listOf(
    "Purple" to AladdinPrimary,
    "Blue" to AladdinSecondary,
    "Teal" to AladdinTertiary,
    "Rose" to Color(0xFFE91E63),
    "Orange" to Color(0xFFFF6D00),
    "Amber" to Color(0xFFFFAB00)
)

// ─── Typography ───────────────────────────────────────────────────────────────
val AladdinTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

// ─── Theme Composable ─────────────────────────────────────────────────────────
@Composable
fun AladdinTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (appTheme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }

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
        typography = AladdinTypography,
        content = content
    )
}
