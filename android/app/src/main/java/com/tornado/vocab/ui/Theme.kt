package com.tornado.vocab.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tornado.vocab.data.ThemeMode
import com.tornado.vocab.data.WordStatus

/*
 * هوية تورنادو: ذهبي على ليل عميق — منقولة عن تطبيق الويب.
 *
 * والتدفئة هنا لمسة لا إعادة تصميم: الأسطح الداكنة كانت مائلة إلى الأزرق
 * الرمادي، وهو لون شاشات لا لون ورق. إزاحة صغيرة نحو البنّي تجعل الذهبي يجلس
 * على خلفية من جنسه فيبدو دفء المطبوع القديم بدل برود الشاشة.
 *
 * لم يتغيّر مقاس ولا موضع ولا شكل عنصر — الفرق في درجة اللون وحدها، وبقدر
 * لا يُلاحَظ إلا بالمقارنة.
 */
private val Gold = Color(0xFFE8B04B)
private val GoldDeep = Color(0xFFD9973A)
private val Ink = Color(0xFF1A1720)
private val Panel = Color(0xFF201C27)
private val PanelHigh = Color(0xFF292432)
private val Line = Color(0xFF433C4C)
private val Parchment = Color(0xFFEDE6D8)
private val Muted = Color(0xFF9A9188)
private val Teal = Color(0xFF3E8CA8)

/** ألوان الحالة — نفس الشيفرة اللونية المستخدمة في كل شاشات التطبيق */
object StatusColors {
    val New = Color(0xFF5B8DD9)
    val Missed = Color(0xFFD96A5B)
    val Known = Color(0xFF5B9E6F)
    val Accent = Gold

    fun of(status: WordStatus): Color = when (status) {
        WordStatus.NEW -> New
        WordStatus.MISSED -> Missed
        WordStatus.KNOWN -> Known
    }

    fun label(status: WordStatus): String = when (status) {
        WordStatus.NEW -> "New"
        WordStatus.MISSED -> "Missed"
        WordStatus.KNOWN -> "Known"
    }
}

private val DarkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF242B36),
    primaryContainer = GoldDeep,
    onPrimaryContainer = Color(0xFF1A1004),
    secondary = Teal,
    onSecondary = Color(0xFF04212B),
    background = Ink,
    onBackground = Parchment,
    surface = Panel,
    onSurface = Parchment,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Color(0xFF302A39),
    error = Color(0xFFD96A5B),
    onError = Color(0xFF2B0A06)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8A5D0B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDEA8),
    onPrimaryContainer = Color(0xFF2B1C00),
    secondary = Color(0xFF1F5468),
    background = Color(0xFFFCF8F2),
    onBackground = Color(0xFF1D1B16),
    surface = Color(0xFFFFFBF4),
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFF0E7D8),
    onSurfaceVariant = Color(0xFF544F3E),
    outline = Color(0xFF847A69)
)

/**
 * لمسة عتيقة في الخط لا في التخطيط.
 *
 * لا يتغيّر مقاس ولا موضع ولا شكل زر — التعديل في الحرف وحده: الكلمة المدروسة
 * تأخذ خطاً ذا سيرفات يشبه حرف القاموس المطبوع، وتباعداً أوسع قليلاً يمنحها
 * وقاراً. وما عداه يبقى بخط النظام لأن الواجهة تُقرأ لا تُتأمّل.
 *
 * الحدّ هنا مقصود: تغيير الخط في كل مكان يجعل التطبيق أثقل قراءةً لا أجمل.
 */
private val Serif = FontFamily.Serif

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Serif,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Serif,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp
    ),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
)

val LocalIsDark = staticCompositionLocalOf { true }

@Composable
fun TornadoTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
