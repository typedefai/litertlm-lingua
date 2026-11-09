package com.typedefai.lingua.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palettes used when dynamic color is unavailable (minSdk 31, so this is rare).
private val LightColors = lightColorScheme(
  primary = Color(0xFF4F46E5),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFE0E7FF),
  onPrimaryContainer = Color(0xFF1E1B4B),
  secondary = Color(0xFF0EA5E9),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFE0F2FE),
  onSecondaryContainer = Color(0xFF0C4A6E),
  tertiary = Color(0xFF10B981),
  onTertiary = Color(0xFFFFFFFF),
  background = Color(0xFFF8FAFC),
  onBackground = Color(0xFF0F172A),
  surface = Color(0xFFFFFFFF),
  onSurface = Color(0xFF0F172A),
  surfaceVariant = Color(0xFFE2E8F0),
  onSurfaceVariant = Color(0xFF475569),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFFA5B4FC),
  onPrimary = Color(0xFF1E1B4B),
  primaryContainer = Color(0xFF3730A3),
  onPrimaryContainer = Color(0xFFE0E7FF),
  secondary = Color(0xFF7DD3FC),
  onSecondary = Color(0xFF0C4A6E),
  secondaryContainer = Color(0xFF075985),
  onSecondaryContainer = Color(0xFFE0F2FE),
  tertiary = Color(0xFF6EE7B7),
  onTertiary = Color(0xFF064E3B),
  background = Color(0xFF0B1220),
  onBackground = Color(0xFFE2E8F0),
  surface = Color(0xFF111A2C),
  onSurface = Color(0xFFE2E8F0),
  surfaceVariant = Color(0xFF1E293B),
  onSurfaceVariant = Color(0xFF94A3B8),
)

@Composable
fun LinguaTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColors
    else -> LightColors
  }
  MaterialTheme(colorScheme = colorScheme, content = content)
}
