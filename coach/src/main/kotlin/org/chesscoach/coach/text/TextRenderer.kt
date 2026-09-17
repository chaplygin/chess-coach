package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
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

    /** "Один-два самых весомых" — сколько фактов показываем в одном пункте причины, остальное отбрасываем. */
    private const val MAX_FACTS_PER_REASON = 2

    /** В угрозе — сам ход соперника и не больше двух следствий, объясняющих опасность. */
    private const val MAX_THREAT_CONSEQUENCES = 2

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

    /** Шкала для масштаба угрозы — в цене фигур, а не в сантипешках. */
    private const val MINOR_SCALE_CP = 300
    private const val ROOK_SCALE_CP = 500
    private const val QUEEN_SCALE_CP = 900

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
            return "ВАШ ВАРИАНТ: " + SanRuFormatter.move(section.playedSan) +
                System.lineSeparator() + "Это и есть лучший ход."
        }

        val title = "ВАШ ВАРИАНТ: " + SanRuFormatter.move(section.playedSan) + " — " +
            classificationLabel(section.classification) + damageMark(section)
        return title + System.lineSeparator() + comparisonBody(section, "Лучше", "Ваш ход")
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
        is Section.PositionAssessment -> "ОЦЕНКА ПОЗИЦИИ"
        is Section.MyPosition -> "МОЯ ПОЗИЦИЯ"
        is Section.MyAttack -> "МОЯ АТАКА"
        is Section.WhyThisMove ->
            "ЛУЧШИЙ ХОД: ${SanRuFormatter.move(section.moveSan)}${playedMatchMark(explanation)}"
        is Section.PlayedMove ->
            "СЫГРАННЫЙ ХОД: ${SanRuFormatter.move(section.playedSan)} — " +
                classificationLabel(section.classification) + damageMark(section)
        is Section.Prophylaxis -> "ПРОФИЛАКТИКА"
        is Section.Trap -> "ЕСЛИ СЫГРАТЬ ИНАЧЕ"
        is Section.Alternatives -> "АЛЬТЕРНАТИВЫ"
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
        val phrase = section.punishment
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
    ): String? = when (section) {
        is Section.PositionAssessment -> positionAssessmentBody(section)
        is Section.MyPosition -> myPositionBody(section, pool, glossary, memory, forkedSquares)
        is Section.MyAttack -> myAttackBody(section, glossary, memory, forkedSquares)
        is Section.WhyThisMove -> whyThisMoveBody(section, glossary, memory, forkedSquares)
        is Section.PlayedMove -> playedMoveBody(section, explanation)
        is Section.Prophylaxis -> prophylaxisBody(section.fact, pool)
        is Section.Trap -> trapBody(section, explanation.sideToMove)
        is Section.Alternatives -> alternativesBody(section)
    }

    // ——— ОЦЕНКА ПОЗИЦИИ ———

    private fun positionAssessmentBody(section: Section.PositionAssessment): String {
        val lines = mutableListOf<String>()
        // Дебют первой строкой: он отвечает на вопрос "куда я вообще попал",
        // а материал и характер позиции — уже на "как тут дела".
        section.opening?.takeIf { it.isNotBlank() }?.let { lines += "Дебют: $it." }
        lines += materialAssessmentLine(section)
        lines += characterLine(section.character)
        ideasLine(section.ideas)?.let { lines += it }
        return lines.joinToString(System.lineSeparator())
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

    private fun characterLine(character: PositionCharacter): String = when (character) {
        PositionCharacter.OPEN -> "Позиция открытая: фигурам есть где развернуться."
        PositionCharacter.CLOSED -> "Позиция закрытая: пешечные цепи сцеплены, играть приходится вокруг них."
        PositionCharacter.SEMI_OPEN -> "Позиция полуоткрытая: не нараспашку, но и не заперта наглухо."
    }

    private fun ideasLine(ideas: List<StrategicIdea>): String? {
        if (ideas.isEmpty()) return null
        return "Идея: " + ideas.joinToString(", ") { ideaPhrase(it) } + "."
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

    // ——— МОЯ ПОЗИЦИЯ / МОЯ АТАКА ———

    /**
     * Все пункты печатаются всегда, даже пустые.
     *
     * "Мат: нет" — это ответ, а пропущенная строка выглядит так, будто тренер
     * про мат забыл. Читателю нужен одинаковый список от позиции к позиции,
     * иначе он не знает, что именно проверено, а что нет.
     */
    private fun myPositionBody(
        section: Section.MyPosition,
        pool: TemplatePool,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String = listOf(
        labelledLine("Безопасность короля", section.kingSafety, forkedSquares, glossary, memory),
        labelledLine("Защита от взятий", section.loosePieces, forkedSquares, glossary, memory),
        threatLine(section.threat, pool, glossary, memory, forkedSquares),
        planLine("План соперника", section.opponentPlan, glossary, forkedSquares, forOpponent = true),
    ).joinToString(System.lineSeparator())

    private fun myAttackBody(
        section: Section.MyAttack,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String = listOf(
        labelledLine("Мат", section.mate, forkedSquares, glossary, memory),
        labelledLine("Взятия", section.captures, forkedSquares, glossary, memory),
        labelledLine("Активизация", section.activity, forkedSquares, glossary, memory),
        planLine("Мой план", section.plan, glossary, forkedSquares, forOpponent = false),
    ).joinToString(System.lineSeparator())

    private fun labelledLine(
        label: String,
        facts: List<Fact>,
        forkedSquares: Set<Square>,
        glossary: Glossary,
        memory: RenderMemory,
    ): String = sentencesLine(label, statementsFor(facts, forkedSquares, glossary, memory, MAX_FACTS_PER_REASON))

    /**
     * "{Метка}: {предложения}" одной строкой, либо "{Метка}: нет."
     *
     * Первая буква строчная: после двоеточия начинается не новое предложение, а
     * продолжение строки, и заглавная там читается как опечатка.
     */
    private fun sentencesLine(label: String, sentences: List<String>): String {
        if (sentences.isEmpty()) return "$label: нет."
        return "$label: " + decapitalize(sentences.joinToString(" "))
    }

    /** Угроза живёт пунктом "моей позиции": это ответ на вопрос "что мне грозит", а не отдельная новость. */
    private fun threatLine(
        threat: ThreatFact?,
        pool: TemplatePool,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String {
        if (threat == null) return "Угроза: нет."

        val parts = mutableListOf(pool.choose(threat, threatIntroTemplates))
        if (threat.dropCentipawns >= CoachThresholds.THREAT_STRONG_CP) {
            // Точку у шаблона снимаем и ставим заново после скобки: оценка
            // "серьёзная" без масштаба — это интонация, а не информация, и
            // читатель вправе спросить, насколько серьёзная.
            val severity = pool.choose(threat, threatSeverityTemplates).removeSuffix(".")
            parts += severity + " (" + threatScale(threat) + ")."
        }
        // Не любой факт, который сработал после хода соперника, объясняет, чем
        // этот ход опасен: отсталая пешка или чужая подвижность тут ни при чём.
        // Опасность — это то, что реально сыпется: материал, король, мат.
        parts += statementsFor(
            threat.consequences.filter { explainsDanger(it) },
            forkedSquares,
            glossary,
            memory,
            MAX_THREAT_CONSEQUENCES,
        )

        return sentencesLine("Угроза", parts)
    }

    /**
     * Насколько велика угроза — словами, а не в сантипешках.
     *
     * Число просадки в тексте разбора не появляется никогда (см. контракт
     * класса), но и молчать о масштабе нельзя: "угроза серьёзная" без меры
     * звучит одинаково и когда теряется пешка, и когда ферзь. Меру даём
     * привычной шахматисту шкалой — в фигурах.
     */
    private fun threatScale(threat: ThreatFact): String {
        if (threat.evidence.engineLine?.score is Score.Mate) return "дело идёт к мату"
        return when {
            threat.dropCentipawns >= QUEEN_SCALE_CP -> "уровень ферзя"
            threat.dropCentipawns >= ROOK_SCALE_CP -> "уровень ладьи"
            threat.dropCentipawns >= MINOR_SCALE_CP -> "уровень лёгкой фигуры"
            threat.dropCentipawns >= PAWN_MAGNITUDE_CP -> "больше пешки"
            else -> "примерно пешка"
        }
    }

    /**
     * План — итог уже сказанного, поэтому он единственный освобождён от запрета
     * на повтор: сводка обязана повторять то, что прозвучало выше, иначе она
     * пустеет ровно там, где полезна. Отсюда своя, чистая [RenderMemory].
     */
    private fun planLine(
        label: String,
        steps: List<PlanStep>,
        glossary: Glossary,
        forkedSquares: Set<Square>,
        forOpponent: Boolean,
    ): String {
        val memory = RenderMemory()
        // Шаги с одной и той же идеей сливаем в одну фразу. Иначе план на три
        // слабые пешки выглядит как заевшая пластинка: "Давить на слабую пешку
        // соперника" трижды подряд, меняется только поле.
        val sentences = steps
            .groupBy { it.idea }
            .mapNotNull { (idea, group) ->
                planStepSentence(idea, group.flatMap { it.facts }, glossary, memory, forkedSquares, forOpponent)
            }
        return sentencesLine(label, sentences)
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

    private fun explainsDanger(fact: Fact): Boolean = when (fact) {
        is HangingPieceFact, is TrappedPieceFact, is ForkFact, is PinFact, is SkewerFact,
        is DiscoveredAttackFact, is BackRankFact, is OverloadedDefenderFact, is MateFact,
        -> true
        else -> false
    }

    // ——— ЛУЧШИЙ ХОД ———

    private fun whyThisMoveBody(
        section: Section.WhyThisMove,
        glossary: Glossary,
        memory: RenderMemory,
        forkedSquares: Set<Square>,
    ): String? {
        val lines = section.reasons.mapNotNull { reasonSentence(it, glossary, memory, forkedSquares) }
        if (lines.isEmpty() && !section.pointIsInTheLine) return null

        // Нумеруем после фильтрации, а не до: пункт, который весь оказался
        // повтором и исчез, не должен оставлять дыру вида "1) ... 3) ...".
        val numbered = lines.mapIndexed { index, line -> (index + 1).toString() + ") " + line }

        // Признаки молчат, а ход всё равно заметно сильнее остальных. Выдавать
        // в таком случае открытую линию за причину отдать ладью — значит
        // соврать уверенным тоном, поэтому отсылаем к варианту.
        val caveat = if (section.pointIsInTheLine) {
            listOf("Одними признаками позиции этот ход не объясняется — его смысл раскрывается в варианте ниже.")
        } else {
            emptyList()
        }

        val opening = section.openingCaveat
            ?.let { openingCaveatLines(it, section.reasons) }
            ?: emptyList()

        return (numbered + caveat + opening).joinToString(System.lineSeparator())
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
            return "Сыгран лучший ход."
        }

        return comparisonBody(section, "Лучше было", "Сыгранный ход")
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

        if (missedPhrases.isEmpty()) return betterLine + refutationSentence(section)

        // Запятые без союза: сама именная группа уже может содержать "и"
        // ("вилку на d1 и e2"), и обычное перечисление дало бы "и e2 и связку".
        return betterLine + " " + subject + " упускает " + missedPhrases.joinToString(", ") + "." +
            refutationSentence(section)
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

    /** Запасная причина, когда признаки молчат: падение оценки известно всегда. */
    private fun lossPhrase(centipawnLoss: Int): String =
        if (centipawnLoss >= COLLAPSE_CP) {
            "оценка рушится"
        } else {
            "оценка падает на " + String.format(Locale.ROOT, "%.1f", centipawnLoss / 100.0)
        }

    /**
     * Чего лишился сыгранный ход — короткой именной группой в винительном
     * падеже, чтобы вставать после "упускает".
     *
     * Null для признаков, потеря которых не описывается одним словом: про них
     * лучше промолчать, чем выдумывать формулировку.
     */
    private fun missedPhrase(fact: Fact): String? = when (fact) {
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
        forOpponent: Boolean,
    ): String? {
        // План перечисляет цели, а не пересказывает признаки: три подряд
        // предложения "пешка на d5 изолирована" отличаются одним полем и
        // читаются как заевшая пластинка.
        val targets = facts.mapNotNull(::planTarget).distinct()
        if (targets.size == facts.size) {
            return planIdeaPhrase(idea, targets.size, forOpponent) + ": " + russianList(targets) + "."
        }

        val statements = statementsFor(facts, forkedSquares, glossary, memory)
        if (statements.isEmpty()) return null
        // factStatement собирает самостоятельное предложение с заглавной буквы;
        // тут оно идёт придаточным после двоеточия, поэтому строчная уместнее.
        return planIdeaPhrase(idea, statements.size, forOpponent) + ": " +
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
     * фразе. [forOpponent] переворачивает притяжательные слова: под заголовком
     * "План соперника" фраза "давить на слабые пешки соперника" означала бы,
     * что соперник давит сам на себя.
     */
    private fun planIdeaPhrase(idea: PlanIdea, count: Int, forOpponent: Boolean): String = when (idea) {
        PlanIdea.OCCUPY_OUTPOST ->
            if (count > 1) "Держать фигуры на форпостах" else "Держать фигуру на форпосте"

        PlanIdea.OPEN_FILE_FOR_ROOK ->
            if (count > 1) "Держать ладьи на открытых линиях" else "Держать ладью на открытой линии"

        PlanIdea.ADVANCE_PASSED_PAWN ->
            if (count > 1) "Двигать проходные пешки вперёд" else "Двигать проходную пешку вперёд"

        PlanIdea.TARGET_WEAK_PAWN -> when {
            forOpponent && count > 1 -> "Давить на мои слабые пешки"
            forOpponent -> "Давить на мою слабую пешку"
            count > 1 -> "Давить на слабые пешки соперника"
            else -> "Давить на слабую пешку соперника"
        }

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

    // ——— Дедупликация и отбор фактов ———

    /** До скольки сантипешек перевес ещё называется "лишняя пешка", а не "лишнее качество". */
    private const val PAWN_MAGNITUDE_CP = 150

    /** До скольки сантипешек перевес называется "лишнее качество", а не "заметный материальный перевес". */
    private const val EXCHANGE_MAGNITUDE_CP = 300

    /** Все факты разбора разом — чтобы вилка поглощала свои цели по всему тексту, а не только в своём пункте. */
    private fun collectAllFacts(explanation: PositionExplanation): List<Fact> = explanation.sections.flatMap { section ->
        when (section) {
            is Section.PositionAssessment -> emptyList()
            is Section.MyPosition ->
                section.kingSafety + section.loosePieces +
                    section.opponentPlan.flatMap { it.facts } +
                    (section.threat?.consequences ?: emptyList())

            is Section.MyAttack ->
                section.mate + section.captures + section.activity + section.plan.flatMap { it.facts }

            is Section.WhyThisMove -> section.reasons.flatMap { it.facts }
            is Section.PlayedMove -> section.missed + section.punishment
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
            val gender = PieceLexicon.genderOf(pieceTypeOf(fact.piece))
            val name = capitalize(PieceLexicon.name(pieceTypeOf(fact.piece)))
            "$name на ${sq(fact.square)} ${PieceLexicon.Agreements.LOST.agree(gender)}${hangingReason(fact)}."
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
        // "Защиты нет" и "размен не выручает" — разные вещи: во втором случае
        // взять обратно есть чем, но размен всё равно проигран, и советовать
        // "защити фигуру" было бы вредным советом.
        val verdict = if (fact.defended) "размен не выручает" else "защиты нет"
        return " (бьёт $attackerName ${sq(attacker)}, $verdict)"
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
