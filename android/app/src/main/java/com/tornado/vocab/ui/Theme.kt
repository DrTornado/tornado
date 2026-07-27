package com.tornado.vocab.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Gold = Color(0xFFE8B04B)
private val Deep = Color(0xFF171C24)
private val Panel = Color(0xFF1B212B)

private val DarkScheme = darkColorScheme(
    primary = Gold, onPrimary = Color(0xFF242B36),
    background = Deep, onBackground = Color(0xFFE8E4DA),
    surface = Panel, onSurface = Color(0xFFE8E4DA),
    secondary = Color(0xFF3E8CA8)
)

private val LightScheme = lightColorScheme(primary = Color(0xFFB07C1E), secondary = Color(0xFF2C6B84))

@Composable
fun TornadoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
