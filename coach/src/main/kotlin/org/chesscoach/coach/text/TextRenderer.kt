package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.BadBishopFact
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.DevelopmentFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.MaterialVerdict
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OpenFileRookFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PawnWeaknessKind
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.PlanIdea
import org.chesscoach.coach.model.PlanStep
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.ProphylaxisFact
import org.chesscoach.coach.model.Reason
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.ThreatFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.ScoreFormat

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

    /** "Один-два самых весомых" — сколько фактов показываем в одном пункте причины, остальное отбрасываем. */
    private const val MAX_FACTS_PER_REASON = 2

    /** В угрозе — сам ход соперника и не больше двух следствий, объясняющих опасность. */
    private const val MAX_THREAT_CONSEQUENCES = 2

    fun render(explanation: PositionExplanation): String {
        val glossary = Glossary()
        val pool = TemplatePool(explanation.fen)
        val memory = RenderMemory()
        // Вилка накрывает свои цели по всему разбору, а не только внутри
        // своего же пункта: если "Ферзь на e2 потерян" всплывёт в другом
        // разделе, вилка, которая уже назвала e2 целью, поглощает и его.
        val forkedSquares = collectAllFacts(explanation).filterIsInstance<ForkFact>().flatMap { it.targets }.toSet()

        val body = buildString {
            append(header(explanation))
            for (section in explanation.sections) {
                val sectionBody = renderSection(section, explanation, pool, glossary, memory, forkedSquares) ?: continue
                appendLine()
                appendLine()
                append(titleOf(section, explanation))
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
            append("ТЕРМИНЫ")
            footnotes.forEach { term ->
                appendLine()
                append("${capitalize(term.label)} — ${term.definition}.")
            }
        }
    }

    // ——— Шапка ———

    private fun header(explanation: PositionExplanation): String {
        val moveNumber = MoveNotation.fullMoveNumber(explanation.fen)
        val sideLabel = SideLexicon.name(explanation.sideToMove)
        val scoreLabel = explanation.scoreFromWhite?.let { " Оценка ${ScoreFormat.display(it)}" } ?: ""
        return "Ход $moveNumber, $sideLabel.$scoreLabel"
    }

    // ——— Заголовки и тела разделов ———

    private fun titleOf(section: Section, explanation: PositionExplanation): String = when (section) {
        is Section.Threat -> "УГРОЗА"
        is Section.WhyThisMove -> bestMoveTitle(section, explanation)
        is Section.Prophylaxis -> "ПРОФИЛАКТИКА"
        is Section.Plan -> "ПЛАН"
        is Section.Trap -> "ЕСЛИ СЫГРАТЬ ИНАЧЕ"
        is Section.Alternatives -> "АЛЬТЕРНАТИВЫ"
        is Section.ExpectedContinuation -> "ЧТО БУДЕТ ДАЛЬШЕ"
        is Section.KingSafetyNote -> "БЕЗОПАСНОСТЬ КОРОЛЯ"
        is Section.MaterialNote -> "МАТЕРИАЛ"
    }

    private fun bestMoveTitle(section: Section.WhyThisMove, explanation: PositionExplanation): String {
        val label = if (explanation.playedMoveSan == null || explanation.playedMoveSan == section.moveSan) {
            "ЛУЧШИЙ ХОД"
        } else {
            "СЫГРАННЫЙ ХОД"
        }
        return "$label: ${SanRuFormatter.move(section.moveSan)}"
    }

    /** Раздел без фактов не печатается вовсе — ни заголовка, ни фразы-заполнителя. */
    private fun renderSection(
        section: Section,
        explanation: PositionExplanation,
        pool: TemplatePool,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String? = when (section) {
        is Section.Threat -> threatBody(section.threat, pool, glossary, memory, forkedSquares)
        is Section.WhyThisMove -> whyThisMoveBody(section, glossary, memory, forkedSquares)
        is Section.Prophylaxis -> prophylaxisBody(section.fact, pool)
        is Section.Plan -> planBody(section, glossary, memory, forkedSquares)
        is Section.Trap -> trapBody(section, explanation.sideToMove)
        is Section.Alternatives -> alternativesBody(section)
        is Section.ExpectedContinuation -> continuationBody(section, explanation)
        // Тот же факт мог уже прозвучать раньше в этом же разборе (например,
        // в причинах хода) — тогда statementsFor молча его пропустит, и весь
        // раздел останется без текста: это не ошибка, а и есть требование не
        // повторяться между разделами.
        is Section.KingSafetyNote -> statementsFor(listOf(section.fact), forkedSquares, glossary, memory).firstOrNull()
        is Section.MaterialNote -> materialBody(section)
    }

    // ——— УГРОЗА ———

    private val threatIntroTemplates: List<(ThreatFact) -> String> = listOf(
        { t -> "Если ничего не предпринять, ${SideLexicon.name(t.favours)} сыграют ${SanRuFormatter.move(t.opponentMoveSan)}." },
        { t -> "Стоит зазеваться — и ${SideLexicon.name(t.favours)} проведут ${SanRuFormatter.move(t.opponentMoveSan)}." },
        { t -> "Без ответа ${SideLexicon.name(t.favours)} тут же играют ${SanRuFormatter.move(t.opponentMoveSan)}." },
    )

    private val threatSeverityTemplates: List<(ThreatFact) -> String> = listOf(
        { "Угроза серьёзная." },
        { "Это уже ощутимая потеря." },
        { "Терпеть такое нельзя." },
    )

    private fun threatBody(
        threat: ThreatFact,
        pool: TemplatePool,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String = buildString {
        append(pool.choose(threat, threatIntroTemplates))
        if (threat.dropCentipawns >= CoachThresholds.THREAT_STRONG_CP) {
            append(" ")
            append(pool.choose(threat, threatSeverityTemplates))
        }
        // Не любой факт, который сработал в позиции после хода соперника,
        // объясняет, чем этот ход опасен — отсталая пешка или собственная
        // подвижность тут ни при чём. Опасность — это то, что реально
        // сыпется: материал, король, мат.
        val dangers = threat.consequences.filter { explainsDanger(it) }
        statementsFor(dangers, forkedSquares, glossary, memory, MAX_THREAT_CONSEQUENCES).forEach {
            append(" ")
            append(it)
        }
    }

    private fun explainsDanger(fact: Fact): Boolean = when (fact) {
        is HangingPieceFact, is TrappedPieceFact, is ForkFact, is PinFact, is SkewerFact,
        is DiscoveredAttackFact, is BackRankFact, is OverloadedDefenderFact, is MateFact,
        -> true
        else -> false
    }

    // ——— ЛУЧШИЙ ХОД / СЫГРАННЫЙ ХОД ———

    private fun whyThisMoveBody(
        section: Section.WhyThisMove,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String? {
        val lines = section.reasons.mapNotNull { reasonSentence(it, glossary, memory, forkedSquares) }
        if (lines.isEmpty()) return null
        // Нумеруем после фильтрации, а не до: пункт, который весь оказался
        // повтором и исчез, не должен оставлять дыру вида "1) ... 3) ...".
        return lines.mapIndexed { index, line -> "${index + 1}) $line" }.joinToString(System.lineSeparator())
    }

    /** Один-два самых весомых факта тира, а не все подряд — пункт должен звучать как фраза тренера, а не как список. */
    private fun reasonSentence(reason: Reason, glossary: Glossary, memory: RenderMemory, forkedSquares: Set<Square>): String? {
        val sentences = statementsFor(reason.facts, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON)
        if (sentences.isEmpty()) return null
        return sentences.joinToString(" ")
    }

    // ——— ПРОФИЛАКТИКА ———

    private val prophylaxisTemplates: List<(ProphylaxisFact) -> String> = listOf(
        { f -> "Заодно снимает угрозу ${SanRuFormatter.move(f.preventedMoveSan)}: до хода она была реальной, теперь её нет." },
        { f -> "Заново это лишает соперника ${SanRuFormatter.move(f.preventedMoveSan)} — ход больше не работает." },
        { f -> "Заодно ход разряжает ${SanRuFormatter.move(f.preventedMoveSan)}, о котором стоило беспокоиться." },
    )

    private fun prophylaxisBody(fact: ProphylaxisFact, pool: TemplatePool): String = pool.choose(fact, prophylaxisTemplates)

    // ——— ПЛАН ———

    private fun planBody(section: Section.Plan, glossary: Glossary, memory: RenderMemory, forkedSquares: Set<Square>): String? {
        val lines = section.steps.mapNotNull { planStepSentence(it, glossary, memory, forkedSquares) }
        if (lines.isEmpty()) return null
        return lines.joinToString(System.lineSeparator())
    }

    private fun planStepSentence(step: PlanStep, glossary: Glossary, memory: RenderMemory, forkedSquares: Set<Square>): String? {
        val facts = statementsFor(step.facts, forkedSquares, glossary, memory)
        if (facts.isEmpty()) return null
        // factStatement собирает самостоятельное предложение с заглавной буквы;
        // тут оно идёт придаточным после двоеточия, поэтому строчная уместнее.
        return "${planIdeaPhrase(step.idea)}: ${facts.joinToString(" ") { decapitalize(it) }}"
    }

    private fun planIdeaPhrase(idea: PlanIdea): String = when (idea) {
        PlanIdea.OCCUPY_OUTPOST -> "Держать фигуру на форпосте"
        PlanIdea.OPEN_FILE_FOR_ROOK -> "Держать ладью на открытой линии"
        PlanIdea.ADVANCE_PASSED_PAWN -> "Двигать проходную пешку вперёд"
        PlanIdea.ATTACK_KING -> "Продолжать давление на короля"
        PlanIdea.TARGET_WEAK_PAWN -> "Давить на слабую пешку соперника"
        PlanIdea.IMPROVE_WORST_PIECE -> "Улучшать положение худшей фигуры"
        PlanIdea.CENTRALISE -> "Централизовать фигуры"
        PlanIdea.TRADE_INTO_ENDGAME -> "Стремиться к выгодному эндшпилю через размены"
        PlanIdea.PREVENT_BREAK -> "Не пускать подрыв соперника"
    }

    // ——— ЕСЛИ СЫГРАТЬ ИНАЧЕ (ловушка) ———

    private fun trapBody(section: Section.Trap, mover: Side): String {
        val moveRu = SanRuFormatter.move(section.moveSan)
        val refutationRu = SanRuFormatter.line(section.refutationSan)
        return "$moveRu выглядит естественно, но после $refutationRu ${verdictPhrase(section.verdict, mover)}."
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
            val moveRu = SanRuFormatter.move(line.moveSan)
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

    // ——— ЧТО БУДЕТ ДАЛЬШЕ ———

    private fun continuationBody(section: Section.ExpectedContinuation, explanation: PositionExplanation): String? {
        if (section.movesSan.isEmpty()) return null
        val moveNumber = MoveNotation.fullMoveNumber(explanation.fen)
        val sequence = formatMoveSequence(moveNumber, explanation.sideToMove, section.movesSan)
        return "Скорее всего: $sequence"
    }

    /** Оформление вариантов в привычной записи партии: номер хода только перед ходом белых или в самом начале. */
    private fun formatMoveSequence(startMoveNumber: Int, startSide: Side, sanMoves: List<String>): String {
        var moveNumber = startMoveNumber
        var side = startSide
        val parts = mutableListOf<String>()

        sanMoves.forEachIndexed { index, san ->
            val translated = SanRuFormatter.move(san)
            val prefix = when {
                side == Side.WHITE -> "$moveNumber."
                index == 0 -> "$moveNumber..."
                else -> ""
            }
            parts += prefix + translated
            if (side == Side.BLACK) moveNumber += 1
            side = side.flip()
        }

        return parts.joinToString(" ")
    }

    // ——— МАТЕРИАЛ ———

    /** До скольки сантипешек перевес ещё называется "лишняя пешка", а не "лишнее качество". */
    private const val PAWN_MAGNITUDE_CP = 150

    /** До скольки сантипешек перевес называется "лишнее качество", а не "заметный материальный перевес". */
    private const val EXCHANGE_MAGNITUDE_CP = 300

    /**
     * Раздел — про баланс материала, а не про то, что где-то что-то висит:
     * повисшая фигура уже названа в другом месте разбора, и здесь её видно
     * только как источник этого самого баланса, никогда — отдельной фразой
     * "Слон на d3 потерян". Заголовок соразмерен величине разницы, а не
     * зашит одной фразой на все случаи: "оценка +6.84" и "заметный
     * материальный перевес" рядом читались как противоречие, хотя оба были
     * правдой — просто перевес там был ровно в одну пешку.
     */
    private fun materialBody(section: Section.MaterialNote): String {
        val side = SideLexicon.name(if (section.centipawnsFromWhite > 0) Side.WHITE else Side.BLACK, GrammaticalCase.GENITIVE)
        val magnitude = kotlin.math.abs(section.centipawnsFromWhite)
        return when {
            magnitude <= PAWN_MAGNITUDE_CP -> "У $side лишняя пешка."
            magnitude <= EXCHANGE_MAGNITUDE_CP -> "У $side лишнее качество."
            else -> "Заметный материальный перевес у $side."
        }
    }

    // ——— Дедупликация и отбор фактов ———

    /** Все факты разбора разом — чтобы вилка поглощала свои цели по всему тексту, а не только в своём пункте. */
    private fun collectAllFacts(explanation: PositionExplanation): List<Fact> = explanation.sections.flatMap { section ->
        when (section) {
            is Section.Threat -> section.threat.consequences
            is Section.WhyThisMove -> section.reasons.flatMap { it.facts }
            is Section.Plan -> section.steps.flatMap { it.facts }
            is Section.MaterialNote -> section.facts
            is Section.KingSafetyNote -> listOf(section.fact)
            is Section.Prophylaxis, is Section.Trap, is Section.Alternatives, is Section.ExpectedContinuation -> emptyList()
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
            val gender = PieceLexicon.genderOf(pieceTypeOf(fact.piece))
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            "$name на ${sq(fact.square)} ${PieceLexicon.Agreements.LOST.agree(gender)}."
        }

        is TrappedPieceFact -> {
            val gender = PieceLexicon.genderOf(pieceTypeOf(fact.piece))
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            "$name на ${sq(fact.square)} ${PieceLexicon.Agreements.TRAPPED.agree(gender)} — любой её ход теряет материал."
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

        is MateFact -> "Мат в ${fact.movesToMate} ${movesWord(fact.movesToMate)}."

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
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            "$name на ${sq(fact.square)} стоит форпостом — пешкой её оттуда не согнать."
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
            val castling = if (fact.castled) "" else " Король ещё не рокирован."
            val undeveloped = if (fact.undeveloped.isEmpty()) {
                ""
            } else {
                " Не вышли в игру фигуры на ${russianList(fact.undeveloped.map { sq(it) })}."
            }
            ("Развитие отстаёт.$castling$undeveloped").trim()
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
    }

    // ——— Мелкие помощники ———

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
        text.replaceFirstChar { if (it.isUpperCase()) it.lowercase() else it.toString() }

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
