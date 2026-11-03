package org.chesscoach.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.chesscoach.coach.analysis.MomentKind

/**
 * Оформление в духе настроек iPhone: светло-серый фон, белые карточки
 * со скруглением, один синий акцент. Цвета собраны здесь, чтобы экраны не
 * разъезжались оттенками.
 */
object Palette {
    val accent = Color(0xFF007AFF)
    val background = Color(0xFFF2F2F7)
    val card = Color.White
    val secondaryText = Color(0xFF6E6E73)
    val separator = Color(0xFFE5E5EA)
    val danger = Color(0xFFFF3B30)
    val success = Color(0xFF34C759)
    val warning = Color(0xFFFF9500)
    val selected = Color(0xFFD6E8FF)
}

/** Цвет метки ключевого момента: красный — ваши промахи, зелёный — ваши удачи и чужие ошибки. */
fun momentColor(kind: MomentKind): Color = when (kind) {
    MomentKind.MY_ERROR, MomentKind.TIME_TROUBLE, MomentKind.LOST_ADVANTAGE -> Palette.danger
    MomentKind.MISSED_CHANCE -> Palette.warning
    MomentKind.OPPONENT_ERROR, MomentKind.MY_STRIKE -> Palette.success
    MomentKind.OPPONENT_STRIKE -> Color(0xFFAF52DE)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            primaryContainer = Palette.selected,
            background = Palette.background,
            surface = Palette.background,
            surfaceVariant = Palette.card,
            surfaceContainerHighest = Palette.card,
            surfaceContainerHigh = Palette.card,
            surfaceContainer = Palette.card,
            surfaceContainerLow = Palette.card,
            secondaryContainer = Palette.selected,
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
            headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
        ),
        content = content,
    )
}
