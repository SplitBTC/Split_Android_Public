package com.split.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SplitColorScheme = darkColorScheme(
    primary = SplitBrandBlue,
    secondary = SplitBrandPink,
    tertiary = SplitBrandPink,
    background = SplitBlack,
    surface = SplitSurface,
    surfaceVariant = SplitSurfaceAlt,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun SplitAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SplitColorScheme,
        typography = SplitTypography,
        content = content
    )
}

