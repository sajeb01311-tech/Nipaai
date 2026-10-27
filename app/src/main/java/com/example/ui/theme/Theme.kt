package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val NipaCommandColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = Color(0xFF0A2A38),
    onPrimaryContainer = ElectricCyan,
    secondary = IrisViolet,
    onSecondary = StarlightWhite,
    secondaryContainer = Color(0xFF201642),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = MagentaPulse,
    onTertiary = StarlightWhite,
    tertiaryContainer = Color(0xFF3B122B),
    onTertiaryContainer = Color(0xFFFBCFE8),
    background = ObsidianVoid,
    onBackground = StarlightWhite,
    surface = ObsidianSurface,
    onSurface = StarlightWhite,
    surfaceVariant = ObsidianElevated,
    onSurfaceVariant = MistSilver,
    outline = GlassBorderSubtle,
    outlineVariant = Color(0x1FFFFFFF),
    error = CoralAlert,
    onError = StarlightWhite,
    errorContainer = Color(0xFF3B0D19),
    onErrorContainer = Color(0xFFFECDD3)
)

val NipaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun NipaTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = NipaCommandColorScheme,
        typography = NipaTypography,
        shapes = NipaShapes,
        content = content
    )
}
