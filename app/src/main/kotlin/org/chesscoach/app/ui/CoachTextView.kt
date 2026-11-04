package org.chesscoach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.chesscoach.coach.text.TextRenderer

/**
 * Текст тренера по его разметке (см. [TextRenderer.SECTION_MARK]): группы —
 * карточками с иконкой и итогом, разделы — жирными заголовками, пункты —
 * буллитами с отступом. Ходы стоят плашками, цена и оценка — цветными метками.
 * Слова те же, что в тексте: разметка ничего не прячет и не добавляет.
 */
@Composable
fun CoachText(text: String) {
    val marker = System.lineSeparator() + TextRenderer.REFERENCE_TITLE + System.lineSeparator()
    val main = text.substringBefore(marker).trimEnd()
    val reference = text.substringAfter(marker, "")
    var referenceOpen by remember(text) { mutableStateOf(false) }
    val blocks = remember(main) { coachBlocks(main) }

    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { CoachBlockCard(it) }
        }
    }
    if (reference.isNotEmpty()) {
        TextButton(onClick = { referenceOpen = !referenceOpen }) {
            Text(if (referenceOpen) "Скрыть термины" else "Показать термины")
        }
        if (referenceOpen) {
            SelectionContainer {
                Column { reference.lines().filter { it.isNotBlank() }.forEach { CoachLine(it) } }
            }
        }
    }
}

/** Группа разбора: заголовок со своей иконкой и итогом, ниже её строки. Null-заголовок — шапка разбора. */
private data class CoachBlock(val title: String?, val tag: String?, val lines: List<String>)

private fun coachBlocks(text: String): List<CoachBlock> {
    val blocks = mutableListOf<CoachBlock>()
    var title: String? = null
    var tag: String? = null
    var lines = mutableListOf<String>()
    fun flush() {
        if (lines.isNotEmpty() || title != null) blocks += CoachBlock(title, tag, lines)
    }
    text.lines().forEach { line ->
        if (line.startsWith(TextRenderer.GROUP_MARK)) {
            flush()
            val rest = line.removePrefix(TextRenderer.GROUP_MARK)
            title = rest.substringBefore(TextRenderer.GROUP_MARK).trim()
            tag = rest.substringAfter(TextRenderer.GROUP_MARK, "").trim().ifEmpty { null }
            lines = mutableListOf()
        } else if (line.isNotBlank()) {
            lines += line
        }
    }
    flush()
    return blocks
}

/** Иконка группы — символом, одинаковым в приложении и на сайте. */
private fun groupIcon(title: String?): String = when {
    title == null -> "⚑"
    title.startsWith("До хода") || title.startsWith("Позиция") -> "↶"
    title.startsWith("После хода") -> "↷"
    else -> "♞"
}

private fun isMoveGroup(title: String?): Boolean =
    title != null && (title.startsWith("Ваш ход") || title.startsWith("Ход ") || title.startsWith("Что играть"))

@Composable
private fun CoachBlockCard(block: CoachBlock) {
    val move = isMoveGroup(block.title)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.card)
            .border(if (move) 2.dp else 1.dp, if (move) Palette.accent else Palette.separator, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Шапка разбора: первая строка — стадия и номер хода, мелко.
        val lines = if (block.title == null) block.lines.drop(1) else block.lines
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(groupIcon(block.title), fontSize = 18.sp, color = if (move) Palette.accent else Palette.secondaryText)
            Spacer(Modifier.width(8.dp))
            Text(
                block.title ?: "Итог хода",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = if (move) Palette.accent else Color.Unspecified,
                modifier = Modifier.weight(1f),
            )
            block.tag?.let { Tag(it) }
        }
        if (block.title == null) {
            block.lines.firstOrNull()?.let { Text(it, fontSize = 13.sp, color = Palette.secondaryText) }
        }
        lines.forEach { CoachLine(it) }
    }
}

@Composable
private fun Tag(text: String) {
    val (background, foreground) = tagColors(text)
    Text(
        text,
        fontSize = 12.sp,
        color = foreground,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private val DangerTint = Color(0xFFFDE4E2)
private val SuccessTint = Color(0xFFDDF5E3)
private val NeutralTint = Color(0xFFEDEDF2)
private val DangerText = Color(0xFFB3261E)
private val SuccessText = Color(0xFF1E7A34)

/** Красное — потеря или угроза читателю, зелёное — выигрыш, остальное серое. */
private fun tagColors(text: String): Pair<Color, Color> = when {
    text.contains("зевок") || text.contains("ошибка") || text.contains("потеря") && !text.contains("потеря 0.0") ||
        text.contains("рушится") || text.startsWith("-") && text.contains("для вас") -> DangerTint to DangerText
    text.startsWith("+") && text.contains("для вас") || text.contains("лучший") -> SuccessTint to SuccessText
    else -> NeutralTint to Palette.secondaryText
}

@Composable
private fun CoachLine(line: String) {
    when {
        line.startsWith(TextRenderer.SECTION_MARK) -> {
            val title = line.removePrefix(TextRenderer.SECTION_MARK)
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = sectionColor(title),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        }
        else -> {
            val level = when {
                line.startsWith(TextRenderer.BULLET) -> 0
                line.startsWith(TextRenderer.SUB_BULLET) -> 1
                line.startsWith(TextRenderer.SUB_SUB_BULLET) -> 2
                else -> -1
            }
            if (level < 0) {
                Text(styledLine(line), fontSize = 16.sp, lineHeight = 23.sp)
                return
            }
            val body = line.drop(2)
            Row(Modifier.padding(start = (level * 18).dp)) {
                Text(
                    line.take(1),
                    fontSize = 16.sp,
                    color = Palette.secondaryText,
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    styledLine(body),
                    fontSize = if (level == 0) 16.sp else 15.sp,
                    lineHeight = 23.sp,
                    color = when {
                        body.startsWith("Но после") -> DangerText
                        body.startsWith("Размены (справочно)") || level == 2 && isReference(body) -> Palette.secondaryText
                        else -> Color.Unspecified
                    },
                )
            }
        }
    }
}

private fun isReference(body: String): Boolean = body.contains("материал равный") || body.contains(" в пользу ")

/** План соперника и плохой ход — красным, свой план — зелёным: как стрелки на доске. */
private fun sectionColor(title: String): Color = when {
    title.startsWith("План соперника") -> DangerText
    title.startsWith("Мой план") -> SuccessText
    title.startsWith("Сыгранный ход") && (title.contains("зевок") || title.contains("ошибка")) -> DangerText
    else -> Color.Unspecified
}

/**
 * Жирная метка до двоеточия ("Взятия:", "Вывод:"), ходы плашками и цветные
 * метки цены в скобках. Разбор строки — [CoachMarkup], общий с сайтом по правилам.
 */
private fun styledLine(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    CoachMarkup.labelEnd(text)?.let { addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, it) }
    CoachMarkup.moveRuns(text).forEach { range ->
        addStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = NeutralTint),
            range.first,
            range.last + 1,
        )
    }
    CoachMarkup.tags(text).forEach { range ->
        val (background, foreground) = tagColors(text.substring(range.first + 1, range.last))
        addStyle(SpanStyle(background = background, color = foreground, fontSize = 13.sp), range.first, range.last + 1)
    }
}

/**
 * Правила разметки строки. Те же правила повторены на сайте в app.js: что
 * считается меткой, ходом и ценой, должно совпадать.
 */
object CoachMarkup {
    private const val MAX_LABEL = 42

    /** Конец метки "Взятия:" — индекс после двоеточия. Null — метки нет. */
    fun labelEnd(text: String): Int? {
        val colon = text.indexOf(": ").takeIf { it in 1..MAX_LABEL } ?: text.takeIf { it.endsWith(":") && it.length <= MAX_LABEL }?.let { it.length - 1 }
            ?: return null
        val label = text.substring(0, colon)
        if (label.contains("(")) return null
        return colon + 1
    }

    private val sanMove = Regex("^(O-O-O|O-O|[KQRBN][a-h]?[1-8]?x?[a-h][1-8]|[a-h]x[a-h][1-8](=[QRBN])?|[a-h][1-8](=[QRBN])?)[+#]?$")
    private val moveNumber = Regex("^[0-9]+([.]|[.][.][.]|…)$")

    private fun core(token: String): String = token.trimStart('(').trimEnd('.', ',', ';', ':', ')')

    private fun definite(core: String): Boolean =
        core.first() in "KQRBNO" || core.contains('x') || core.contains('+') || core.contains('#') || core.contains('=')

    /**
     * Ходы плашками: подряд идущие записи ходов, если среди них есть хотя бы
     * одна несомненная (фигура, взятие, шах). Голое поле "d4" в тексте — не ход.
     */
    fun moveRuns(text: String): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        var end = -1
        var hasDefinite = false
        var index = 0
        fun close() {
            if (start >= 0 && hasDefinite) runs += start..end
            start = -1
            hasDefinite = false
        }
        for (token in text.split(" ")) {
            val tokenStart = index
            index += token.length + 1
            val core = core(token)
            val isMove = core.isNotEmpty() && sanMove.matches(core)
            val isNumber = core.isNotEmpty() && moveNumber.matches(core)
            if (isMove || isNumber) {
                val coreStart = tokenStart + token.indexOf(core)
                if (start < 0) start = coreStart
                end = coreStart + core.length - 1
                if (isMove && definite(core)) hasDefinite = true
                // Знак препинания после хода закрывает вариант.
                if (token.length > core.length + token.indexOf(core)) close()
            } else {
                close()
            }
        }
        close()
        return runs
    }

    /** Скобки с ценой или оценкой: "(-6 для вас)", "(оценка +2.6)". */
    fun tags(text: String): List<IntRange> {
        val found = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val open = text.indexOf('(', from).takeIf { it >= 0 } ?: break
            val close = text.indexOf(')', open).takeIf { it >= 0 } ?: break
            val inside = text.substring(open + 1, close)
            if (inside.contains("для вас") || inside.contains("для белых") || inside.contains("для чёрных") ||
                inside.startsWith("оценка") || inside.startsWith("потеря") || inside.startsWith("слабее") ||
                inside.startsWith("равноценно")
            ) {
                found += open..close
            }
            from = close + 1
        }
        return found
    }
}
