package com.fyrbyadditive.famesmartblinds.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimary,
    secondary = BlueSecondary,
    tertiary = CyanAccent,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = CardBackgroundDark,
    onPrimary = OnSurfaceDark,
    onSecondary = OnSurfaceDark,
    onTertiary = OnSurfaceDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = StatusError,
    onError = OnSurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    secondary = BlueSecondary,
    tertiary = CyanAccent,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = CardBackgroundLight,
    onPrimary = OnSurfaceDark,
    onSecondary = OnSurfaceDark,
    onTertiary = OnSurfaceDark,
    onBackground = OnSurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    error = StatusError,
    onError = OnSurfaceDark
)

@Composable
fun FAMESmartBlindsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disabled to maintain brand colors
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
        typography = Typography,
        content = content
    )
}
