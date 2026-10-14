package com.tinkercobble.ham.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Teal = Color(0xFF075D59)
private val Copper = Color(0xFFB45C25)
private val Cream = Color(0xFFFFF8EB)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Copper,
    onSecondary = Color.White,
    background = Cream,
    onBackground = Color(0xFF17312E),
    surface = Color(0xFFFFFCF5),
    onSurface = Color(0xFF17312E),
    surfaceContainerLowest = Color(0xFFFFFCF5),
    surfaceContainerLow = Color(0xFFFFF8EB),
    surfaceContainer = Color(0xFFF7EFE2),
    surfaceContainerHigh = Color(0xFFF2E8D8),
    surfaceContainerHighest = Color(0xFFEADFCC),
    surfaceVariant = Color(0xFFF3E9D9),
    onSurfaceVariant = Color(0xFF465C58),
    outline = Color(0xFF728581),
    outlineVariant = Color(0xFFC9D1C6)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9ADBD1),
    onPrimary = Color(0xFF003B38),
    secondary = Color(0xFFFFB782),
    onSecondary = Color(0xFF512500),
    background = Color(0xFF102321),
    onBackground = Color(0xFFF3EEE3),
    surface = Color(0xFF1B302D),
    onSurface = Color(0xFFF3EEE3),
    surfaceContainerLowest = Color(0xFF0B1D1B),
    surfaceContainerLow = Color(0xFF152B28),
    surfaceContainer = Color(0xFF1B302D),
    surfaceContainerHigh = Color(0xFF243B37),
    surfaceContainerHighest = Color(0xFF304842),
    surfaceVariant = Color(0xFF304842),
    onSurfaceVariant = Color(0xFFD0DDD5),
    outline = Color(0xFF98ADA6),
    outlineVariant = Color(0xFF526B65)
)

@Composable
fun HamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
