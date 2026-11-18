package com.mingxin.feynman.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/* ------------------------------------------------------------------ */
/*  四色系统。浅色与深色各一套，语义不变，只换色。                     */
/* ------------------------------------------------------------------ */

data class MingXinColors(
    val paper: Color,       // 页面底色
    val surface: Color,     // 卡片/输入区底色
    val ink: Color,         // 主文字
    val jade: Color,        // 主色
    val jadeDeep: Color,
    val jadeBright: Color,
    val gold: Color,
    val goldText: Color,
    val vermilion: Color,
    val onJadeSub: Color,   // 玉色底上的次级文字
    val jadeCard: Color,    // 玉色大卡片底色（深浅模式不同：深色要更沉）
    val isDark: Boolean
) {
    val ink70 get() = ink.copy(alpha = 0.70f)
    val ink12 get() = ink.copy(alpha = 0.12f)
    val ink8 get() = ink.copy(alpha = 0.08f)
    val jade10 get() = jade.copy(alpha = 0.10f)
    val gold12 get() = gold.copy(alpha = 0.12f)
    val vermilion12 get() = vermilion.copy(alpha = 0.12f)
    val jadeGrad get() = listOf(jadeBright, jadeDeep)
}

val LightColors = MingXinColors(
    paper = Color(0xFFFAF8F3),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF20362E),
    jade = Color(0xFF3B7965),
    jadeDeep = Color(0xFF2F5D4D),
    jadeBright = Color(0xFF4A8B74),
    gold = Color(0xFF9E793E),
    goldText = Color(0xFF84652F),
    vermilion = Color(0xFFB4432F),
    onJadeSub = Color(0xFFBFD8CC),
    jadeCard = Color(0xFF3B7965),
    isDark = false
)

/** 深色：墨底玉字，保留书卷气，不刺眼 */
val DarkColors = MingXinColors(
    paper = Color(0xFF141A17),
    surface = Color(0xFF1E2622),
    ink = Color(0xFFE4EDE7),
    jade = Color(0xFF6FAE95),
    jadeDeep = Color(0xFF4E8E76),
    jadeBright = Color(0xFF8CC7AF),
    gold = Color(0xFFC9A46A),
    goldText = Color(0xFFD4B47E),
    vermilion = Color(0xFFE07A62),
    onJadeSub = Color(0xFFA8CDBA),
    jadeCard = Color(0xFF2B5C4B),
    isDark = true
)

val CardRadius = 20.dp
val CardShape = RoundedCornerShape(CardRadius)
val BtnShape = RoundedCornerShape(50)

private val LocalColors = compositionLocalOf { LightColors }

object MingTheme {
    val colors: MingXinColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
}

/** 主题入口：把配色注入 CompositionLocal，并同步 Material3 的 ColorScheme */
@Composable
fun MingXinTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(
        primary = c.jade, onPrimary = Color(0xFF0F1512),
        background = c.paper, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.paper, onSurfaceVariant = c.ink,
        secondary = c.gold, error = c.vermilion, onError = Color(0xFF1A0E0B),
        outline = c.ink.copy(alpha = 0.45f), outlineVariant = c.ink.copy(alpha = 0.20f)
    ) else lightColorScheme(
        primary = c.jade, onPrimary = Color.White,
        background = c.paper, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.paper, onSurfaceVariant = c.ink,
        secondary = c.gold, error = c.vermilion,
        outline = c.ink.copy(alpha = 0.45f), outlineVariant = c.ink.copy(alpha = 0.20f)
    )
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
