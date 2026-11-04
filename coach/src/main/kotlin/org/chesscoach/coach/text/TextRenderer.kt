package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.BadBishopFact
import org.chesscoach.coach.model.CaptureCheck
import org.chesscoach.coach.model.ProbedMove
import org.chesscoach.coach.model.MateGuard
import org.chesscoach.coach.model.AllowedMate
import org.chesscoach.coach.model.AllowedMateReason
import org.chesscoach.coach.analysis.explainsDanger
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.CheckedAdvice
import org.chesscoach.coach.model.DevelopmentPlan
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.DevelopmentFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.ExchangeFact
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.detectors.SeeCalculator
import org.chesscoach.coach.model.EndgameFact
import org.chesscoach.coach.model.EndgameKind
import org.chesscoach.coach.model.ForkThreatFact
import org.chesscoach.coach.model.MatePattern
import org.chesscoach.coach.model.MaterialVerdict
import org.chesscoach.coach.model.PawnBreakFact
import org.chesscoach.coach.model.RemoveDefenderFact
import org.chesscoach.coach.model.WeakSquareFact
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OpenFileRookFact
import org.chesscoach.coach.model.OpeningCaveat
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PawnWeaknessKind
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.PlanIdea
import org.chesscoach.coach.model.PlanStep
import org.chesscoach.coach.model.PositionCharacter
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.PreviousMove
import org.chesscoach.coach.model.NaturalMove
import org.chesscoach.coach.model.HumanMove
import org.chesscoach.coach.model.ProphylaxisFact
import org.chesscoach.coach.model.Reason
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.StrategicIdea
import org.chesscoach.coach.model.ThreatFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import java.util.Locale

/**
 * Сборка [PositionExplanation] в текст разбора.
 *
 * У [render] один публичный параметр — уже готовая структура фактов, и ни
 * одного метода, принимающего произвольную строку: каждое предложение
 * получается из конкретного факта функцией, типизированной по его классу.
 * Именно это делает выдуманную вилку структурно невозможной — придумать её
 * значило бы сначала создать несуществующий [ForkFact], а не просто
 * дописать фразу.
 *
 * Сантипешки печатаются только в шапке (через готовый [ScoreFormat]) — ни в
 * одном из разделов ниже число сантипешек не появляется, только категории
 * ("заметный перевес", тир причины, [MaterialVerdict]).
 *
 * Редакторская сдержанность — тоже часть контракта, не украшение: живой
 * прогон на настоящем движке показал, что "каждое предложение из факта" без
 * присмотра превращается в свалку из всех фактов тира подряд, с повторами
 * между разделами. Поэтому здесь есть три механизма, а не один:
 *  - [dedupeFacts] убирает факт, чей текст совпадает с уже показанным в том же
 *    списке, и факт "фигура висит", который сама вилка уже назвала целью;
 *  - [RenderMemory] не даёт одному и тому же предложению прозвучать дважды в
 *    разных разделах одного разбора (поэтому раздел БЕЗОПАСНОСТЬ КОРОЛЯ молчит,
 *    если то же самое уже было сказано в причинах хода);
 *  - [MAX_FACTS_PER_REASON] и [MAX_THREAT_CONSEQUENCES] ограничивают не то,
 *    какие факты бывают, а сколько из уже отобранных попадает в один пункт —
 *    отбор всё равно идёт по фактам, а не по придуманному тексту.
 */
object TextRenderer {

    /**
     * Разметка текста для экранов, одинаковая в приложении и на сайте:
     *  - "━━ До хода ━━ оценка -3.9" — заголовок группы, после второй метки её итог;
     *  - "## Лучший ход" — заголовок раздела;
     *  - "• ", "◦ ", "▪ " — пункт, вложенный пункт и пункт третьего уровня.
     * Без экрана текст читается и так: метки не прячут ни слова.
     */
    const val SECTION_MARK = "## "
    const val BULLET = "• "
    const val SUB_BULLET = "◦ "
    const val SUB_SUB_BULLET = "▪ "

    /** Заголовок справочного блока: экраны сворачивают всё, что идёт после него. */
    const val REFERENCE_TITLE = SECTION_MARK + "Термины"

    /** "Один-два самых весомых" — сколько фактов показываем в одном пункте причины, остальное отбрасываем. */
    private const val MAX_FACTS_PER_REASON = 2

    /** В угрозе — сам ход соперника и не больше двух следствий, объясняющих опасность. */
    private const val MAX_THREAT_CONSEQUENCES = 2

    /** Размены — справка: длинный список заслонил бы то, что действительно выигрывает материал. */
    private const val MAX_EXCHANGES = 3

    /** Разбирать материал начинаем со старших фигур: лишний ферзь важнее лишней пешки. */
    private val MATERIAL_ORDER = listOf(
        PieceType.QUEEN,
        PieceType.ROOK,
        PieceType.BISHOP,
        PieceType.KNIGHT,
        PieceType.PAWN,
    )

    /** Потеря от фигуры и выше: такой зевок называем раньше любой тактики помельче. */
    private const val HEAVY_LOSS_CP = 300

    /** Выше этого числа точная цифра теряет смысл: упущенный мат даёт десятки пешек. */
    private const val COLLAPSE_CP = 1000

    /** Потеря, в которой есть мат: мат считается как сто пешек, такое число ничего не говорит. */
    private const val MATE_LOSS_CP = 5000

    /**
     * [viewer] — цвет читателя, если он известен (разбор своей партии). Тогда
     * планы подписаны "мой" и "соперника" относительно него, а не стороны, чей
     * ход: иначе на ходах соперника блоки менялись местами. Без читателя планы
     * подписаны цветом. [scoreLines] — печатать ли строки "оценка до и после хода":
     * экран разбора партии показывает их сам в заголовке хода.
     */
    fun render(explanation: PositionExplanation, viewer: Side? = null, scoreLines: Boolean = true): String {
        val glossary = Glossary()
        val pool = TemplatePool(explanation.fen)
        val memory = RenderMemory()
        // Вилка накрывает свои цели по всему разбору, а не только внутри
        // своего же пункта: если "Ферзь на e2 потерян" всплывёт в другом
        // разделе, вилка, которая уже назвала e2 целью, поглощает и его.
        val forkedSquares = collectAllFacts(explanation).filterIsInstance<ForkFact>().flatMap { it.targets }.toSet()

        val body = buildString {
            append(header(explanation, scoreLines))
            var group: String? = null
            for (section in readingOrder(explanation, viewer)) {
                // Оценка до хода и общая справка о позиции — один блок: раньше это
                // были два раздела, и материал называли дважды.
                if (section is Section.EvalReason && explanation.section<Section.PositionAssessment>() != null) continue
                val sectionBody = renderSection(section, explanation, pool, glossary, memory, forkedSquares, viewer) ?: continue
                val sectionGroup = groupOf(section, explanation, viewer)
                appendLine()
                appendLine()
                if (sectionGroup != group) {
                    group = sectionGroup
                    append(GROUP_MARK + " " + sectionGroup + " " + GROUP_MARK)
                    groupTag(section, explanation)?.let { append(" $it") }
                    // Заголовок группы и есть заголовок этих разделов: второй раз не повторяем.
                    if (section is Section.PositionAssessment || section is Section.AfterMove || section is Section.EvalReason) {
                        appendLine()
                        append(sectionBody)
                        continue
                    }
                    appendLine()
                    appendLine()
                }
                append(SECTION_MARK + titleOf(section, explanation, viewer))
                appendLine()
                append(sectionBody)
            }
        }

        val footnotes = glossary.footnotes()
        if (footnotes.isEmpty()) return body

        return buildString {
            append(body)
            appendLine()
            appendLine()
            append(REFERENCE_TITLE)
            footnotes.forEach { term ->
                appendLine()
                append(BULLET + "${capitalize(term.label)} — ${term.definition}.")
            }
        }
    }

    /**
     * Итог группы рядом с её заголовком: оценка до хода, ярлык и потеря хода,
     * оценка после. Каждое число подписано: итог — словом "оценка", разница —
     * словом "потеря".
     */
    private fun groupTag(first: Section, explanation: PositionExplanation): String? = when (first) {
        is Section.EvalReason, is Section.PositionAssessment, is Section.MyPosition, is Section.MyAttack ->
            explanation.scoreFromWhite?.let { "оценка " + ScoreFormat.short(it) }
        is Section.AfterMove -> first.scoreFromWhite?.let { "оценка " + ScoreFormat.short(it) }
        else -> explanation.section<Section.PlayedMove>()?.takeIf { explanation.playedMoveSan != null }?.let { played ->
            when {
                played.matchesBest -> "лучший ход"
                played.centipawnLoss < LOSS_SHOWN_CP -> classificationLabel(played.classification)
                else -> classificationLabel(played.classification) + ", " + lossPhrase(played.centipawnLoss)
            }
        }
    }

    /** "пропускает мат: Qxh2#" — в скобки заголовка хода. */
    private fun allowedMateMark(mate: AllowedMate): String =
        if (mate.movesToMate == 1 && mate.mateSan.isNotEmpty()) "пропускает мат: " + SanRuFormatter.move(mate.mateSan)
        else "пропускает мат в " + mate.movesToMate + " " + movesWord(mate.movesToMate)

    /**
     * Почему ход пропускает мат: "g3 перекрывает слону f4 защиту поля h2 — чёрные
     * ставят мат Qxh2#." Защитника называем поимённо: его и надо было беречь.
     */
    private fun allowedMateSentence(mate: AllowedMate, playedSan: String, mover: Side): String {
        val played = SanRuFormatter.move(playedSan)
        val opponent = SideLexicon.name(mover.flip())
        val san = SanRuFormatter.move(mate.mateSan)
        val piece = mate.defenderPiece
        val from = mate.defender
        val target = mate.target
        if (mate.movesToMate > 1 || mate.mateSan.isEmpty()) {
            return "После $played у " + SideLexicon.name(mover.flip(), GrammaticalCase.GENITIVE) + " форсированный мат в " + mate.movesToMate + " " + movesWord(mate.movesToMate) + "."
        }
        return when {
            mate.reason == AllowedMateReason.MOVES_DEFENDER && piece != null && from != null && target != null ->
                "$played уводит " + PieceLexicon.name(piece, GrammaticalCase.ACCUSATIVE) + " " + sq(from) + " с защиты поля " + sq(target) + " — $opponent ставят мат $san."
            mate.reason == AllowedMateReason.BLOCKS_DEFENDER && piece != null && from != null && target != null ->
                "$played перекрывает " + PieceLexicon.name(piece, GrammaticalCase.DATIVE) + " " + sq(from) + " защиту поля " + sq(target) + " — $opponent ставят мат $san."
            mate.reason == AllowedMateReason.IGNORES_THREAT -> "$played не защищает от угрозы $san — $opponent ставят мат."
            else -> "После $played $opponent ставят мат $san."
        }
    }

    /** Потерю меньше пяти сантипешек округление всё равно покажет нулём. */
    private const val LOSS_SHOWN_CP = 5

    /**
     * Разбор варианта, который человек набрал сам, — отдельным коротким блоком.
     *
     * Целиком повторять разбор позиции незачем: он уже на экране. Нужен ровно
     * ответ на вопрос "а если бы я сыграл так": лучше это или хуже, чем лучший
     * ход, и чем именно. Null — движку нечем оценить этот ход, и тогда честнее
     * промолчать, чем выдать пустой заголовок.
     */
    fun renderCandidate(explanation: PositionExplanation): String? {
        val section = explanation.section<Section.PlayedMove>() ?: return null

        if (section.matchesBest) {
            return SECTION_MARK + "Ваш вариант: " + SanRuFormatter.move(section.playedSan) +
                System.lineSeparator() + BULLET + "Это и есть лучший ход."
        }

        val title = SECTION_MARK + "Ваш вариант: " + SanRuFormatter.move(section.playedSan) + " — " +
            classificationLabel(section.classification) + damageMark(section)
        return title + System.lineSeparator() + moveExplanation(section, explanation.sideToMove, "Лучше", "Ваш ход")
    }

    // ——— Шапка ———

    private fun header(explanation: PositionExplanation, scoreLines: Boolean): String {
        val moveNumber = MoveNotation.fullMoveNumber(explanation.fen)
        val sideLabel = SideLexicon.name(explanation.sideToMove)
        val played = explanation.section<Section.PlayedMove>()
        val turning = played != null && isTurningPoint(explanation, played)

        val title = listOfNotNull(
            phaseLabel(explanation.featuresBefore.phase),
            "Ход $moveNumber, $sideLabel",
            "Ключевой момент".takeIf { turning },
        ).joinToString(" · ")

        val lines = listOfNotNull(
            verdictLine(explanation),
            worseMoveLine(explanation),
            // Строка "Сыгранное хуже" уже сказала то же самое с вариантом.
            played?.takeUnless { worseMoveLine(explanation)?.startsWith(PLAYED_WORSE) == true }?.let(::summaryLine),
        ) + scoreLine(explanation, played)?.takeIf { scoreLines }.orEmpty()
        return (listOf(title) + lines.map { BULLET + it }).joinToString(System.lineSeparator())
    }

    /** Вывод первой строкой: что делать и почему. Остальной разбор его обосновывает. */
    private fun verdictLine(explanation: PositionExplanation): String? {
        if (explanation.bestMoveSan.isEmpty()) return null
        val move = SanRuFormatter.move(explanation.bestMoveSan)
        val score = explanation.scoreFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty()
        return "Вывод: играть $move$score" + verdictReason(explanation)
    }

    private fun verdictReason(explanation: PositionExplanation): String {
        val score = explanation.scoreFromWhite
        val moverMates = score is Score.Mate &&
            (score.movesToMate > 0) == (explanation.sideToMove == Side.WHITE)
        if (moverMates) return ": ведёт к мату."
        // Под шахом любой ход прежде всего отвечает на шах: об этом первым.
        checkAnswerPhrase(explanation)?.let { answer ->
            val also = escapePhrase(explanation)?.let { " и " + decapitalize(it.removeSuffix(".")) }.orEmpty()
            return ": " + decapitalize(answer.removeSuffix(".")) + also + "."
        }
        // Снять угрозу важнее, чем то, что ход заодно берёт: без этого теряется больше.
        removedThreat(explanation)?.let { return ": снимает угрозу " + SanRuFormatter.move(it) + "." }
        capturedPhrase(explanation)?.let { return ": $it." }
        escapePhrase(explanation)?.let { return ": " + decapitalize(it) }

        val why = explanation.section<Section.WhyThisMove>()
        val forked = collectAllFacts(explanation).filterIsInstance<ForkFact>().flatMap { it.targets }.toSet()
        val first = why?.takeUnless { it.pointIsInTheLine }?.reasons?.firstNotNullOfOrNull { reason ->
            statementsFor(reason.facts, forked, Glossary(), RenderMemory(), 1).firstOrNull()
        }
        return if (first != null) ": " + decapitalize(first) else ". Простой причины не нашлось, смотрите вариант ниже."
    }

    /**
     * Ход соперника из угрозы, которую лучший ход снимает: главная угроза или
     * любое взятие. Null — лучший ход угроз не снимает.
     */
    private fun removedThreat(explanation: PositionExplanation): String? {
        val threat = explanation.section<Section.MyPosition>()?.threat
        val why = explanation.section<Section.WhyThisMove>()?.takeIf { it.moveSan == explanation.bestMoveSan } ?: return null
        val prevented = why.reasons.flatMap { it.facts }.filterIsInstance<ProphylaxisFact>().map { it.preventedMoveSan }
        return prevented.firstOrNull { it == threat?.opponentMoveSan } ?: prevented.firstOrNull { it.contains('x') }
    }

    /** "берёт коня d4" — самая проверяемая причина хода. Null — лучший ход не взятие. */
    private fun capturedPhrase(explanation: PositionExplanation): String? {
        val uci = explanation.bestMoveUci.takeIf { it.length >= 4 } ?: return null
        val board = Board()
        if (!runCatching { board.loadFromFen(explanation.fen) }.isSuccess) return null
        val target = Square.valueOf(uci.substring(2, 4).uppercase())
        val type = board.getPiece(target).pieceType ?: return null
        val taker = board.getPiece(Square.valueOf(uci.substring(0, 2).uppercase())).pieceType
        // Взятие, на которое тут же берут обратно, — это размен, а не выигрыш:
        // "берёт пешку d4" после exd4 Nxd4 обещает пешку, которой нет.
        val line = explanation.section<Section.BestMoves>()?.computerLineUci.orEmpty()
        val recaptured = line.getOrNull(1)?.substring(2, 4) == uci.substring(2, 4)
        if (recaptured && taker != null && SeeCalculator.value(taker) == SeeCalculator.value(type)) {
            val san = explanation.section<Section.BestMoves>()?.computerLineSan.orEmpty().take(2)
            return "начинает размен на " + sq(target) + ": " + SanRuFormatter.line(san) + ", " +
                PieceLexicon.name(taker) + " за " + PieceLexicon.name(type, GrammaticalCase.ACCUSATIVE)
        }
        return "берёт " + PieceLexicon.name(type, GrammaticalCase.ACCUSATIVE) + " " + sq(target)
    }

    /**
     * "Уводит слона с h4 из-под удара gxh4." Фигура лучшего хода стоит под боем
     * или под угрозой соперника — значит, первое, что делает ход, это спасает её.
     * Без этой фразы тренер объяснял Bg3 сдвоенными пешками.
     */
    private fun escapePhrase(explanation: PositionExplanation): String? {
        val uci = explanation.bestMoveUci.takeIf { it.length >= 4 } ?: return null
        val from = runCatching { Square.valueOf(uci.substring(0, 2).uppercase()) }.getOrNull() ?: return null
        val position = explanation.section<Section.MyPosition>() ?: return null
        val threatHits = position.threat?.opponentMoveUci?.drop(2)?.take(2)?.uppercase() == from.name
        val hanging = position.loosePieces.filterIsInstance<HangingPieceFact>().firstOrNull { it.square == from }
        if (!threatHits && hanging == null) return null
        val board = Board()
        if (!runCatching { board.loadFromFen(explanation.fen) }.isSuccess) return null
        val type = board.getPiece(from).pieceType ?: return null
        val blow = position.threat?.takeIf { threatHits }?.let { " " + SanRuFormatter.move(it.opponentMoveSan) }.orEmpty()
        return "Уводит " + PieceLexicon.name(type, GrammaticalCase.ACCUSATIVE) + " с " + sq(from) + " из-под удара" + blow + "."
    }

    /** Шахующие фигуры стороне на очереди. Пусто — шаха нет. */
    private fun checkers(fen: String): List<Pair<Square, PieceType>> {
        val board = Board()
        // Позиция без короля (задачи, тесты) роняет chesslib на проверке шаха.
        if (!runCatching { board.loadFromFen(fen) }.isSuccess || !runCatching { board.isKingAttacked }.getOrDefault(false)) return emptyList()
        val side = board.sideToMove
        return com.github.bhlangonijr.chesslib.Bitboard
            .bbToSquareList(board.squareAttackedBy(board.getKingSquare(side), side.flip()))
            .mapNotNull { square -> board.getPiece(square).pieceType?.let { square to it } }
    }

    /** "ладьи e1" или "ладьи e1 и слона b5". */
    private fun checkersGenitive(checkers: List<Pair<Square, PieceType>>): String =
        russianList(checkers.map { (square, type) -> PieceLexicon.name(type, GrammaticalCase.GENITIVE) + " " + sq(square) })

    /**
     * Как лучший ход отвечает на шах: закрывается, уводит короля. Взятие
     * шахующей фигуры называет [capturedPhrase]. Null — шаха нет.
     */
    private fun checkAnswerPhrase(explanation: PositionExplanation): String? {
        val uci = explanation.bestMoveUci.takeIf { it.length >= 4 } ?: return null
        val checkers = checkers(explanation.fen).takeIf { it.isNotEmpty() } ?: return null
        val board = Board()
        if (!runCatching { board.loadFromFen(explanation.fen) }.isSuccess) return null
        val from = Square.valueOf(uci.substring(0, 2).uppercase())
        val to = Square.valueOf(uci.substring(2, 4).uppercase())
        val moved = board.getPiece(from).pieceType ?: return null
        return when {
            moved == PieceType.KING -> "Уводит короля из-под шаха " + checkersGenitive(checkers) + "."
            checkers.any { it.first == to } -> null
            else -> "Закрывается от шаха " + checkersGenitive(checkers) + " " + PieceLexicon.name(moved, GrammaticalCase.INSTRUMENTAL) + "."
        }
    }

    /** Ход, который хочется сыграть, и чем он хуже: сыгранный, а если он хорош, то ловушка. */
    private fun worseMoveLine(explanation: PositionExplanation): String? {
        val played = explanation.section<Section.PlayedMove>()
        val punished = played != null && !played.matchesBest && isPunished(played.classification)
        if (punished && played != null && played.lineSan.size >= 2) {
            val score = played.scoreAfterFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty()
            return "$PLAYED_WORSE: " + SanRuFormatter.line(played.lineSan.take(WORSE_LINE_PLIES)) + score + "."
        }
        val trap = explanation.section<Section.Trap>() ?: return null
        val score = trap.scoreFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty()
        return "Хуже: " + SanRuFormatter.line(listOf(trap.moveSan) + trap.refutationSan.take(WORSE_LINE_PLIES - 1)) + score + "."
    }

    private const val WORSE_LINE_PLIES = 3
    private const val PLAYED_WORSE = "Сыгранное хуже"

    private fun isPunished(classification: MoveClassification): Boolean =
        classification == MoveClassification.BLUNDER ||
            classification == MoveClassification.MISTAKE ||
            classification == MoveClassification.INACCURACY

    private fun phaseLabel(phase: GamePhase): String = when (phase) {
        GamePhase.OPENING -> "Дебют"
        GamePhase.MIDDLEGAME -> "Миттельшпиль"
        GamePhase.ENDGAME -> "Эндшпиль"
    }

    /**
     * Оценка до хода, после него и после лучшего. Числа всегда от лица белых, как
     * в списке ходов, поэтому дельта с плюсом — это сдвиг в пользу белых.
     *
     * Оценка позиции и есть оценка лучшего хода в ней, поэтому дельта лучшего хода
     * равна нулю: так видно, сколько стоил именно сыгранный ход.
     */
    private fun scoreLine(explanation: PositionExplanation, played: Section.PlayedMove?): List<String>? {
        val before = explanation.scoreFromWhite ?: return null
        if (played == null || played.centipawnLoss >= COLLAPSE_CP || before !is Score.Cp) {
            return listOf("Оценка ${ScoreFormat.display(before)}")
        }
        val shown = ScoreFormat.short(before)
        val move = SanRuFormatter.move(played.playedSan)
        if (played.matchesBest) {
            return listOf(
                "Оценка до хода: $shown",
                "Оценка после хода $move (это лучший ход): $shown, потеря 0.0",
            )
        }
        val sign = if (explanation.sideToMove == Side.WHITE) -1 else 1
        val after = Score.Cp(before.centipawns + sign * played.centipawnLoss)
        return listOf(
            "Оценка до хода: $shown",
            "Оценка после хода $move: ${ScoreFormat.short(after)}, " + lossPhrase(played.centipawnLoss),
            "Оценка после лучшего хода ${SanRuFormatter.move(played.bestSan)} была бы $shown, потеря 0.0",
        )
    }

    private fun summaryLine(section: Section.PlayedMove): String {
        if (section.matchesBest) return "Коротко: сыгран лучший ход."
        if (section.classification == MoveClassification.BEST) {
            return "Коротко: " + SanRuFormatter.move(section.playedSan) + " не уступает лучшему ходу " +
                SanRuFormatter.move(section.bestSan) + "."
        }
        return "Коротко: " + SanRuFormatter.move(section.playedSan) + " — " +
            classificationLabel(section.classification) + damageMark(section) +
            ", лучше было " + SanRuFormatter.move(section.bestSan) + "."
    }

    /**
     * Перелом: ход перевёл партию в другую зону оценки для того, кто его сделал,
     * например из равной позиции в заметно худшую. Пороги «??» этим не меняются.
     */
    private fun isTurningPoint(explanation: PositionExplanation, played: Section.PlayedMove): Boolean {
        val fromWhite = explanation.scoreFromWhite ?: return false
        if (played.matchesBest || played.centipawnLoss < TURNING_POINT_LOSS_CP) return false
        val fromMover = ScoreFormat.toWhitePerspective(fromWhite, explanation.sideToMove)
        val before = when (fromMover) {
            is Score.Cp -> fromMover.centipawns
            is Score.Mate -> if (fromMover.movesToMate > 0) MATE_ZONE_CP else -MATE_ZONE_CP
        }
        return scoreZone(before) != scoreZone(before - played.centipawnLoss)
    }

    private fun scoreZone(centipawns: Int): Int = when {
        centipawns > TURNING_POINT_LOSS_CP -> 1
        centipawns < -TURNING_POINT_LOSS_CP -> -1
        else -> 0
    }

    private const val TURNING_POINT_LOSS_CP = 100
    private const val MATE_ZONE_CP = 10_000

    // ——— Группы: до хода, ход, после хода ———

    /** Метка заголовка группы: экраны выделяют такие строки. */
    const val GROUP_MARK = "━━"

    /**
     * Разбор читается как рассказ тренера, подсевшего к доске: что было до хода,
     * что сыграно и что надо было, что стало после. План соперника идёт раньше
     * своего: сначала понять, что он задумал.
     */
    private fun readingOrder(explanation: PositionExplanation, viewer: Side?): List<Section> {
        val sections = explanation.sections
        val opponentPlanFirst = viewer != null && viewer != explanation.sideToMove
        if (!opponentPlanFirst) return sections
        val myPosition = sections.indexOfFirst { it is Section.MyPosition }
        val myAttack = sections.indexOfFirst { it is Section.MyAttack }
        if (myPosition < 0 || myAttack < 0) return sections
        return sections.toMutableList().also {
            it[myPosition] = sections[myAttack]
            it[myAttack] = sections[myPosition]
        }
    }

    private fun groupOf(section: Section, explanation: PositionExplanation, viewer: Side?): String {
        val played = explanation.playedMoveSan != null
        return when (section) {
            is Section.EvalReason, is Section.PositionAssessment, is Section.MyPosition, is Section.MyAttack ->
                if (played) "До хода" else "Позиция"
            is Section.AfterMove -> "После хода"
            else -> when {
                !played -> "Что играть"
                viewer == null -> "Ход " + SanRuFormatter.move(explanation.playedMoveSan ?: "")
                viewer == explanation.sideToMove -> "Ваш ход " + SanRuFormatter.move(explanation.playedMoveSan ?: "")
                else -> "Ход соперника " + SanRuFormatter.move(explanation.playedMoveSan ?: "")
            }
        }
    }

    // ——— Заголовки и тела разделов ———

    private fun titleOf(section: Section, explanation: PositionExplanation, viewer: Side?): String = when (section) {
        is Section.EvalReason -> "Почему такая оценка"
        is Section.PositionAssessment -> "Позиция"
        // Планы: МОЯ ПОЗИЦИЯ — это план соперника стороны, чей ход, МОЯ АТАКА — её собственный.
        is Section.MyPosition -> planTitle(explanation.sideToMove.flip(), viewer)
        is Section.MyAttack -> planTitle(explanation.sideToMove, viewer)
        is Section.BestMoves -> "Лучший ход"
        is Section.WhyThisMove ->
            "Почему ${SanRuFormatter.move(section.moveSan)}${playedMatchMark(explanation)}"
        is Section.PlayedMove ->
            "Сыгранный ход: ${SanRuFormatter.move(section.playedSan)} — " +
                classificationLabel(section.classification) + damageMark(section)
        is Section.Prophylaxis -> "Профилактика"
        is Section.Trap -> "Если сыграть иначе"
        is Section.Alternatives -> "Другие ходы"
        is Section.AfterMove -> "После хода"
    }

    private fun planTitle(owner: Side, viewer: Side?): String = when (viewer) {
        null -> "План " + SideLexicon.name(owner, GrammaticalCase.GENITIVE)
        owner -> "Мой план"
        else -> "План соперника"
    }

    /**
     * Совпадение с сыгранным ходом отмечается прямо у лучшего хода.
     *
     * Отдельный раздел "СЫГРАННЫЙ ХОД: Nc3 — лучший" повторял бы тот же ход
     * заголовком ниже, и читателю пришлось бы сличать две строки, чтобы понять,
     * что речь об одном и том же.
     */
    private fun playedMatchMark(explanation: PositionExplanation): String =
        if (explanation.section<Section.PlayedMove>()?.matchesBest == true) " (= сыгранный)" else ""

    /**
     * Чем именно плох ход — короткой вставкой прямо в заголовок.
     *
     * Ярлык "зевок" сам по себе ничего не объясняет: он сообщает оценку, а не
     * причину. Причина берётся из фактов, появившихся у соперника после этого
     * хода, и только если их нет — из падения оценки, которое известно всегда.
     */
    private fun damageMark(section: Section.PlayedMove): String {
        if (section.classification != MoveClassification.BLUNDER &&
            section.classification != MoveClassification.MISTAKE
        ) {
            return ""
        }
        val phrase = section.allowedMate?.let(::allowedMateMark) ?: section.punishment
            .sortedBy(::damageRank)
            .firstNotNullOfOrNull { runCatching { damagePhrase(it) }.getOrNull() }
            ?: lossPhrase(section.centipawnLoss)
        return " ($phrase)"
    }

    private fun classificationLabel(classification: MoveClassification): String = when (classification) {
        MoveClassification.BEST -> "лучший"
        MoveClassification.GOOD -> "хорошо"
        MoveClassification.INACCURACY -> "неточность"
        MoveClassification.MISTAKE -> "ошибка"
        MoveClassification.BLUNDER -> "зевок"
    }

    /** Раздел без фактов не печатается вовсе — ни заголовка, ни фразы-заполнителя. */
    private fun renderSection(
        section: Section,
        explanation: PositionExplanation,
        pool: TemplatePool,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
        viewer: Side?,
    ): String? = when (section) {
        is Section.EvalReason -> bulleted(evalReasonBody(section, glossary))
        is Section.PositionAssessment -> positionAssessmentBody(section, explanation, glossary)
        is Section.MyPosition -> myPositionBody(section, explanation, pool, glossary, memory, forkedSquares, viewer)
        is Section.MyAttack -> myAttackBody(section, explanation, glossary, memory, forkedSquares, viewer)
        is Section.BestMoves -> bestMovesBody(section, explanation, glossary, memory, forkedSquares)
        is Section.WhyThisMove -> whyThisMoveBody(section, explanation, glossary, memory, forkedSquares)
        is Section.PlayedMove -> playedMoveBody(section, explanation)
        is Section.Prophylaxis -> BULLET + prophylaxisBody(section.fact, pool)
        is Section.Trap -> BULLET + trapBody(section, explanation.sideToMove)
        is Section.Alternatives -> alternativesBody(section)
        is Section.AfterMove -> afterMoveBody(section, glossary, explanation)
    }

    /** "Оценка после хода Bxg5: −4.5." и почему — тем же разбором варианта, что и до хода. */
    private fun afterMoveBody(section: Section.AfterMove, glossary: Glossary, explanation: PositionExplanation): String? {
        val score = section.scoreFromWhite ?: return null
        val head = "Оценка после хода " + SanRuFormatter.move(section.moveSan) + ": " + ScoreFormat.short(score) + "."
        val mate = explanation.section<Section.PlayedMove>()?.allowedMate?.takeIf { it.movesToMate == 1 }
            ?.let { allowedMateSentence(it, section.moveSan, explanation.sideToMove) }
        val reason = section.reason?.let { evalReasonBody(it, glossary, withMaterial = true) }.orEmpty()
        return bulleted(listOf(head) + listOfNotNull(mate) + reason)
    }

    // ——— Пункты ———

    /**
     * Пункт разбора и вложенные в него. [weight] — насколько пункт важен для
     * читателя: соседи сортируются по нему, самое опасное и самое выгодное выше.
     */
    private data class Item(val text: String, val children: List<Item> = emptyList(), val weight: Int = 0)

    private fun bulletOf(level: Int): String = when (level) {
        0 -> BULLET
        1 -> SUB_BULLET
        else -> SUB_SUB_BULLET
    }

    private fun itemLines(items: List<Item>, level: Int = 0): List<String> =
        items.flatMap { listOf(bulletOf(level) + it.text) + itemLines(it.children, level + 1) }

    private fun bulleted(lines: List<String>): String =
        lines.filter { it.isNotBlank() }.joinToString(System.lineSeparator()) { BULLET + it }

    // ——— ПОЧЕМУ ТАКАЯ ОЦЕНКА ———

    /**
     * Отвечает своими фразами и своей памятью: факт, названный здесь, должен
     * прозвучать и в разделе ниже, а этот блок читают отдельно.
     */
    private fun evalReasonBody(section: Section.EvalReason, glossary: Glossary, withMaterial: Boolean = true): List<String> =
        evalReasonParts(section, glossary)
            .let { parts -> if (withMaterial) parts else parts.drop(1) }
            .filter { it.isNotEmpty() }

    /** Первая часть — материал сейчас: в блоке "до хода" его уже назвала строка выше. */
    private fun evalReasonParts(section: Section.EvalReason, glossary: Glossary): List<String> {
        val leader = section.leader
        val line = { san: List<String> -> san.joinToString(" ") { SanRuFormatter.move(it) } }

        if (section.mateIn != null && leader != null) {
            val pattern = when (section.matePattern) {
                MatePattern.SMOTHERED -> " Рисунок: спёртый мат, королю мешают свои же фигуры."
                MatePattern.BACK_RANK -> " Рисунок: линейный мат по последней горизонтали."
                null -> ""
            }
            // Пустая первая часть — на месте материала: при мате о нём не говорим.
            return listOf(
                "",
                capitalize(SideLexicon.name(leader)) + " ставят мат в " + section.mateIn + " " +
                    movesWord(section.mateIn) + ": " + line(section.lineSan) + "." + pattern,
            )
        }

        val parts = mutableListOf(materialNowSentence(section.materialNow))
        if (leader == null) {
            parts += "Перевеса нет: движок не видит ни выигрыша материала, ни решающего удара."
            return parts
        }

        val gain = materialGainWord(section.materialSwing ?: 0)
        if (gain != null) {
            parts += "В варианте " + line(section.swingLineSan) + " " + SideLexicon.name(leader) +
                " выигрывают " + gain + "."
        }
        section.tactic?.let { tactic ->
            val statement = statementsFor(listOf(tactic), emptySet(), glossary, RenderMemory()).firstOrNull()
            if (statement != null) {
                parts += "Первый удар, после " + line(section.tacticLineSan) + ": " + decapitalize(statement)
            }
        }
        // Лишний сейчас конь может быть уже под боем: тогда перевес держится не на нём.
        val lineMaterial = section.materialAfterLine
        val returned = gain == null && lineMaterial != null &&
            kotlin.math.abs(lineMaterial - section.materialNow) >= MATERIAL_EXPLAINS_CP
        if (returned && lineMaterial != null) {
            parts += "В варианте " + line(section.swingLineSan) + " " + materialChangePhrase(lineMaterial)
        } else if (parts.size > 1) {
            return parts
        }

        val materialBase = if (returned && lineMaterial != null) lineMaterial else section.materialNow
        val materialAhead = if (leader == Side.WHITE) materialBase else -materialBase
        if (materialAhead >= MATERIAL_EXPLAINS_CP) {
            parts += "Перевес держится на этом материале."
            return parts
        }

        val positional = statementsFor(section.positional, emptySet(), glossary, RenderMemory(), MAX_FACTS_PER_REASON)
        parts += if (positional.isEmpty()) {
            "Перевес у " + SideLexicon.name(leader, GrammaticalCase.GENITIVE) +
                " позиционный, движок видит его в варианте " + line(section.lineSan) + "."
        } else {
            "Перевес у " + SideLexicon.name(leader, GrammaticalCase.GENITIVE) + " позиционный. " +
                positional.joinToString(" ")
        }
        return parts
    }

    private fun materialChangePhrase(material: Int): String = when {
        kotlin.math.abs(material) < MATERIAL_EXPLAINS_CP -> "материал выравнивается."
        material > 0 -> "материала больше у белых (" + pawnsLabel(material) + ")."
        else -> "материала больше у чёрных (" + pawnsLabel(material) + ")."
    }

    private fun materialNowSentence(material: Int): String = when {
        kotlin.math.abs(material) < MATERIAL_EXPLAINS_CP -> "Материал равный."
        material > 0 -> "Материал: у белых больше (" + pawnsLabel(material) + ")."
        else -> "Материал: у чёрных больше (" + pawnsLabel(material) + ")."
    }

    /** Что выигрывается к спокойной позиции варианта. Null — материал по сути не меняется. */
    private fun materialGainWord(swing: Int): String? = when {
        swing >= SeeCalculator.QUEEN_VALUE - GAIN_MARGIN_CP -> "ферзя"
        swing >= SeeCalculator.ROOK_VALUE - GAIN_MARGIN_CP -> "ладью"
        swing >= SeeCalculator.KNIGHT_VALUE - GAIN_MARGIN_CP -> "фигуру"
        // Не "качество": так же выглядит и слон за две пешки, а качество — это ладья за лёгкую фигуру.
        swing >= EXCHANGE_MIN_CP -> "материал, около двух пешек"
        swing >= SeeCalculator.PAWN_VALUE - GAIN_MARGIN_CP / 2 -> "пешку"
        else -> null
    }

    private const val MATERIAL_EXPLAINS_CP = 80
    private const val GAIN_MARGIN_CP = 50
    private const val EXCHANGE_MIN_CP = 150

    // ——— ОЦЕНКА ПОЗИЦИИ ———

    /**
     * Блок "до хода": дебют, материал, характер позиции, оценка и почему она
     * такая. Материал называется один раз — строкой с перечнем фигур.
     */
    private fun positionAssessmentBody(
        section: Section.PositionAssessment,
        explanation: PositionExplanation,
        glossary: Glossary,
    ): String {
        val items = mutableListOf<Item>()
        // Дебют первой строкой: он отвечает на вопрос "куда я вообще попал",
        // а материал и характер позиции — уже на "как тут дела".
        section.opening?.takeIf { it.isNotBlank() }?.let { items += Item("Дебют: $it.") }
        items += Item(materialAssessmentLine(section))
        characterLine(section.character)?.let { items += Item(it) }
        val score = explanation.scoreFromWhite
        if (score != null) {
            val label = if (explanation.playedMoveSan != null) "Оценка до хода: " else "Оценка: "
            val why = explanation.section<Section.EvalReason>()?.let { evalReasonBody(it, glossary, withMaterial = false) }.orEmpty()
            items += Item(label + ScoreFormat.short(score) + ".", why.map { Item(it) })
        }
        return itemLines(items).joinToString(System.lineSeparator())
    }

    /**
     * Строка про материал есть всегда, в том числе при равенстве.
     *
     * "Материал равный" — это не пустая фраза, а ответ на вопрос, который
     * читатель задаёт первым. Молчание он прочтёт как недосмотр, а не как
     * "считать нечего".
     *
     * Словесная оценка ("заметный перевес") тут больше не нужна и даже вредна:
     * она уже однажды разошлась с доской, объявив заметным перевесом лишнюю
     * пешку. Перечень фигур и число в пешках проверяются глазами, а
     * "заметный" — нет.
     */
    private fun materialAssessmentLine(section: Section.PositionAssessment): String {
        val score = pawnsLabel(section.materialCentipawns)
        val parts = listOfNotNull(
            surplusPhrase(section.pieceDifference, Side.WHITE),
            surplusPhrase(section.pieceDifference, Side.BLACK),
        )

        if (parts.isEmpty()) {
            // Перечня нет, а число есть — так бывает только если доску не
            // удалось прочитать. Называть это равенством нельзя: слова
            // противоречили бы числу в той же строке.
            if (kotlin.math.abs(section.materialCentipawns) >= CoachThresholds.MATERIAL_NOTE_CP) {
                val side = if (section.materialCentipawns > 0) Side.WHITE else Side.BLACK
                return "Материал: перевес у " + SideLexicon.name(side, GrammaticalCase.GENITIVE) + " ($score)."
            }
            return "Материал: равный ($score)."
        }
        return "Материал: " + parts.joinToString(", ") + " ($score)."
    }

    /**
     * Баланс в пешках со знаком: плюс — за белых, минус — за чёрных.
     *
     * Locale.ROOT обязателен: на русском устройстве формат по умолчанию даёт
     * запятую, и "+1,0" рядом с оценкой "+1.0" в шапке выглядит как две разные
     * величины.
     */
    private fun pawnsLabel(centipawnsFromWhite: Int): String {
        if (centipawnsFromWhite == 0) return "0.0"
        return String.format(Locale.ROOT, "%+.1f", centipawnsFromWhite / 100.0)
    }

    /** "у белых лишние ладья и две пешки" — либо null, если у этой стороны лишнего нет. */
    private fun surplusPhrase(difference: Map<PieceType, Int>, side: Side): String? {
        val sign = if (side == Side.WHITE) 1 else -1
        // Порядок от старшей фигуры к младшей: сначала важное.
        val items = MATERIAL_ORDER
            .mapNotNull { type ->
                val count = (difference[type] ?: 0) * sign
                if (count > 0) type to count else null
            }
        if (items.isEmpty()) return null

        // Множественное число решает ОБЩЕЕ количество лишних фигур, а не
        // количество первой из них: "лишняя ладья и пешка" — это две фигуры,
        // и определение при них обязано быть во множественном числе.
        val total = items.sumOf { (_, count) -> count }
        val spare = PieceLexicon.spare(items.first().first, total)
        val listed = items.joinToString(" и ") { (type, count) -> PieceLexicon.counted(type, count) }
        return "у " + SideLexicon.name(side, GrammaticalCase.GENITIVE) + " " + spare + " " + listed
    }

    /** "Полуоткрытая" ничего не подсказывает, поэтому строка только для двух явных случаев. */
    private fun characterLine(character: PositionCharacter): String? = when (character) {
        PositionCharacter.OPEN -> "Позиция открытая: фигурам есть где развернуться."
        PositionCharacter.CLOSED -> "Позиция закрытая: пешечные цепи сцеплены, играть приходится вокруг них."
        PositionCharacter.SEMI_OPEN -> null
    }

    private fun ideasLine(ideas: List<StrategicIdea>, plan: DevelopmentPlan?): String? {
        val phrases = plan?.let(::developmentPhrases).orEmpty() + ideas.map { ideaPhrase(it) }
        if (phrases.isEmpty()) return null
        return "Идея: " + phrases.joinToString(", ") + "."
    }

    /** Порядок как у дебютного правила: центр, развитие, рокировка. Фигуры называются поимённо. */
    private fun developmentPhrases(plan: DevelopmentPlan): List<String> = buildList {
        if (plan.needsCenter) add("занять центр пешкой")
        if (plan.undeveloped.isNotEmpty()) {
            add("развить " + russianList(plan.undeveloped.map { namedSquare(plan.evidence, it, GrammaticalCase.ACCUSATIVE) }))
        }
        if (plan.castlingPending) add("сделать рокировку")
    }

    private fun ideaPhrase(idea: StrategicIdea): String = when (idea) {
        StrategicIdea.ATTACK_KING -> "атаковать короля"
        StrategicIdea.FORCE_EXCHANGES -> "форсировать размены"
        // Не "укрепить оборону короля": пользователь справедливо спросил, что
        // это значит. Идея должна называть действие, которое можно сделать
        // следующим ходом, а не состояние, к которому надо стремиться.
        StrategicIdea.DEFEND -> "подтянуть фигуры к своему королю"
        StrategicIdea.IMPROVE_PIECES -> "улучшать положение фигур"
        StrategicIdea.PUSH_PAWNS -> "двигать проходные пешки"
        StrategicIdea.SIMPLIFY_TO_ENDGAME -> "упрощать позицию и идти в эндшпиль"
        StrategicIdea.OPEN_THE_POSITION -> "вскрывать позицию"
        StrategicIdea.KEEP_IT_CLOSED -> "держать позицию закрытой"
    }

    // ——— ПЛАН СОПЕРНИКА / МОЙ ПЛАН ———

    /**
     * Один порядок проверки на обоих планах: шахи и маты, взятия, активные ходы.
     *
     * Все пункты печатаются всегда, даже пустые. "Шахи и маты: нет" — это ответ, а
     * пропущенная строка выглядит так, будто тренер про мат забыл.
     *
     * Угроза встаёт в тот пункт, к которому относится ход соперника, а король и
     * план соперника идут в активные ходы.
     */
    private fun myPositionBody(
        section: Section.MyPosition,
        explanation: PositionExplanation,
        pool: TemplatePool,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
        viewer: Side?,
    ): String {
        val threat = section.threat
        val threatSentences = threatSentences(threat, pool, glossary, memory, forkedSquares)
        val threatIsMate = threat != null && threatMates(threat)
        val threatIsCapture = !threatIsMate && threat?.opponentMoveSan?.contains('x') == true
        val mover = explanation.sideToMove
        val threatItem = threatSentences.firstOrNull()?.let { intro ->
            Item(intro, threatSentences.drop(1).map { Item(it) }, weight = THREAT_WEIGHT)
        }

        val (backRank, loose) = section.loosePieces.partition { it is BackRankFact }
        // Угроза-взятие встаёт под ту фигуру, которую она берёт: "ферзь под боем" и
        // "без ответа белые играют Nxd6" — один пункт, а не два в разных местах.
        val looseItems = factItems(loose, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON, viewer)
        val target = threat?.opponentMoveUci?.drop(2)?.take(2)
        val targetIndex = looseItems.indexOfFirst { (fact, _) -> fact is HangingPieceFact && sq(fact.square) == target }
        val captures = looseItems.mapIndexed { index, (_, item) ->
            if (index == targetIndex && threatItem != null && threatIsCapture) {
                item.copy(children = item.children + listOf(Item(threatItem.text)) + threatItem.children)
            } else {
                item
            }
        } + listOfNotNull(threatItem.takeIf { threatIsCapture && targetIndex < 0 })

        val previous = section.previousMove?.let { previousMoveItem(it, forkedSquares, glossary) }
        // Взятие соперника заодно с ударом: "Bxc2 заодно нападает на ладью d1".
        val flipped = org.chesscoach.coach.engine.NullMoveUtil.flipSideToMove(explanation.fen)
        val capturesWithBlows = captures.mapIndexed { index, item ->
            val fact = looseItems.getOrNull(index)?.first as? HangingPieceFact
            val from = fact?.firstAttacker
            if (flipped == null || fact == null || from == null) return@mapIndexed item
            val uci = from.value().lowercase() + fact.square.value().lowercase()
            val blows = MoveEffects.attacks(flipped, uci)
            if (blows.isEmpty()) {
                item
            } else {
                val san = MoveNotation.uciToSanOrRaw(flipped, listOf(uci)).first()
                item.copy(children = listOf(Item("Взятие " + SanRuFormatter.move(san) + " заодно " + blows.joinToString(" и ") + ".")) + item.children)
            }
        }
        val checks = if (section.probedChecks.isNotEmpty()) {
            probedCheckItems(section.probedChecks, mover.flip(), withScore = false)
        } else {
            items(checksSentences(section.checks))
        }
        return itemLines(listOfNotNull(previous)).plus(
            checkList(
                mates = items(mateSentences(section.mates)) +
                    items(statementsFor(backRank, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON)) +
                    listOfNotNull(threatItem.takeIf { threatIsMate && section.mates.isEmpty() }) +
                    section.mateGuards.filter { guard -> section.mates.none { it == guard.mateSan } }.map { Item(guardSentence(it, mine = true)) } +
                    checks,
                captures = capturesWithBlows,
                active = listOfNotNull(threatItem.takeUnless { threatIsMate || threatIsCapture }) +
                    adviceItems(section.advice, glossary, memory, forkedSquares, best = null, mover = mover.flip()) +
                    items(statementsFor(section.kingSafety, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON)) +
                    items(planSentences(section.opponentPlan, glossary, forkedSquares, targetSide = mover)),
                exchanges = exchangeItems(section.exchanges, forkedSquares, glossary, memory),
            ),
        ).plus(ideaLine(explanation.section<Section.PositionAssessment>()?.opponentDevelopmentPlan, emptyList()))
            .joinToString(System.lineSeparator())
    }

    /** Угроза соперника — самое срочное в его плане: она стоит первой в своём пункте. */
    private const val THREAT_WEIGHT = 1_000_000

    private fun items(sentences: List<String>): List<Item> = sentences.map { Item(it) }

    /**
     * Факты пунктами, с ценой для читателя: "(-6 для вас)". Сначала самые
     * дорогие: лимит пункта не должен срезать ферзя ради пешки.
     */
    private fun factItems(
        facts: List<Fact>,
        forkedSquares: Set<Square>,
        glossary: Glossary,
        memory: RenderMemory,
        limit: Int,
        viewer: Side?,
    ): List<Pair<Fact, Item>> = dedupeFacts(facts.sortedByDescending(::factWeight), forkedSquares, glossary)
        .mapNotNull { fact ->
            val text = runCatching { renderFactStatement(fact, glossary) }.getOrNull()
                ?.takeIf { it.isNotBlank() && memory.claim(it) }
                ?: return@mapNotNull null
            val tag = (fact as? HangingPieceFact)?.let { materialTag(it.lossCentipawns, it.favours, viewer) }
            fact to Item(text + tag?.let { " ($it)" }.orEmpty(), weight = factWeight(fact))
        }
        .take(limit)

    /**
     * Сколько стоит факт тому, кому он на пользу: висящая фигура — её цену.
     * Вилка берёт младшую из двух целей, а вилка с шахом — старшую из остальных.
     */
    private fun factWeight(fact: Fact): Int = when (fact) {
        is HangingPieceFact -> fact.lossCentipawns
        is ForkFact -> {
            val types = fact.targets.mapNotNull { fact.evidence.pieceAt(it)?.pieceType }
            val values = types.filter { it != PieceType.KING }.map(SeeCalculator::value).sortedDescending()
            if (PieceType.KING in types) values.firstOrNull() ?: 0 else values.getOrNull(1) ?: 0
        }
        else -> 0
    }

    /** "+2 для вас", "-6 для вас"; без читателя — "+2 для белых". Null — меньше пешки. */
    private fun materialTag(centipawns: Int, owner: Side, viewer: Side?): String? {
        val pawns = kotlin.math.round(centipawns / 100.0).toInt()
        if (pawns == 0) return null
        return when (viewer) {
            null -> "+$pawns для " + SideLexicon.name(owner, GrammaticalCase.GENITIVE)
            owner -> "+$pawns для вас"
            else -> "-$pawns для вас"
        }
    }

    /** Размены — справка: сначала тот, что выгоднее стороне плана. */
    private fun exchangeItems(
        exchanges: List<ExchangeFact>,
        forkedSquares: Set<Square>,
        glossary: Glossary,
        memory: RenderMemory,
    ): List<Item> = items(statementsFor(exchanges.sortedByDescending { it.deltaCentipawns }, forkedSquares, glossary, memory, MAX_EXCHANGES))

    /**
     * "Прошлый ход 6... g5: нападает на слона h4." Свою [RenderMemory] не берём:
     * тот же удар потом стоит пунктом плана, и вычёркивать его там нельзя.
     * Несколько целей — вложенными пунктами, самая дорогая первой.
     */
    private fun previousMoveItem(previous: PreviousMove, forkedSquares: Set<Square>, glossary: Glossary): Item {
        val parts = mutableListOf<String>()
        previous.captured?.let { parts += "взят" + takenEnding(it) + " " + PieceLexicon.name(it, GrammaticalCase.NOMINATIVE) }
        if (previous.check) parts += "шах"
        // Коротко, что ход сделал: подробности тот же факт получает ниже, в пунктах плана.
        val ranked = previous.facts.sortedWith(compareBy<Fact>(::damageRank).thenByDescending(::factWeight))
        val short = ranked.mapNotNull(::previousPhrase).distinct().take(MAX_FACTS_PER_REASON)
        parts += short.ifEmpty {
            statementsFor(ranked, forkedSquares, glossary, RenderMemory(), MAX_FACTS_PER_REASON)
                .map { decapitalize(it.removeSuffix(".")) }
        }
        val head = "Прошлый ход " + previous.moveNumberLabel + " " + SanRuFormatter.move(previous.san) + ":"
        return when {
            parts.isEmpty() -> Item("$head новых ударов и угроз не создаёт.")
            parts.size == 1 -> Item(head + " " + parts.first() + ".")
            else -> Item(head, parts.map { Item("$it.") })
        }
    }

    /** Что сделал прошлый ход — глаголом: "нападает на слона h4". Null — короткой формы нет. */
    private fun previousPhrase(fact: Fact): String? = when (fact) {
        is HangingPieceFact -> "нападает на " + namedSquare(fact.evidence, fact.square, GrammaticalCase.ACCUSATIVE)
        is ForkFact -> "ставит вилку на " + russianList(fact.targets.map { sq(it) })
        is PinFact -> "связывает " + namedSquare(fact.evidence, fact.pinned, GrammaticalCase.ACCUSATIVE)
        is SkewerFact -> "ставит рентген на " + sq(fact.front)
        is DiscoveredAttackFact -> "готовит вскрытое нападение на " + sq(fact.target)
        is MateFact -> "грозит матом"
        is BackRankFact -> "грозит матом по последней горизонтали"
        is TrappedPieceFact -> "запирает " + namedSquare(fact.evidence, fact.square, GrammaticalCase.ACCUSATIVE)
        else -> null
    }

    /** "взят конь", "взята пешка": причастие по роду фигуры. */
    private fun takenEnding(type: PieceType): String =
        if (PieceLexicon.genderOf(type) == Gender.MASCULINE) "" else "а"

    private fun myAttackBody(
        section: Section.MyAttack,
        explanation: PositionExplanation,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
        viewer: Side?,
    ): String {
        val assessment = explanation.section<Section.PositionAssessment>()
        val (engineMates, mateFacts) = section.mate.partition { it is MateFact }
        val mover = explanation.sideToMove
        val captures = factItems(section.captures, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON, viewer)
            .map { (fact, item) -> checkedCapture(fact, item, section.captureChecks, mover) }
        val checkers = checkers(explanation.fen)
        val given = checkers.takeIf { it.isNotEmpty() }?.let {
            val whom = if (viewer == mover) "Вам" else capitalize(SideLexicon.name(mover, GrammaticalCase.DATIVE))
            Item("$whom шах " + russianList(it.map { (square, type) -> PieceLexicon.name(type, GrammaticalCase.INSTRUMENTAL) + " " + sq(square) }) +
                ": сначала ответить на него — закрыться, взять шахующую фигуру или уйти королём.")
        }
        val checks = if (section.probedChecks.isNotEmpty()) {
            probedCheckItems(section.probedChecks, mover, withScore = true)
        } else {
            items(checksSentences(section.checks))
        }
        val shownCaptures = section.captureChecks.map { it.uci }.toSet()
        val extraCaptures = section.probedCaptures.filter { it.uci !in shownCaptures }.map { probed ->
            val victim = runCatching {
                Board().also { it.loadFromFen(probed.fenBefore) }.getPiece(Square.valueOf(probed.uci.substring(2, 4).uppercase())).pieceType
            }.getOrNull()
            val what = victim?.let { " — берёт " + PieceLexicon.name(it, GrammaticalCase.ACCUSATIVE) + " " + probed.uci.substring(2, 4) }.orEmpty()
            Item(
                SanRuFormatter.move(probed.san) + what + probed.scoreFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty() + ".",
                weight = victim?.let(SeeCalculator::value) ?: 0,
            )
        }
        return checkList(
            mates = listOfNotNull(given) + items(mateSentences(section.mates)) +
                items(engineMates.filterIsInstance<MateFact>().map { "Есть форсированный мат в ${it.movesToMate} ${movesWord(it.movesToMate)}." }) +
                items(statementsFor(mateFacts, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON)) +
                section.mateGuards.filter { guard -> section.mates.none { it == guard.mateSan } }.map { Item(guardSentence(it, mine = false)) } +
                checks,
            captures = captures + extraCaptures,
            active = listOfNotNull(section.mateIdea?.let { mateIdeaItem(it, mover) }) +
                adviceItems(section.advice, glossary, memory, forkedSquares, explanation.scoreFromWhite, mover) +
                items(statementsFor(section.activity, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON)) +
                items(planSentences(section.plan, glossary, forkedSquares, targetSide = explanation.sideToMove.flip())),
            exchanges = exchangeItems(section.exchanges, forkedSquares, glossary, memory),
            counter = counterItems(section, mover),
        ).plus(myIdeaLine(assessment?.developmentPlan, assessment?.ideas.orEmpty(), checkers.isNotEmpty(), section.mateIdea))
            .joinToString(System.lineSeparator())
    }

    /**
     * "Мат в два хода: Bh2, затем Qxg1#." и можно ли его сыграть: под шахом
     * сначала ответ на шах, а после него идея может пропасть; без шаха —
     * что ответит соперник на подготовку.
     */
    private fun mateIdeaItem(idea: org.chesscoach.coach.model.MateIdea, mover: Side): Item {
        val head = "Мат в два хода: " + SanRuFormatter.move(idea.prepSan) + ", затем " + SanRuFormatter.move(idea.mateSan) +
            ", если соперник не помешает."
        val opponent = SideLexicon.name(mover.flip())
        val note = when {
            idea.inCheck && idea.afterLineSan.isEmpty() -> "Сейчас не сыграть: сначала надо ответить на шах."
            idea.inCheck && idea.survives -> "Сейчас шах, но после " + SanRuFormatter.line(idea.afterLineSan) + " идея остаётся."
            idea.inCheck -> "Сейчас не сыграть: сначала надо ответить на шах, а после " +
                SanRuFormatter.line(idea.afterLineSan) + " мата уже нет."
            idea.lossVsBestCentipawns >= CoachThresholds.ADVICE_MAX_LOSS_CP && idea.replySan != null ->
                "Но " + opponent + " успевают защититься: после " + SanRuFormatter.move(idea.prepSan) + " " +
                    SanRuFormatter.move(idea.replySan) + idea.scoreFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty() + "."
            else -> idea.scoreFromWhite?.let { "Оценка после " + SanRuFormatter.move(idea.prepSan) + ": " + ScoreFormat.short(it) + "." }
        }
        val playable = !idea.inCheck && idea.lossVsBestCentipawns < CoachThresholds.ADVICE_MAX_LOSS_CP
        return Item(head, listOfNotNull(note).map { Item(it) }, weight = if (playable) MATE_IDEA_WEIGHT else -BAD_ADVICE_PENALTY / 2)
    }

    /** Матовая идея, которую можно сыграть, — первой в активных ходах. */
    private const val MATE_IDEA_WEIGHT = 500_000

    /**
     * "Идея" своего плана. Под шахом общая "подтянуть фигуры к королю" ничего не
     * говорит: сначала ответить на шах. Найденный мат называем поимённо.
     */
    private fun myIdeaLine(
        plan: DevelopmentPlan?,
        ideas: List<StrategicIdea>,
        inCheck: Boolean,
        mateIdea: org.chesscoach.coach.model.MateIdea?,
    ): List<String> {
        if (!inCheck && mateIdea == null) return ideaLine(plan, ideas)
        val phrases = listOfNotNull(
            "сначала ответить на шах".takeIf { inCheck },
            mateIdea?.takeIf { !it.inCheck || it.survives }?.let {
                "готовить мат " + SanRuFormatter.move(it.prepSan) + " и " + SanRuFormatter.move(it.mateSan)
            },
        ) + plan?.let(::developmentPhrases).orEmpty() +
            ideas.filterNot { inCheck && it == StrategicIdea.DEFEND }.map { ideaPhrase(it) }
        return listOf(BULLET + "Идея: " + phrases.joinToString(", ") + ".")
    }

    /**
     * Взятие, проверенное движком. Можно брать — рядом оценка. Нельзя — пункт
     * уходит вниз, и под ним сказано, чем ответит соперник: на доске фигура
     * висит, но у берущего самого висит что-то дороже.
     */
    private fun checkedCapture(fact: Fact, item: Item, checks: List<CaptureCheck>, mover: Side): Item {
        val check = (fact as? HangingPieceFact)?.let { hanging -> checks.firstOrNull { it.square == hanging.square } }
            ?: return item
        val score = check.scoreFromWhite?.let { "оценка " + ScoreFormat.short(it) }
        if (check.lossVsBestCentipawns < CoachThresholds.ADVICE_MAX_LOSS_CP) {
            val text = if (score == null) item.text else if (item.text.endsWith(")")) item.text.dropLast(1) + ", $score)" else item.text + " ($score)"
            return item.copy(text = text)
        }
        val reply = check.replySan ?: return item.copy(weight = item.weight - BAD_ADVICE_PENALTY)
        if (check.replyMates) {
            val mated = "Но после " + SanRuFormatter.move(check.moveSan) + " " + SideLexicon.name(mover.flip()) + " отвечают матом " + SanRuFormatter.move(reply) + "."
            return item.copy(children = item.children + Item(mated), weight = item.weight - BAD_ADVICE_PENALTY)
        }
        val takes = check.replyTakes?.let { " и берут " + PieceLexicon.name(it, GrammaticalCase.ACCUSATIVE) }.orEmpty()
        val but = "Но после " + SanRuFormatter.move(check.moveSan) + " " + SideLexicon.name(mover.flip()) + " отвечают " +
            SanRuFormatter.move(reply) + takes + score?.let { " ($it)" }.orEmpty() + "."
        return item.copy(children = item.children + Item(but), weight = item.weight - BAD_ADVICE_PENALTY)
    }

    /** Ход с "но" уходит в конец пункта: сначала то, что можно играть. */
    private const val BAD_ADVICE_PENALTY = 100_000

    /** "Идея" стоит в плане той стороны, чья она: в общей оценке позиции было непонятно, чья. */
    private fun ideaLine(plan: DevelopmentPlan?, ideas: List<StrategicIdea>): List<String> =
        listOfNotNull(ideasLine(ideas, plan)?.let { BULLET + it })

    private fun mateSentences(mates: List<String>): List<String> {
        if (mates.isEmpty()) return emptyList()
        return listOf("Мат ходом " + mates.joinToString(" или ") { SanRuFormatter.move(it) } + ".")
    }

    private fun checksSentences(checks: List<String>): List<String> {
        if (checks.isEmpty()) return emptyList()
        val word = if (checks.size > 1) "Шахи " else "Шах "
        return listOf(word + checks.joinToString(", ") { SanRuFormatter.move(it) } + ".")
    }

    /**
     * Удар следующим ходом уже проверен движком, поэтому у своих ударов есть
     * итоговая оценка. Удар, который уступает лучшему ходу ([best]), — с "но",
     * и он идёт последним.
     */
    private fun adviceItems(
        advice: List<CheckedAdvice>,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
        best: Score?,
        mover: Side,
    ): List<Item> = advice.mapNotNull { item ->
        val statement = statementsFor(listOf(item.fact), forkedSquares, glossary, memory).firstOrNull()
            ?: return@mapNotNull null
        val score = item.scoreFromWhite ?: return@mapNotNull Item(statement)
        val worse = best != null && ScoreFormat.centipawnLoss(
            ScoreFormat.toWhitePerspective(best, mover),
            ScoreFormat.toWhitePerspective(score, mover),
        ) >= CoachThresholds.ADVICE_MAX_LOSS_CP
        Item(
            statement.removeSuffix(".") + " (оценка " + ScoreFormat.short(score) + ").",
            weight = if (worse) -BAD_ADVICE_PENALTY else 0,
        )
    }

    /**
     * Пункты проверки всегда в одном порядке: шахи и маты, взятия, активные ходы.
     * Внутри пункта — по важности для читателя. Размены — справка внутри
     * взятий, а не пункт проверки: нет разменов, нет и строки.
     */
    private fun checkList(
        mates: List<Item>,
        captures: List<Item>,
        active: List<Item>,
        exchanges: List<Item>,
        counter: List<Item>? = null,
    ): List<String> = itemLines(
        listOfNotNull(
            checkItem("1. Шахи и маты", mates, emptyList()),
            checkItem("2. Взятия", captures.sortedByDescending { it.weight }, exchanges),
            checkItem("3. Активные ходы", active.sortedByDescending { it.weight }, emptyList()),
            counter?.let { checkItem("4. Противодействие плану соперника", it, emptyList()) },
        ),
    )

    /**
     * Шахи, проверенные движком: работающие с оценкой, проигрывающие — с "но" и
     * ответом соперника. У шахов соперника оценки нет: доска там с пропущенным ходом.
     */
    private fun probedCheckItems(checks: List<ProbedMove>, side: Side, withScore: Boolean): List<Item> = checks.map { check ->
        val score = check.scoreFromWhite?.takeIf { withScore }?.let { "оценка " + ScoreFormat.short(it) }
        if (check.works) {
            Item("Шах " + SanRuFormatter.move(check.san) + score?.let { " ($it)" }.orEmpty() + ".")
        } else {
            Item("Шах " + SanRuFormatter.move(check.san) + ", но " + replyPhrase(check, side) + score?.let { " ($it)" }.orEmpty() + ".", weight = -BAD_ADVICE_PENALTY)
        }
    }

    /** "слон f5 берёт ладью: Bxd7", "в ответ мат Qxh7#", "соперник отвечает Kh8". */
    private fun replyPhrase(move: ProbedMove, side: Side): String {
        val reply = move.replySan?.let { SanRuFormatter.move(it) } ?: return "это уступает лучшему ходу"
        if (move.replyMates) return "в ответ мат $reply"
        val piece = move.replyPiece
        val takes = move.replyTakes
        if (piece != null && takes != null) {
            val who = PieceLexicon.name(piece) + (move.replyFrom?.let { " " + sq(it) }.orEmpty())
            return who + " берёт " + PieceLexicon.name(takes, GrammaticalCase.ACCUSATIVE) + ": " + reply
        }
        return SideLexicon.name(side.flip()) + " отвечают " + reply
    }

    /** "Слон f4 — единственная защита h2: если он уйдёт или линию перекроют, Qxh2#." */
    private fun guardSentence(guard: MateGuard, mine: Boolean): String {
        val name = capitalize(PieceLexicon.name(guard.defenderPiece))
        val masculine = PieceLexicon.genderOf(guard.defenderPiece) == Gender.MASCULINE
        val mate = SanRuFormatter.move(guard.mateSan)
        return if (mine) {
            name + " " + sq(guard.defender) + " — единственная защита " + sq(guard.target) + ": если " +
                (if (masculine) "он уйдёт" else "она уйдёт") + " или линию перекроют, " + mate + "."
        } else {
            name + " " + sq(guard.defender) + " соперника — единственная защита " + sq(guard.target) + ": отвлечь " +
                (if (masculine) "его" else "её") + " или перекрыть линию — и " + mate + "."
        }
    }

    /**
     * Четвёртый пункт своего плана: ходы против главных угроз соперника с
     * оценкой и тем, что они делают. Null — сейчас шах, отвечать надо на него.
     */
    private fun counterItems(section: Section.MyAttack, mover: Side): List<Item> {
        if (section.counterThreats.isEmpty()) return emptyList()
        val against = "Против " + section.counterThreats.joinToString(" и ") { SanRuFormatter.move(it) }
        if (section.counterMoves.isEmpty()) return listOf(Item("$against хорошего ответа не нашлось."))
        return section.counterMoves.map { move ->
            val score = move.scoreFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty()
            val effects = MoveEffects.phrases(move.fenBefore, move.uci).takeIf { it.isNotEmpty() }
                ?.let { ": " + MoveEffects.list(it) }.orEmpty()
            val head = "Против " + SanRuFormatter.move(move.againstSan ?: section.counterThreats.first()) + " — " +
                SanRuFormatter.move(move.san) + score + effects + "."
            if (move.works) {
                Item(head)
            } else {
                Item(head, listOf(Item("Но это уступает лучшему ходу: " + lossPhrase(move.lossVsBestCentipawns) + ".")), weight = -BAD_ADVICE_PENALTY)
            }
        }
    }

    /**
     * "{Метка}: {пункт}" одной строкой, если пункт один, иначе метка и пункты
     * под ней. Пусто — "{Метка}: нет."
     *
     * Первая буква строчная: после двоеточия начинается не новое предложение, а
     * продолжение строки, и заглавная там читается как опечатка.
     */
    private fun checkItem(label: String, items: List<Item>, exchanges: List<Item>): Item {
        val reference = exchanges.takeIf { it.isNotEmpty() }?.let { Item("Размены (справочно):", it) }
        val single = items.singleOrNull()
        return when {
            items.isEmpty() -> Item("$label: нет.", listOfNotNull(reference))
            single != null && reference == null -> Item("$label: " + decapitalize(single.text), single.children)
            else -> Item("$label:", items + listOfNotNull(reference))
        }
    }

    /** Угроза живёт пунктом плана соперника: это ответ на вопрос "что мне грозит", а не отдельная новость. */
    private fun threatSentences(
        threat: ThreatFact?,
        pool: TemplatePool,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): List<String> {
        if (threat == null) return emptyList()

        val parts = mutableListOf(pool.choose(threat, threatIntroTemplates))
        // При мате о материале не говорим: "конь под боем" считалось бы на доске,
        // где партия уже кончилась или вот-вот кончится.
        if (threatMates(threat)) {
            parts += "Дело идёт к мату."
            return parts
        }

        // Не любой факт, который сработал после хода соперника, объясняет, чем
        // этот ход опасен: отсталая пешка или чужая подвижность тут ни при чём.
        // Опасность — это то, что реально сыпется: материал, король, мат.
        val dangers = threat.consequences.filter { explainsDanger(it) }
        val doubleAttack = threatDoubleAttack(threat, dangers, forkedSquares)
        if (doubleAttack.isNotEmpty()) parts += doubleAttackSentence(doubleAttack)

        // Детекторы смотрели на доску после хода соперника, поэтому их фразы
        // надо явно отнести к будущему: на текущей доске той пешки ещё нет.
        val statements = statementsFor(dangers - doubleAttack.toSet(), forkedSquares, glossary, memory, MAX_THREAT_CONSEQUENCES)
        if (statements.isNotEmpty()) {
            val lead = if (statements.size == 1) "После этого " else "После этого: "
            parts += lead + statements.joinToString("; ") { decapitalize(it.removeSuffix(".")) } + "."
        }

        if (threat.lineSan.size >= 2) parts += "Вариант: " + SanRuFormatter.line(threat.lineSan) + "."

        return parts
    }

    /** Строка движка считана за соперника, поэтому положительный счёт — мат ставит он. */
    private fun threatMates(threat: ThreatFact): Boolean =
        (threat.evidence.engineLine?.score as? Score.Mate)?.let { it.movesToMate > 0 } == true

    /** Фигуры под боем у той самой фигуры, что ходит угрозой: это двойной удар, а не отдельные потери. */
    private fun threatDoubleAttack(
        threat: ThreatFact,
        dangers: List<Fact>,
        forkedSquares: Set<Square>,
    ): List<HangingPieceFact> {
        val destination = threat.opponentMoveUci.drop(2).take(2).uppercase()
        val hit = dangers.filterIsInstance<HangingPieceFact>().filter {
            it.square !in forkedSquares && it.firstAttacker?.toString() == destination
        }
        return if (hit.size >= 2) hit else emptyList()
    }

    private fun doubleAttackSentence(hit: List<HangingPieceFact>): String {
        val attacker = hit.first().firstAttacker ?: return ""
        val piece = hit.first().evidence.pieceAt(attacker) ?: return ""
        val targets = russianList(hit.map { namedSquare(it.evidence, it.square, GrammaticalCase.ACCUSATIVE) })
        return "${capitalize(PieceLexicon.name(pieceTypeOf(piece)))} на ${sq(attacker)} нападёт сразу на $targets, все не спасти."
    }

    /**
     * План — итог уже сказанного, поэтому он единственный освобождён от запрета
     * на повтор: сводка обязана повторять то, что прозвучало выше, иначе она
     * пустеет ровно там, где полезна. Отсюда своя, чистая [RenderMemory].
     */
    private fun planSentences(
        steps: List<PlanStep>,
        glossary: Glossary,
        forkedSquares: Set<Square>,
        targetSide: Side,
    ): List<String> {
        val memory = RenderMemory()
        // Шаги с одной и той же идеей сливаем в одну фразу. Иначе план на три
        // слабые пешки выглядит как заевшая пластинка: "Давить на слабую пешку
        // соперника" трижды подряд, меняется только поле.
        return steps
            .groupBy { it.idea }
            .mapNotNull { (idea, group) ->
                planStepSentence(idea, group.flatMap { it.facts }, glossary, memory, forkedSquares, targetSide)
            }
    }

    // ——— УГРОЗА ———

    private val threatIntroTemplates: List<(ThreatFact) -> String> = listOf(
        { t -> "Если ничего не предпринять, ${SideLexicon.name(t.favours)} сыграют ${SanRuFormatter.move(t.opponentMoveSan)}." },
        { t -> "Стоит зазеваться — и ${SideLexicon.name(t.favours)} проведут ${SanRuFormatter.move(t.opponentMoveSan)}." },
        { t -> "Без ответа ${SideLexicon.name(t.favours)} тут же играют ${SanRuFormatter.move(t.opponentMoveSan)}." },
    )

    // ——— ЛУЧШИЙ ХОД ———

    private fun bestMovesBody(
        section: Section.BestMoves,
        explanation: PositionExplanation,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String? {
        if (section.computerLineSan.isEmpty()) return null
        val score = explanation.scoreFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty()
        val computer = Item("Компьютер: " + SanRuFormatter.line(section.computerLineSan) + score + ".")
        val human = section.human ?: return itemLines(listOf(computer)).joinToString(System.lineSeparator())
        val computerSan = SanRuFormatter.move(section.computerLineSan.first())
        if (human.sameAsComputer) {
            return itemLines(listOf(computer, Item("Человек: " + sameMovePhrase(human.kind, computerSan))))
                .joinToString(System.lineSeparator())
        }

        // Отставание в развитии и голый король — приметы позиции, а не то, что делает ход.
        val why = statementsFor(
            human.facts.filterNot { it is DevelopmentFact || it is KingSafetyFact },
            forkedSquares, glossary, memory, MAX_FACTS_PER_REASON,
        )
        // Ноль после округления — это не "слабее", а равноценный ход.
        val loss = if (human.lossVsBestCentipawns < LOSS_SHOWN_CP) {
            "равноценно лучшему"
        } else {
            "слабее лучшего, " + lossPhrase(human.lossVsBestCentipawns)
        }
        val humanSan = human.lineSan.firstOrNull()?.let { SanRuFormatter.move(it) }.orEmpty()
        // Смысл хода, как в "Почему" у лучшего: что он делает на доске.
        val meaning = human.lineUci.firstOrNull()?.let { MoveEffects.phrases(explanation.fen, it) }.orEmpty()
            .takeIf { it.isNotEmpty() }?.let { Item(humanSan + " — " + MoveEffects.list(it) + ".") }
        val humanItem = Item(
            "Человек: " + SanRuFormatter.line(human.lineSan) + " (" + loss + ").",
            listOfNotNull(meaning) + listOf(Item(humanDifference(human, humanSan, computerSan) + why.joinToString(" ", prefix = " ").trimEnd())),
        )
        return itemLines(listOf(computer, humanItem)).joinToString(System.lineSeparator())
    }

    /** "тот же Nxb5, его видно сразу — это взятие." Тихий ход объяснять нечем, кроме того, что он тот же. */
    private fun sameMovePhrase(kind: NaturalMove, san: String): String = when (kind) {
        NaturalMove.CAPTURE -> "тот же $san, его видно сразу — это взятие."
        NaturalMove.CHECK -> "тот же $san, его видно сразу — это шах."
        NaturalMove.DEVELOPMENT -> "тот же $san — фигура выходит в игру по дебютному правилу."
        NaturalMove.OTHER -> "тот же ход $san: заметнее хода здесь нет."
    }

    /** Почему человеку проще другой ход: он заметнее, а компьютерный держится на расчёте. */
    private fun humanDifference(human: HumanMove, humanSan: String, computerSan: String): String {
        val natural = when (human.kind) {
            NaturalMove.CAPTURE -> "$humanSan — взятие, его видно сразу"
            NaturalMove.CHECK -> "$humanSan — шах, его видно сразу"
            NaturalMove.DEVELOPMENT -> "$humanSan развивает фигуру по дебютному правилу"
            NaturalMove.OTHER -> "$humanSan проще найти"
        }
        val computer = if (human.computerQuiet) {
            "тихий $computerSan компьютер выбирает по расчёту на несколько ходов вперёд"
        } else {
            "$computerSan компьютер ставит выше по расчёту"
        }
        return "Разница: " + natural + ", а " + computer + "."
    }

    private fun whyThisMoveBody(
        section: Section.WhyThisMove,
        explanation: PositionExplanation,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String? {
        // Взятие — самая прямая причина хода, а детекторы смотрят только на то, что появилось после него.
        val captured = capturedPhrase(explanation)
            ?.takeIf { section.moveSan == explanation.bestMoveSan }
            ?.let { capitalize(it) + "." }
        val escape = escapePhrase(explanation)?.takeIf { section.moveSan == explanation.bestMoveSan && captured == null }
        val checkAnswer = checkAnswerPhrase(explanation)?.takeIf { section.moveSan == explanation.bestMoveSan }
        // Снятая угроза — первой: без неё ход теряет больше, чем выигрывает всё остальное.
        val threatMove = removedThreat(explanation)?.takeIf { section.moveSan == explanation.bestMoveSan }
        val (threatReasons, otherReasons) = section.reasons.partition { reason ->
            threatMove != null && reason.facts.any { it is ProphylaxisFact && it.preventedMoveSan == threatMove }
        }
        val lines = listOfNotNull(checkAnswer) +
            threatReasons.mapNotNull { reasonSentence(it, glossary, memory, forkedSquares) } +
            listOfNotNull(escape, captured) +
            otherReasons.mapNotNull { reasonSentence(it, glossary, memory, forkedSquares) }
        if (lines.isEmpty() && !section.pointIsInTheLine) return null

        val numbered = lines.map { BULLET + it }

        // Признаки молчат, а ход всё равно заметно сильнее остальных. Выдавать
        // в таком случае открытую линию за причину отдать ладью — значит
        // соврать уверенным тоном, поэтому отсылаем к варианту.
        val caveat = if (section.pointIsInTheLine && captured == null) {
            listOf("Одними признаками позиции этот ход не объясняется — его смысл раскрывается в варианте ниже.")
        } else {
            emptyList()
        }

        val opening = section.openingCaveat
            ?.let { openingCaveatLines(it, section.reasons) }
            ?: emptyList()

        return (numbered + (caveat + opening).map { BULLET + it }).joinToString(System.lineSeparator())
    }

    /**
     * Оговорка про дебютное правило развития.
     *
     * Клубного игрока учат «каждой фигурой по разу, потом рокировка», и совет
     * ходить той же фигурой второй раз выглядит как противоречие всему, что он
     * знает. Непонятая рекомендация не выполняется, поэтому молчать тут нельзя —
     * но и оправдывать ход нечем, кроме того, что уже сказано выше: выдумывать
     * достоинства хода ради красивого объяснения запрещено.
     */
    private fun openingCaveatLines(caveat: OpeningCaveat, reasons: List<Reason>): List<String> {
        val pieceName = PieceLexicon.name(pieceTypeOf(caveat.movedPiece))
        val waiting = caveat.undevelopedSquares.map { square ->
            val piece = caveat.evidence.pieceAt(square)
            if (piece == null) sq(square) else PieceLexicon.name(pieceTypeOf(piece)) + " " + sq(square)
        }

        val pending = buildList {
            if (waiting.isNotEmpty()) add(russianList(waiting) + " ещё не в игре")
            if (caveat.castlingPending) add("рокировка не сделана")
        }
        if (pending.isEmpty()) return emptyList()

        val weighty = setOf(ReasonTier.MATERIAL_TACTICS, ReasonTier.KING_SAFETY, ReasonTier.PROPHYLAXIS)
        val justified = reasons.any { it.tier in weighty }

        val verdict = if (justified) {
            "Дебютное правило тут уступает конкретной выгоде, названной выше: она считается по материалу и безопасности короля, а правило — только общее напоминание."
        } else {
            "Чем это лучше развития и рокировки, признаки позиции не объясняют — смотрите вариант ниже."
        }

        return listOf(
            "Оговорка: ходить в дебюте дважды одной фигурой не принято, а тут " +
                russianList(pending) + " — " + pieceName + " с " + sq(caveat.from) + " идёт повторно. " +
                verdict
        )
    }

    /** Один-два самых весомых факта тира, а не все подряд — пункт должен звучать как фраза тренера, а не как список. */
    private fun reasonSentence(reason: Reason, glossary: Glossary, memory: RenderMemory, forkedSquares: Set<Square>): String? {
        val sentences = statementsFor(reason.facts, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON)
        if (sentences.isEmpty()) return null
        return sentences.joinToString(" ")
    }

    // ——— СЫГРАННЫЙ ХОД ———

    /**
     * Сравнение сыгранного хода с лучшим. Если ходы совпали, [Section.PlayedMove.missed]
     * пуст по построению (см. [org.chesscoach.coach.analysis.PipelineOrchestrator]),
     * и это тоже полезная новость, а не пустое место — читателю важно увидеть,
     * что он не упустил ничего из того, что даёт лучший ход.
     */
    private fun playedMoveBody(section: Section.PlayedMove, explanation: PositionExplanation): String? {
        if (section.matchesBest) {
            // Про совпадение уже сказано пометкой у заголовка лучшего хода —
            // повторять это отдельным разделом значит заставить читателя
            // сличать две строки, чтобы понять, что речь об одном ходе.
            if (explanation.section<Section.WhyThisMove>() != null) return null
            return BULLET + "Сыгран лучший ход."
        }

        return moveExplanation(section, explanation.sideToMove, "Лучше было", "Сыгранный ход")
    }

    /**
     * Полный ответ про ход: итоговые оценки, вариант и причина. Одинаков для
     * сыгранного хода и своего варианта: это один и тот же вопрос.
     */
    private fun moveExplanation(
        section: Section.PlayedMove,
        mover: Side,
        betterPrefix: String,
        subject: String,
    ): String = bulleted(
        listOfNotNull(
            moveScoresLine(section),
            // Сам ход уже назван в заголовке: вариант начинается с ответа на него.
            section.lineSan.takeIf { it.size >= 2 }?.let { "Вариант после хода: " + SanRuFormatter.line(it.drop(1)) + "." },
            moveReasonLine(section, mover),
            comparisonBody(section, betterPrefix, subject),
        ),
    )

    /** Итог и разница подписаны каждый своим словом: "оценка" и "потеря". */
    private fun moveScoresLine(section: Section.PlayedMove): String? {
        val after = section.scoreAfterFromWhite ?: return null
        val best = section.bestScoreFromWhite ?: return null
        return "Оценка после хода " + ScoreFormat.short(after) + ", после лучшего " +
            SanRuFormatter.move(section.bestSan) + " " + ScoreFormat.short(best) + ": " +
            lossPhrase(section.centipawnLoss) + "."
    }

    /** Причина из материала варианта и новых ударов соперника. Нет ни того, ни другого — так и пишем. */
    private fun moveReasonLine(section: Section.PlayedMove, mover: Side): String? {
        if (!isPunished(section.classification)) return null
        val parts = mutableListOf<String>()
        // Пропущенный мат и есть причина: висящая пешка рядом с ним только сбивает.
        section.allowedMate?.let { return "Почему: " + decapitalize(allowedMateSentence(it, section.playedSan, mover)) }
        val swing = section.materialSwing ?: 0
        val dangers = section.punishment.filter(::explainsDanger).sortedBy(::damageRank)
        parts += statementsFor(dangers, emptySet(), Glossary(), RenderMemory(), MAX_FACTS_PER_REASON)
            .map { "после хода: " + decapitalize(it) }
        val lineEnd = listOfNotNull(
            materialGainWord(-swing)?.let { SideLexicon.name(mover) + " теряют " + it },
        ) + statementsFor(section.lineGains, emptySet(), Glossary(), RenderMemory(), MAX_FACTS_PER_REASON)
            .map { decapitalize(it.removeSuffix(".")) }
        if (lineEnd.isNotEmpty()) parts += "к концу варианта " + lineEnd.joinToString("; ") + "."
        if (parts.isEmpty()) {
            return "Почему: конкретной причины я не нашёл, оценка опирается на вариант выше."
        }
        return "Почему: " + parts.first() + parts.drop(1).joinToString("") { " " + capitalize(it) }
    }

    /**
     * Сравнение хода с лучшим одним абзацем. [betterPrefix] и [subject]
     * отличаются для хода из партии и для варианта, который человек набрал
     * сам: "Лучше было Nc3" уместно про уже сыгранное, но про предложенный
     * прямо сейчас вариант так не говорят.
     */
    private fun comparisonBody(
        section: Section.PlayedMove,
        betterPrefix: String,
        subject: String,
    ): String {
        val betterLine = betterPrefix + " " + SanRuFormatter.move(section.bestSan) + "."

        // Упущенное называется своими словами, а не тем же предложением, что в
        // разделе про лучший ход. Дословный повтор глушится защитой от
        // повторов — и раздел, ради которого разбор и читают, остался бы без
        // главного: чем именно сыгранный ход хуже.
        // Вилка поглощает перечисление своих же целей: сказать "упускает вилку
        // и заодно две фигуры на тех же полях" значит трижды назвать одно.
        val forkTargets = section.missed
            .filterIsInstance<ForkFact>()
            .flatMap { it.targets }
            .toSet()

        val missedPhrases = section.missed
            .filterNot {
                (it is HangingPieceFact && it.square in forkTargets) ||
                    (it is TrappedPieceFact && it.square in forkTargets)
            }
            .mapNotNull { runCatching { missedPhrase(it) }.getOrNull() }
            .distinct()
            .take(MAX_FACTS_PER_REASON)

        // Вариант уже назвал ответ соперника, повторять его незачем.
        val reply = if (section.lineSan.size >= 2) "" else refutationSentence(section)
        if (missedPhrases.isEmpty()) return betterLine + reply

        // Запятые без союза: сама именная группа уже может содержать "и"
        // ("вилку на d1 и e2"), и обычное перечисление дало бы "и e2 и связку".
        return betterLine + " " + subject + " упускает " + missedPhrases.joinToString(", ") + "." + reply
    }

    /**
     * Чем соперник наказывает — только там, где есть за что.
     *
     * После хорошего хода лучший ответ соперника это просто продолжение партии,
     * и подавать его как расплату значило бы пугать читателя на ровном месте.
     */
    private fun refutationSentence(section: Section.PlayedMove): String {
        val reply = section.refutationSan ?: return ""
        val punished = section.classification == MoveClassification.BLUNDER ||
            section.classification == MoveClassification.MISTAKE ||
            section.classification == MoveClassification.INACCURACY
        if (!punished) return ""
        return " Соперник отвечает " + SanRuFormatter.move(reply) + "."
    }

    /**
     * Чем ход плох — именной группой, которая встаёт в скобки после ярлыка.
     *
     * Null для признаков, вред от которых не описывается коротко: про них лучше
     * промолчать, чем выдумывать формулировку.
     */
    private fun damagePhrase(fact: Fact): String? = when (fact) {
        is MateFact -> "пропускает мат в " + fact.movesToMate
        is BackRankFact -> "подставляется под мат по последней горизонтали"
        is ForkFact -> "попадает под вилку на " + russianList(fact.targets.map { sq(it) })
        is PinFact -> "попадает под связку на " + sq(fact.pinned)
        is SkewerFact -> "попадает под рентген на " + sq(fact.front)
        is DiscoveredAttackFact -> "попадает под вскрытое нападение на " + sq(fact.target)
        is OverloadedDefenderFact -> "перегружает защитника на " + sq(fact.defender)
        is HangingPieceFact ->
            "отдаёт " + PieceLexicon.name(pieceTypeOf(fact.piece), GrammaticalCase.ACCUSATIVE) +
                " на " + sq(fact.square)

        is TrappedPieceFact ->
            "запирает " + PieceLexicon.name(pieceTypeOf(fact.piece), GrammaticalCase.ACCUSATIVE) +
                " на " + sq(fact.square)

        else -> null
    }

    /**
     * Порядок, в котором стоит называть вред: сначала то, что заканчивает
     * партию, потом крупный материал, и только затем мелочи. Список фактов
     * приходит в порядке срабатывания детекторов, а он к тяжести отношения
     * не имеет.
     */
    private fun damageRank(fact: Fact): Int = when {
        fact is MateFact -> 0
        fact is BackRankFact -> 1
        fact is HangingPieceFact && fact.lossCentipawns >= HEAVY_LOSS_CP -> 2
        fact is ForkFact -> 3
        fact is SkewerFact -> 4
        fact is DiscoveredAttackFact -> 5
        fact is PinFact -> 6
        fact is TrappedPieceFact -> 7
        fact is HangingPieceFact -> 8
        else -> 99
    }

    /**
     * Запасная причина, когда признаки молчат: падение оценки известно всегда.
     * Число — пока оно правда в пешках: оценки до и после стоят рядом, и
     * "потеря 10.2" сверяется с ними. Упущенный мат пешками не меряют.
     */
    private fun lossPhrase(centipawnLoss: Int): String =
        if (centipawnLoss >= MATE_LOSS_CP) {
            "оценка рушится"
        } else {
            "потеря " + String.format(Locale.ROOT, "%.1f", centipawnLoss / 100.0)
        }

    /**
     * Чего лишился сыгранный ход — короткой именной группой в винительном
     * падеже, чтобы вставать после "упускает".
     *
     * Null для признаков, потеря которых не описывается одним словом: про них
     * лучше промолчать, чем выдумывать формулировку.
     */
    private fun missedPhrase(fact: Fact): String? = when (fact) {
        is MateFact -> if (fact.movesToMate == 1) "мат в один ход" else "форсированный мат в ${fact.movesToMate} ${movesWord(fact.movesToMate)}"
        is ForkFact -> "вилку на " + russianList(fact.targets.map { sq(it) })
        is PinFact -> if (fact.absolute) "связку на " + sq(fact.pinned) else "связку по " + sq(fact.pinned)
        is SkewerFact -> "рентген на " + sq(fact.behind)
        is DiscoveredAttackFact -> "вскрытое нападение на " + sq(fact.target)
        is BackRankFact -> "мат по последней горизонтали"
        is OverloadedDefenderFact -> "перегрузку защитника на " + sq(fact.defender)
        is MateFact -> "мат в " + fact.movesToMate
        // Именно какой фигуры: "выигрыш фигуры на b7" про пешку — неправда,
        // а пешки в этом списке встречаются чаще всего.
        is HangingPieceFact ->
            "выигрыш " + PieceLexicon.name(pieceTypeOf(fact.piece), GrammaticalCase.GENITIVE) +
                " на " + sq(fact.square)

        is TrappedPieceFact ->
            "поимку " + PieceLexicon.name(pieceTypeOf(fact.piece), GrammaticalCase.GENITIVE) +
                " на " + sq(fact.square)
        is OutpostFact -> "форпост на " + sq(fact.square)
        is PassedPawnFact -> "проходную на " + sq(fact.square)
        is OpenFileRookFact -> "открытую линию " + fact.file
        else -> null
    }

    // ——— ПРОФИЛАКТИКА ———

    private val prophylaxisTemplates: List<(ProphylaxisFact) -> String> = listOf(
        { f -> "Заодно снимает угрозу ${SanRuFormatter.move(f.preventedMoveSan)}: до хода она была реальной, теперь её нет." },
        { f -> "Заново это лишает соперника ${SanRuFormatter.move(f.preventedMoveSan)} — ход больше не работает." },
        { f -> "Заодно ход разряжает ${SanRuFormatter.move(f.preventedMoveSan)}, о котором стоило беспокоиться." },
    )

    private fun prophylaxisBody(fact: ProphylaxisFact, pool: TemplatePool): String = pool.choose(fact, prophylaxisTemplates)

    // ——— ПЛАН ———

    private fun planStepSentence(
        idea: PlanIdea,
        facts: List<Fact>,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
        targetSide: Side,
    ): String? {
        // План перечисляет цели, а не пересказывает признаки: три подряд
        // предложения "пешка на d5 изолирована" отличаются одним полем и
        // читаются как заевшая пластинка.
        val targets = facts.mapNotNull(::planTarget).distinct()
        if (targets.size == facts.size) {
            return planIdeaPhrase(idea, targets.size, targetSide) + ": " + russianList(targets) + "."
        }

        val statements = statementsFor(facts, forkedSquares, glossary, memory)
        if (statements.isEmpty()) return null
        // factStatement собирает самостоятельное предложение с заглавной буквы;
        // тут оно идёт придаточным после двоеточия, поэтому строчная уместнее.
        return planIdeaPhrase(idea, statements.size, targetSide) + ": " +
            statements.joinToString(" ") { decapitalize(it) }
    }

    /**
     * Поле, на которое смотрит шаг плана.
     *
     * Null для признаков, у которых одного поля не бывает: для них остаётся
     * обычное предложение, а не строка перечисления.
     */
    private fun planTarget(fact: Fact): String? = when (fact) {
        is PawnWeaknessFact -> fact.square.value().lowercase()
        is PassedPawnFact -> fact.square.value().lowercase()
        is OutpostFact -> fact.square.value().lowercase()
        is OpenFileRookFact -> fact.rook.value().lowercase()
        else -> null
    }

    /**
     * [count] — сколько признаков попало в один шаг: от этого зависит число в
     * фразе. [targetSide] — чьи пешки слабые: называем цвет, а не "мои" и
     * "соперника". Заголовок плана зависит от того, за кого играл читатель, и
     * притяжательное слово под ним то и дело означало бы не ту сторону.
     */
    private fun planIdeaPhrase(idea: PlanIdea, count: Int, targetSide: Side): String = when (idea) {
        PlanIdea.OCCUPY_OUTPOST ->
            if (count > 1) "Держать фигуры на форпостах" else "Держать фигуру на форпосте"

        PlanIdea.OPEN_FILE_FOR_ROOK ->
            if (count > 1) "Держать ладьи на открытых линиях" else "Держать ладью на открытой линии"

        PlanIdea.ADVANCE_PASSED_PAWN ->
            if (count > 1) "Двигать проходные пешки вперёд" else "Двигать проходную пешку вперёд"

        PlanIdea.TARGET_WEAK_PAWN ->
            (if (count > 1) "Давить на слабые пешки " else "Давить на слабую пешку ") +
                SideLexicon.name(targetSide, GrammaticalCase.GENITIVE)

        PlanIdea.ATTACK_KING -> "Продолжать давление на короля"
        PlanIdea.IMPROVE_WORST_PIECE -> "Улучшать положение худшей фигуры"
        PlanIdea.CENTRALISE -> "Централизовать фигуры"
        PlanIdea.TRADE_INTO_ENDGAME -> "Стремиться к выгодному эндшпилю через размены"
        PlanIdea.PREVENT_BREAK -> "Не пускать подрыв соперника"
    }

    // ——— ЕСЛИ СЫГРАТЬ ИНАЧЕ (ловушка) ———

    private fun trapBody(section: Section.Trap, mover: Side): String {
        val moveRu = SanRuFormatter.move(section.moveSan)
        val refutationRu = SanRuFormatter.line(section.refutationSan)
        val score = section.scoreFromWhite?.let { " (оценка " + ScoreFormat.short(it) + ")" }.orEmpty()
        return "$moveRu выглядит естественно, но после $refutationRu ${verdictPhrase(section.verdict, mover)}$score."
    }

    private fun verdictPhrase(verdict: MaterialVerdict, mover: Side): String = when (verdict) {
        MaterialVerdict.LOSES_PAWN -> "${SideLexicon.name(mover)} остаются без пешки"
        MaterialVerdict.LOSES_EXCHANGE -> "${SideLexicon.name(mover)} отдают качество"
        MaterialVerdict.LOSES_PIECE -> "${SideLexicon.name(mover)} остаются без фигуры"
        MaterialVerdict.LOSES_ROOK -> "${SideLexicon.name(mover)} остаются без ладьи"
        MaterialVerdict.LOSES_QUEEN -> "${SideLexicon.name(mover)} остаются без ферзя"
        MaterialVerdict.GETS_MATED -> "${SideLexicon.name(mover)} получают мат"
        // Родительный падеж — не именительный: "позиция у чёрных", а не "у чёрные".
        MaterialVerdict.POSITIONAL -> "позиция у ${SideLexicon.name(mover, GrammaticalCase.GENITIVE)} заметно хуже"
    }

    // ——— АЛЬТЕРНАТИВЫ ———

    private fun alternativesBody(section: Section.Alternatives): String? {
        if (section.lines.isEmpty()) return null
        return section.lines.joinToString(System.lineSeparator()) { line ->
            val loss = if (line.lossVsBestCentipawns >= LOSS_SHOWN_CP) ", " + lossPhrase(line.lossVsBestCentipawns) else ""
            val moveRu = BULLET + SanRuFormatter.move(line.moveSan) + " (оценка " + ScoreFormat.short(line.scoreFromWhite) + loss + ")"
            val category = alternativeCategory(line.lossVsBestCentipawns)
            val continuation = line.continuationSan
            if (continuation.isEmpty()) {
                "$moveRu — $category."
            } else {
                "$moveRu — $category: ${SanRuFormatter.line(continuation)}."
            }
        }
    }

    private fun alternativeCategory(lossVsBestCentipawns: Int): String = when (MoveClassification.of(lossVsBestCentipawns)) {
        MoveClassification.BEST, MoveClassification.GOOD -> "тоже неплохой ход"
        MoveClassification.INACCURACY -> "немного уступает лучшему"
        MoveClassification.MISTAKE -> "заметно слабее"
        MoveClassification.BLUNDER -> "серьёзно хуже"
    }

    // ——— Дедупликация и отбор фактов ———

    /** До скольки сантипешек перевес называется "лишнее качество", а не "заметный материальный перевес". */
    private const val EXCHANGE_MAGNITUDE_CP = 300

    /** Все факты разбора разом — чтобы вилка поглощала свои цели по всему тексту, а не только в своём пункте. */
    private fun collectAllFacts(explanation: PositionExplanation): List<Fact> = explanation.sections.flatMap { section ->
        when (section) {
            is Section.EvalReason, is Section.PositionAssessment, is Section.AfterMove -> emptyList()
            is Section.MyPosition ->
                section.kingSafety + section.loosePieces + section.exchanges + section.advice.map { it.fact } +
                    section.opponentPlan.flatMap { it.facts } +
                    (section.threat?.consequences ?: emptyList())

            is Section.MyAttack ->
                section.mate + section.captures + section.activity + section.exchanges +
                    section.advice.map { it.fact } + section.plan.flatMap { it.facts }

            is Section.WhyThisMove -> section.reasons.flatMap { it.facts }
            is Section.PlayedMove -> section.missed + section.punishment
            is Section.BestMoves -> section.human?.facts ?: emptyList()
            is Section.Prophylaxis, is Section.Trap, is Section.Alternatives -> emptyList()
        }
    }

    /**
     * Убирает из списка фактов то, что дублирует уже сказанное, и то, что
     * поглощено вилкой — до рендера, а не подчисткой готовых строк постфактум.
     *
     * Ключ дублирования — сам итоговый текст: [Glossary.mention] больше не
     * меняет строку в зависимости от того, первое это упоминание термина или
     * нет (сноски вынесены в отдельный блок), поэтому два факта с одинаковым
     * текстом — это ровно два факта об одном и том же, а не техническая
     * случайность двух разных объектов [Evidence].
     */
    private fun dedupeFacts(facts: List<Fact>, forkedSquares: Set<Square>, glossary: Glossary): List<Fact> {
        val withoutForkedLeftovers = facts.filterNot { fact ->
            (fact is HangingPieceFact && fact.square in forkedSquares) ||
                (fact is TrappedPieceFact && fact.square in forkedSquares)
        }
        val distinctTexts = withoutForkedLeftovers
            .distinctBy { runCatching { renderFactStatement(it, glossary) }.getOrNull() ?: it }
        // Свой и чужой король в одном пункте не смешиваем: два разных короля
        // подряд не говорят, хорошо это для хода или плохо. Первый — важнее,
        // раз до него дошла очередь раньше остальных фактов тира.
        var keptKingFact = false
        return distinctTexts.filter { fact ->
            if (fact is KingSafetyFact) {
                if (keptKingFact) false else { keptKingFact = true; true }
            } else {
                true
            }
        }
    }

    /**
     * Дедуплицирует и поглощает [facts] локально, затем не даёт ни одному из
     * получившихся предложений прозвучать второй раз где-либо ещё в этом же
     * разборе ([memory]), и лишь потом обрезает до [limit] — отбор всегда идёт
     * по фактам, а обрезка не должна тратить лимит на то, что и так дубль.
     */
    private fun statementsFor(
        facts: List<Fact>,
        forkedSquares: Set<Square>,
        glossary: Glossary,
        memory: RenderMemory,
        limit: Int = Int.MAX_VALUE,
    ): List<String> = dedupeFacts(facts, forkedSquares, glossary)
        // Один странный факт должен стоить одной фразы, а не всего разбора:
        // экран с пустым местом вместо предложения переживаем, экран без
        // разбора вовсе — нет.
        .mapNotNull { runCatching { renderFactStatement(it, glossary) }.getOrNull() }
        .filter { it.isNotBlank() }
        .filter { memory.claim(it) }
        .take(limit)

    /** Предложения, уже прозвучавшие в этом разборе — общая память на весь [render], не только на один раздел. */
    private class RenderMemory {
        private val used = mutableSetOf<String>()

        /** true — предложение новое, теперь занято; false — уже было сказано, использовать нельзя. */
        fun claim(sentence: String): Boolean = used.add(sentence)
    }

    // ——— Факт → предложение ———

    /**
     * Одно грамматически законченное предложение по факту, без упоминания
     * того, кому он на пользу — это решает контекст раздела, где предложение
     * оказалось (угроза, план, причина хода и так далее).
     */
    private fun renderFactStatement(fact: Fact, glossary: Glossary): String = when (fact) {
        is HangingPieceFact -> {
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            "$name на ${sq(fact.square)} под боем${hangingReason(fact)}."
        }

        is ExchangeFact -> {
            val attacker = capitalize(namedSquare(fact.evidence, fact.attacker, GrammaticalCase.NOMINATIVE))
            val victim = namedSquare(fact.evidence, fact.square, GrammaticalCase.ACCUSATIVE)
            if (fact.lineSan.isEmpty()) {
                "$attacker берёт $victim, дельта ${pawnsLabel(fact.deltaCentipawns)}."
            } else {
                exchangeStatement(fact)
            }
        }

        is TrappedPieceFact -> {
            val gender = PieceLexicon.genderOf(pieceTypeOf(fact.piece))
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            val its = if (gender == Gender.MASCULINE) "его" else "её"
            "$name на ${sq(fact.square)} ${PieceLexicon.Agreements.TRAPPED.agree(gender)} — любой $its ход теряет материал."
        }

        is ForkFact -> {
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            if (fact.targets.all { fact.evidence.pieceAt(it) != null }) {
                // Все цели известны поимённо — говорим как тренер, а не как таблица координат.
                val targets = russianList(fact.targets.map { namedSquare(fact.evidence, it, GrammaticalCase.ACCUSATIVE) })
                "$name на ${sq(fact.from)} вилкой нападает сразу на $targets."
            } else {
                // Фигуры на полях доказательства не записаны (старый факт, ручной тест) —
                // остаёмся на прежней, более сухой формулировке с голыми полями.
                val targets = russianList(fact.targets.map { sq(it) })
                "$name на ${sq(fact.from)} держит вилку: под боем сразу $targets."
            }
        }

        is PinFact -> {
            // Каждое место подстановки разрешается независимо и падает на тот же
            // самый филлер, что был в прежнем тексте, если фигура не записана —
            // поэтому же комбинация фактов без данных о фигурах даёт byte-в-byte
            // прежний результат, а не новый шаблон с пустотами.
            val pinnedPiece = fact.evidence.pieceAt(fact.pinned)
            val gender = pinnedPiece?.let { PieceLexicon.genderOf(pieceTypeOf(it)) } ?: Gender.FEMININE
            val subject = pinnedPiece?.let { capitalize(PieceLexicon.name(pieceTypeOf(it))) } ?: "Фигура"
            val predicate = PieceLexicon.Agreements.PINNED_SHORT.agree(gender)
            val pronoun = pronounInstrumental(gender)
            if (fact.absolute) {
                "$subject на ${sq(fact.pinned)} $predicate намертво — за $pronoun король на ${sq(fact.behind)}."
            } else {
                val behind = namedOr(fact.evidence, fact.behind, GrammaticalCase.NOMINATIVE, fallback = "фигура")
                "$subject на ${sq(fact.pinned)} $predicate: за $pronoun на ${sq(fact.behind)} стоит $behind дороже."
            }
        }

        is SkewerFact -> {
            val term = glossary.mention(GlossaryTerm.XRAY, "Рентген")
            val front = namedOr(fact.evidence, fact.front, GrammaticalCase.NOMINATIVE, fallback = "фигура")
            val behind = namedSquare(fact.evidence, fact.behind, GrammaticalCase.NOMINATIVE)
            "$term по линии от ${sq(fact.attacker)}: уйдёт $front с ${sq(fact.front)} — под боем окажется $behind."
        }

        is DiscoveredAttackFact -> {
            val target = namedSquare(fact.evidence, fact.target, GrammaticalCase.ACCUSATIVE)
            val revealed = namedOr(fact.evidence, fact.revealed, GrammaticalCase.GENITIVE, fallback = "фигуры")
            "Скрытый удар: ход с ${sq(fact.moving)} открывает атаку на $target от $revealed на ${sq(fact.revealed)}."
        }

        is BackRankFact -> {
            val exploiter = namedSquare(fact.evidence, fact.exploiter, GrammaticalCase.NOMINATIVE)
            val cover = if (fact.blockedBy.isEmpty()) {
                ""
            } else {
                " Пешки на ${russianList(fact.blockedBy.map { sq(it) })} закрывают королю отход."
            }
            "Королю на ${sq(fact.king)} грозит мат по последней горизонтали: $exploiter проходит на неё.$cover"
        }

        is OverloadedDefenderFact -> {
            val defenderPiece = fact.evidence.pieceAt(fact.defender)
            // "должна" в прежнем тексте согласовано с фиксированным "Фигура" (ж. р.);
            // раз теперь называем настоящую фигуру, глагол обязан согласоваться с ней,
            // а не оставаться в женском роде для коня или слона.
            val gender = defenderPiece?.let { PieceLexicon.genderOf(pieceTypeOf(it)) } ?: Gender.FEMININE
            val phrase = if (defenderPiece != null) {
                val adjective = capitalize(PieceLexicon.Agreements.OVERLOADED_ADJECTIVE.agree(gender))
                "$adjective ${PieceLexicon.name(pieceTypeOf(defenderPiece))}"
            } else {
                "Перегруженная фигура"
            }
            val term = glossary.mention(GlossaryTerm.OVERLOADED_PIECE, phrase)
            val duties = russianList(fact.duties.map { namedSquare(fact.evidence, it, GrammaticalCase.ACCUSATIVE) })
            val verb = PieceLexicon.Agreements.MUST_COVER.agree(gender)
            "$term на ${sq(fact.defender)} $verb успевать сразу на $duties."
        }

        is MateFact -> if (fact.movesToMate == 1) {
            "Ставит мат."
        } else {
            "Форсированный мат в ${fact.movesToMate} ${movesWord(fact.movesToMate)}."
        }

        is KingSafetyFact -> {
            // Чей это король, называем всегда, а не только когда "и так понятно":
            // в одном пункте причины оба короля могут стоять рядом (свой и
            // чужой), и без стороны предложение "Король под давлением" не
            // говорит, хорошо это для хода или плохо.
            val side = SideLexicon.name(fact.kingSide, GrammaticalCase.GENITIVE)
            val files = if (fact.openFilesNearKing.isEmpty()) {
                ""
            } else {
                " Рядом открыты линии ${russianList(fact.openFilesNearKing.map { it.toString() })}."
            }
            "Король $side на ${sq(fact.king)} под давлением, прикрытие ослаблено.$files"
        }

        is PawnWeaknessFact -> when (fact.kind) {
            PawnWeaknessKind.ISOLATED -> "Пешка на ${sq(fact.square)} изолирована."
            PawnWeaknessKind.DOUBLED -> "Пешка на ${sq(fact.square)} сдвоена."
            PawnWeaknessKind.BACKWARD -> "Пешка на ${sq(fact.square)} отсталая."
        }

        is PassedPawnFact -> {
            val defence = if (fact.defended) ", защищена" else ""
            val distance = when {
                fact.squaresToPromotion <= 2 -> "совсем близко к превращению"
                fact.squaresToPromotion <= 5 -> "уже недалеко от превращения"
                else -> "пока далеко от превращения"
            }
            "Пешка на ${sq(fact.square)} проходная$defence, $distance."
        }

        is OutpostFact -> {
            val type = pieceTypeOf(fact.piece)
            val name = capitalize(PieceLexicon.name(type))
            // Местоимение согласуется с фигурой: конь — "его", ладья — "её".
            val it = if (PieceLexicon.genderOf(type) == Gender.MASCULINE) "его" else "её"
            name + " на " + sq(fact.square) + " стоит форпостом — пешкой " + it +
                " оттуда не согнать."
        }

        is OpenFileRookFact -> {
            val openness = if (fact.halfOpen) "полуоткрытую" else "открытую"
            "Ладья на ${sq(fact.rook)} держит $openness линию ${fact.file}."
        }

        is BadBishopFact -> {
            val term = glossary.mention(GlossaryTerm.BAD_BISHOP, "плохим слоном")
            "Слон на ${sq(fact.bishop)} упирается в свои же пешки и остаётся $term."
        }

        is DevelopmentFact -> {
            // Сторону называем обязательно. Безличное "развитие отстаёт" под
            // заголовком МОЯ АТАКА читается как рассказ о моих же бедах, хотя
            // факт попал туда именно потому, что отстал соперник.
            val side = capitalize(SideLexicon.name(fact.owner))
            val castling = if (fact.castled) "" else " Король не рокирован."
            val undeveloped = if (fact.undeveloped.isEmpty()) {
                ""
            } else {
                " Не вышли в игру фигуры на ${russianList(fact.undeveloped.map { sq(it) })}."
            }
            ("$side отстали в развитии.$castling$undeveloped").trim()
        }

        // Сторона — существительное множественного числа: "чёрные", а не
        // средний род на "оно", поэтому и глагол здесь во множественном числе.
        is MobilityEdgeFact -> "У ${SideLexicon.name(fact.owner, GrammaticalCase.GENITIVE)} заметно больше пространства для манёвра."

        is CenterControlFact -> "${capitalize(SideLexicon.name(fact.owner))} прочно держат центр."

        // Профилактика — полноправная причина хода (третий тир), и в список
        // причин она попадает штатно. Раньше тут стояло падение по ошибочному
        // предположению, что этого не бывает: на замороженных ответах движка
        // такой факт не возникал, а на живом поиске разбор начал исчезать
        // через раз.
        is ProphylaxisFact ->
            "Снимает угрозу " + SanRuFormatter.move(fact.preventedMoveSan) + "."

        // Угроза же собирается отдельным разделом и рядовым фактом не бывает.
        // Молча пропустить безопаснее, чем уронить весь разбор из-за одного
        // предложения.
        is ThreatFact -> ""

        is ForkThreatFact -> {
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            val targets = russianList(fact.targets.map { namedSquare(fact.evidence, it, GrammaticalCase.ACCUSATIVE) })
            "$name с ${sq(fact.from)} может пойти на ${sq(fact.to)} и напасть сразу на $targets" + forkTradeClause(fact) + "."
        }

        is RemoveDefenderFact -> {
            val capturer = namedSquare(fact.evidence, fact.capturer, GrammaticalCase.NOMINATIVE)
            val defender = namedSquare(fact.evidence, fact.defender, GrammaticalCase.ACCUSATIVE)
            val target = namedSquare(fact.evidence, fact.target, GrammaticalCase.GENITIVE)
            capitalize(capturer) + " может побить " + defender + ": это снимает защиту с " + target + "."
        }

        is WeakSquareFact -> {
            "Поле ${sq(fact.square)} рядом с королём ${SideLexicon.name(fact.owner, GrammaticalCase.GENITIVE)} " +
                "пешкой уже не прикрыть, и соперник на него смотрит."
        }

        is PawnBreakFact -> {
            val targets = (listOf(fact.target) + fact.alsoAttacks).sortedByDescending { square ->
                fact.evidence.pieceAt(square)?.pieceType?.let(SeeCalculator::value) ?: 0
            }
            val named = russianList(targets.map { namedSquare(fact.evidence, it, GrammaticalCase.ACCUSATIVE) })
            "Пешечный удар ${sq(fact.from)}-${sq(fact.to)}: пешка нападает на $named и вскрывает игру."
        }

        is EndgameFact -> endgameStatement(fact)
    }

    /** "Nxf5 Qxf5: конь за слона, материал +0.1 в пользу белых." */
    private fun exchangeStatement(fact: ExchangeFact): String {
        val forced = fact.lineSan.take(fact.forcedPlies)
        val line = SanRuFormatter.line(forced)
        val attacker = fact.evidence.pieceAt(fact.attacker)
        val victim = fact.evidence.pieceAt(fact.square)
        val swap = if (forced.size == 2 && attacker != null && victim != null) {
            PieceLexicon.name(pieceTypeOf(attacker)) + " за " +
                PieceLexicon.name(pieceTypeOf(victim), GrammaticalCase.ACCUSATIVE) + ", "
        } else {
            ""
        }
        return "$line: $swap" + materialBalance(fact.deltaCentipawns, fact.favours) + "." + optionalExchange(fact)
    }

    /**
     * Равное продолжение размена: "Дальше можно разменять и коней: Nxd4 Qxd4".
     * Обязательную часть оно не меняет, поэтому идёт отдельной фразой, а если
     * движок от него отказывается, так и говорим.
     */
    private fun optionalExchange(fact: ExchangeFact): String {
        val optional = fact.lineSan.drop(fact.forcedPlies)
        if (optional.isEmpty()) return ""
        val victim = sanPieceType(fact.lineSan[fact.forcedPlies - 1])
        val taker = sanPieceType(optional.first())
        val what = if (victim == taker) {
            "разменять и " + tradedPlural(victim)
        } else {
            "продолжить: " + PieceLexicon.name(taker) + " за " + PieceLexicon.name(victim, GrammaticalCase.ACCUSATIVE)
        }
        val engine = fact.engineChoiceSan?.let { ", но движок предпочитает " + SanRuFormatter.move(it) }.orEmpty()
        return " Дальше можно $what: " + SanRuFormatter.line(optional) + engine + "."
    }

    /** "разменять и коней": фигуры во множественном числе после "разменять". */
    private fun tradedPlural(type: PieceType): String = when (type) {
        PieceType.KNIGHT -> "коней"
        PieceType.BISHOP -> "слонов"
        PieceType.ROOK -> "ладьи"
        PieceType.QUEEN -> "ферзей"
        else -> "пешки"
    }

    private fun pluralNominative(type: PieceType): String = when (type) {
        PieceType.KNIGHT -> "кони"
        PieceType.BISHOP -> "слоны"
        PieceType.ROOK -> "ладьи"
        PieceType.QUEEN -> "ферзи"
        else -> "пешки"
    }

    /** Какой фигурой сделан ход: по первой букве записи SAN, строчная — пешка. */
    private fun sanPieceType(san: String): PieceType = when (san.firstOrNull()) {
        'N' -> PieceType.KNIGHT
        'B' -> PieceType.BISHOP
        'R' -> PieceType.ROOK
        'Q' -> PieceType.QUEEN
        'K' -> PieceType.KING
        else -> PieceType.PAWN
    }

    /** Разница в материале с названной стороной: само число не говорит, за кого оно. */
    private fun materialBalance(deltaForCapturer: Int, capturer: Side): String {
        if (deltaForCapturer == 0) return "материал равный"
        val side = if (deltaForCapturer > 0) capturer else capturer.flip()
        val pawns = String.format(Locale.ROOT, "%.1f", kotlin.math.abs(deltaForCapturer) / 100.0)
        return "материал +$pawns в пользу " + SideLexicon.name(side, GrammaticalCase.GENITIVE)
    }

    /** ", но Qxg4 Bxg4 — размен ферзей": равный размен снимает вилку. Пусто, если фигуру не бьют. */
    private fun forkTradeClause(fact: ForkThreatFact): String {
        if (fact.tradeLineSan.isEmpty()) return ""
        val line = SanRuFormatter.line(fact.tradeLineSan)
        val same = fact.tradedBy?.pieceType == fact.piece.pieceType
        val plural = when (fact.piece.pieceType) {
            PieceType.QUEEN -> "ферзей"
            PieceType.ROOK -> "ладей"
            PieceType.BISHOP -> "слонов"
            PieceType.KNIGHT -> "коней"
            else -> null
        }
        val trade = if (same && plural != null) "размен $plural" else "размен"
        // Равный размен в скобках не нуждается: важна итоговая оценка, она стоит рядом.
        val balance = if (fact.tradeDeltaCentipawns == 0) "" else " (" + materialBalance(fact.tradeDeltaCentipawns, fact.favours.flip()) + ")"
        return ", но $line — $trade$balance"
    }

    private fun endgameStatement(fact: EndgameFact): String {
        val owner = SideLexicon.name(fact.owner, GrammaticalCase.GENITIVE)
        return when (fact.kind) {
            EndgameKind.ROOK_BEHIND_PASSED -> "Ладья стоит за проходной пешкой ${sq(fact.square)}: с каждым шагом пешки она становится сильнее."
            EndgameKind.KING_OUTSIDE_SQUARE -> "Пешка ${sq(fact.square)} проходит сама: король не успевает войти в её квадрат."
            EndgameKind.ACTIVE_KING -> "Король $owner на ${sq(fact.square)} заметно активнее короля соперника."
            EndgameKind.OPPOSITE_BISHOPS -> "Слоны разноцветные: даже при лишней пешке выиграть такой эндшпиль трудно, это на руку $owner."
        }
    }

    // ——— Мелкие помощники ———

    /**
     * Почему фигура потеряна — в скобках, сразу за приговором.
     *
     * "Конь на d5 потерян" без причины читается как каприз движка: проверить
     * нечем, поверить не во что. "Бьёт пешка e4, защиты нет" человек проверяет
     * глазами за секунду — и либо соглашается, либо видит, что тренер
     * ошибается, что тоже полезно. Пусто, если нападающий не записан в
     * доказательстве: выдумывать причину нельзя.
     */
    private fun hangingReason(fact: HangingPieceFact): String {
        val attacker = fact.firstAttacker ?: return ""
        val piece = fact.evidence.pieceAt(attacker) ?: return ""
        val attackerName = PieceLexicon.name(pieceTypeOf(piece))
        // "Защиты нет" и "взять обратно невыгодно" — разные вещи: во втором случае
        // взять обратно есть чем, но размен всё равно проигран, и советовать
        // "защити фигуру" было бы вредным советом.
        if (fact.capturedWithCheck) return " (бьёт $attackerName ${sq(attacker)} с шахом, взять обратно сразу нельзя)"
        val verdict = if (fact.defended) "взять обратно невыгодно" else "защиты нет"
        val why = recaptureWhy(fact) ?: return " (бьёт $attackerName ${sq(attacker)}, $verdict)"
        return " (бьёт $attackerName ${sq(attacker)}; $why)"
    }

    /** Почему нельзя взять обратно: ходами, а не приговором. Null — ходов не записано. */
    private fun recaptureWhy(fact: HangingPieceFact): String? {
        val line = fact.recaptureLineSan
        val recapture = line.getOrNull(1)?.let { SanRuFormatter.move(it) } ?: return null
        val reply = line.getOrNull(2)?.let { SanRuFormatter.move(it) }
            ?: return "взять обратно $recapture можно, но это размен в минус"
        val piece = fact.recapturer?.pieceType ?: return "если взять обратно $recapture, то $reply"
        // Взявший обратно гибнет, но и сам забрал фигуру той же цены: это размен,
        // а потеряна только исходная фигура. "Конь потерян" после Nxd4 Nxd4 Qxd4 — неправда.
        val attackerType = fact.firstAttacker?.let { fact.evidence.pieceAt(it) }?.pieceType
        if (attackerType != null && SeeCalculator.value(attackerType) == SeeCalculator.value(piece)) {
            val traded = if (attackerType == piece) pluralNominative(piece) + " меняются" else "фигуры меняются"
            val victim = fact.piece.pieceType ?: return "если взять обратно $recapture, то $reply: $traded"
            val lostVictim = if (PieceLexicon.genderOf(victim) == Gender.MASCULINE) "потерян" else "потеряна"
            return "если взять обратно $recapture, то $reply: $traded, а " + PieceLexicon.name(victim) +
                " всё равно " + lostVictim
        }
        val lost = if (PieceLexicon.genderOf(piece) == Gender.MASCULINE) "потерян" else "потеряна"
        return "если взять обратно $recapture, то $reply и " + PieceLexicon.name(piece) + " " + lost
    }

    private fun pieceTypeOf(piece: Piece) =
        piece.pieceType ?: error("Факт ссылается на пустую клетку — так быть не должно")

    /**
     * Название фигуры на [square] в падеже [case], если она записана в
     * [evidence] через `Evidence.onBoard(...)`; иначе — [fallback].
     *
     * Падать или выдумывать фигуру нельзя: старые факты и написанные вручную
     * тесты этого поля не заполняют, и это по-прежнему честный факт — просто
     * менее подробный.
     */
    private fun namedOr(evidence: Evidence, square: Square, case: GrammaticalCase, fallback: String): String =
        evidence.pieceAt(square)?.let { PieceLexicon.name(pieceTypeOf(it), case) } ?: fallback

    /** "{название} {поле}" вроде "ферзя d8", либо просто голое поле, если фигура не записана. */
    private fun namedSquare(evidence: Evidence, square: Square, case: GrammaticalCase): String {
        val name = evidence.pieceAt(square)?.let { PieceLexicon.name(pieceTypeOf(it), case) } ?: return sq(square)
        return "$name ${sq(square)}"
    }

    /** "за ним" / "за ней" — местоимение в творительном падеже под род связанной фигуры. */
    private fun pronounInstrumental(gender: Gender): String = if (gender == Gender.MASCULINE) "ним" else "ней"

    /** "d8" / "d8 и e6" / "d8, e6 и f3" — по-русски список из трёх и более элементов не через сплошное "и". */
    private fun russianList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.first()
        else -> items.dropLast(1).joinToString(", ") + " и " + items.last()
    }

    private fun sq(square: Square): String = square.toString().lowercase()

    private fun capitalize(text: String): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun decapitalize(text: String): String =
        // Латинская заглавная — это запись хода: nxf5 вместо Nxf5 был бы другой ход.
        text.replaceFirstChar { if (it.isUpperCase() && it !in 'A'..'Z') it.lowercase() else it.toString() }

    /** Русское согласование числительного мата: 1 ход, 2–4 хода, 5+ ходов (и 11–14 — тоже "ходов"). */
    private fun movesWord(n: Int): String {
        val mod100 = kotlin.math.abs(n) % 100
        val mod10 = mod100 % 10
        return when {
            mod100 in 11..14 -> "ходов"
            mod10 == 1 -> "ход"
            mod10 in 2..4 -> "хода"
            else -> "ходов"
        }
    }
}
