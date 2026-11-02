package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.engine.NullMoveUtil
import org.chesscoach.coach.model.AlternativeLine
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.CheckedAdvice
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.HumanMove
import org.chesscoach.coach.model.DevelopmentFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.ExchangeFact
import org.chesscoach.coach.model.EndgameFact
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.ForkThreatFact
import org.chesscoach.coach.model.PawnBreakFact
import org.chesscoach.coach.model.RemoveDefenderFact
import org.chesscoach.coach.model.WeakSquareFact
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OpenFileRookFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.ThreatFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat

/**
 * Единственная точка сборки разбора позиции.
 *
 * Сама ничего не выдумывает и не пересчитывает оценку: каждое число либо
 * приходит от движка дословно, либо посчитано уже готовым анализатором.
 * Здесь только порядок обращений к движку и решение, какие секции попали в
 * список — раздел без доказательств просто не появляется, вместо пустого
 * заголовка.
 */
class PipelineOrchestrator(
    private val engineClient: EngineClient,
    private val featureSource: FeatureSource,
) {
    private val threatAnalyzer = ThreatAnalyzer(engineClient, featureSource)
    private val reasonDiffAnalyzer = ReasonDiffAnalyzer(engineClient, featureSource)
    private val trapSelector = TrapSelector(engineClient)
    private val lineAnalyzer = LineAnalyzer(featureSource)

    private companion object {
        /** Насколько ход должен превосходить второй по силе, чтобы молчание детекторов стало подозрительным. */
        const val UNEXPLAINED_EDGE_CP = 150

        /** Сколько полуходов варианта показывать: дальше он уже не читается. */
        const val LINE_PLIES = 5

        /** Вариант разобранного хода длиннее: он и есть объяснение. */
        const val PLAYED_LINE_PLIES = 6

        /** С этой потери ход не называем советом: разница больше неточности. */
        const val ADVICE_MAX_LOSS_CP = 50

        const val MAX_TEMPTING_CAPTURES = 2
        const val TEMPTING_LINE_INDEX = 99
        val ADVICE_LIMITS = CoachSearchLimits(movetimeMs = 250, multiPv = 1)
    }

    suspend fun explain(
        fen: String,
        playedMoveUci: String? = null,
        opening: String? = null,
    ): PositionExplanation {
        val sideToMove = NullMoveUtil.sideToMoveOf(fen)
        val featuresBefore = featureSource.features(fen)

        // 1. Основной поиск: он даёт лучший ход, альтернативы и саму оценку позиции.
        val mainLines = engineClient
            .analyse(fen, CoachSearchLimits(movetimeMs = 900, multiPv = 3))
            .sortedBy { it.multiPvIndex }
        val bestLine = mainLines.firstOrNull()

        val bestMoveUci = bestLine?.pv?.firstOrNull()
        val bestMoveSan = bestMoveUci?.let { MoveNotation.uciToSanOrRaw(fen, listOf(it)).first() } ?: ""
        val scoreFromWhite = bestLine?.score?.let { ScoreFormat.toWhitePerspective(it, sideToMove) }

        val sections = mutableListOf<Section>()

        // 2. Нулевой ход: что соперник сделал бы, будь ход его. Считается до
        // сборки разделов, потому что угроза — это пункт внутри "моей позиции",
        // а не самостоятельная новость где-то ниже по тексту.
        val threat = bestLine?.let { threatAnalyzer.analyse(fen, it.score) }

        // 3. Оценка позиции и расклад "моя позиция / моя атака" — без движка,
        // прямо из уже посчитанных признаков. Это первое, что читает человек,
        // поэтому идёт в списке раньше конкретного хода.
        bestLine?.let { lineAnalyzer.analyse(fen, it) }?.let { sections += it }
        sections += PositionAssessor.assess(fen, featuresBefore, sideToMove, opening)
        sections += myPositionSection(fen, featuresBefore, sideToMove, threat, opponentAdvice(fen, featuresBefore, sideToMove))
        sections += myAttackSection(fen, featuresBefore, sideToMove, ownAdvice(fen, featuresBefore, sideToMove, bestLine))

        // 4. Почему лучший ход хорош, профилактика и разбор сыгранного хода
        // в сравнении с лучшим.
        var classification: MoveClassification? = null
        if (bestLine != null && bestMoveUci != null) {
            val bestDiff = reasonDiffAnalyzer.analyse(fen, bestMoveUci, bestLine.score)
            bestDiff.whyThisMove
                ?.let {
                    it.copy(
                        pointIsInTheLine = pointIsInTheLine(it, bestLine, mainLines),
                        // Взятие фигуры дебютному правилу не перечит: оговорка тут только сбила бы.
                        openingCaveat = OpeningPrinciples.caveat(fen, bestMoveUci)
                            .takeUnless { isCapture(fen, bestMoveUci, sideToMove) },
                    )
                }
                ?.let { sections += it }

            if (playedMoveUci != null) {
                // Если сыгран лучший ход, второй раз спрашивать движок незачем —
                // диф уже посчитан выше.
                val playedDiff = if (playedMoveUci == bestMoveUci) {
                    bestDiff
                } else {
                    reasonDiffAnalyzer.analyse(fen, playedMoveUci, bestLine.score)
                }

                val evaluation = evaluatePlayedMove(
                    bestScore = bestLine.score,
                    playedMoveUci = playedMoveUci,
                    bestMoveUci = bestMoveUci,
                    mainLines = mainLines,
                    fallbackPlayedScore = playedDiff.moveScoreFromMover,
                )

                if (evaluation != null) {
                    classification = evaluation.classification

                    // Чего лишился сыгранный ход по сравнению с лучшим: факты,
                    // которые дал бы лучший ход, но не дал сыгранный. Когда ходы
                    // совпадают, playedDiff — это тот же самый bestDiff, и
                    // разница закономерно пустая.
                    val bestFacts = bestDiff.whyThisMove?.reasons?.flatMap { it.facts } ?: emptyList()
                    val playedFacts = playedDiff.whyThisMove?.reasons?.flatMap { it.facts } ?: emptyList()
                    val missed = bestFacts.filterNot { it in playedFacts }

                    val playedSan = MoveNotation.uciToSanOrRaw(fen, listOf(playedMoveUci)).first()
                    val playedPv = mainLines.firstOrNull { it.pv.firstOrNull() == playedMoveUci }?.pv
                        ?: (listOf(playedMoveUci) + playedDiff.replyPv)
                    val playedLine = playedPv.take(PLAYED_LINE_PLIES)
                    val playedScore = playedScoreOf(
                        bestLine.score,
                        playedMoveUci,
                        bestMoveUci,
                        mainLines,
                        playedDiff.moveScoreFromMover,
                    )
                    sections += Section.PlayedMove(
                        scoreAfterFromWhite = playedScore?.let { ScoreFormat.toWhitePerspective(it, sideToMove) },
                        bestScoreFromWhite = scoreFromWhite,
                        lineSan = MoveNotation.uciToSanOrRaw(fen, playedLine),
                        lineUci = playedLine,
                        materialSwing = LineMaterial.swing(fen, playedLine, sideToMove),
                        lineGains = lineGains(fen, playedLine, sideToMove),
                        playedSan = playedSan,
                        playedUci = playedMoveUci,
                        bestSan = bestMoveSan,
                        bestUci = bestMoveUci,
                        classification = evaluation.classification,
                        centipawnLoss = evaluation.centipawnLoss,
                        missed = missed,
                        // Когда сыгран лучший ход, наказывать не за что: его
                        // "опровержение" — это просто лучший ответ соперника в
                        // нормальной позиции, и подавать его как расплату
                        // значило бы пугать читателя на ровном месте.
                        refutationSan = playedDiff.replyMoveSan.takeIf { playedMoveUci != bestMoveUci },
                        punishment = if (playedMoveUci == bestMoveUci) emptyList() else playedDiff.opponentGains,
                    )
                }
            }

            // Профилактика — третий тир причин, и обычно она уже стоит пунктом
            // в списке "почему этот ход". Отдельный раздел тогда повторяет то
            // же самое другими словами, и разбор выглядит так, будто тренер
            // забыл, что уже это сказал.
            val alreadyAmongReasons = bestDiff.whyThisMove
                ?.reasons
                ?.any { reason -> reason.facts.any { it === bestDiff.prophylaxis } }
                ?: false
            if (!alreadyAmongReasons) {
                bestDiff.prophylaxis?.let { sections += Section.Prophylaxis(it) }
            }
        }

        // 5-6. Опровержение ловушки и быстрый поиск малой глубины — внутри TrapSelector,
        // и только если вообще есть из чего выбирать (нужна хотя бы одна альтернатива лучшему ходу).
        var quickLines = emptyList<EngineLine>()
        if (bestLine != null && mainLines.size > 1) {
            quickLines = engineClient.analyse(fen, CoachSearchLimits(movetimeMs = 50, depth = 4, multiPv = 3))
            val tempting = temptingCaptures(fen, sideToMove, bestMoveUci, playedMoveUci, mainLines)
            trapSelector.select(fen, sideToMove, mainLines + tempting, quickLines, playedMoveUci)?.let { sections += it }
        }

        if (bestLine != null && bestMoveUci != null) {
            sections += Section.BestMoves(
                computerLineSan = MoveNotation.uciToSanOrRaw(fen, bestLine.pv.take(LINE_PLIES)),
                human = humanMove(fen, bestLine, mainLines, quickLines),
                computerLineUci = bestLine.pv.take(LINE_PLIES),
            )
        }

        if (bestLine != null) {
            alternativesSection(fen, sideToMove, bestLine, mainLines)?.let { sections += it }
        }

        return PositionExplanation(
            fen = fen,
            sideToMove = sideToMove,
            scoreFromWhite = scoreFromWhite,
            bestMoveSan = bestMoveSan,
            bestMoveUci = bestMoveUci ?: "",
            sections = sections.sortedBy(::readingOrder),
            featuresBefore = featuresBefore,
            playedMoveSan = playedMoveUci?.let { MoveNotation.uciToSanOrRaw(fen, listOf(it)).first() },
            classification = classification,
        )
    }

    /**
     * МОЯ ПОЗИЦИЯ: за что могут наказать прямо сейчас — с точки зрения
     * стороны, чей сейчас ход.
     *
     * `favours` у факта — это сторона, которой он на пользу (см. [Fact]), а не
     * его владелец: моя повисшая фигура на пользу сопернику, поэтому
     * "мои" факты ищутся через `favouring(mover.flip())`, а не через
     * фильтрацию по стороне фигуры.
     */
    private fun myPositionSection(
        fen: String,
        features: FeatureSet,
        mover: Side,
        threat: ThreatFact?,
        advice: List<CheckedAdvice>,
    ): Section {
        val opponent = mover.flip()
        val kingSafety = features.pick<KingSafetyFact>().filter { it.kingSide == mover } +
            features.favouring(opponent).filter(::isActiveIdea)
        val loosePieces = features.favouring(opponent).filter {
            it is HangingPieceFact || it is BackRankFact || it is RemoveDefenderFact
        }
        // Раздел собирается всегда, даже когда все четыре пункта пусты:
        // "Угроза: нет" — это ответ на вопрос читателя, а пропущенный раздел он
        // прочтёт как недосмотр тренера.
        return Section.MyPosition(
            kingSafety = kingSafety,
            loosePieces = loosePieces,
            threat = threat,
            opponentPlan = PlanBuilder.build(features, opponent),
            exchanges = features.favouring(opponent).filterIsInstance<ExchangeFact>(),
            checks = SafeChecks.of(fen, mover.flip()),
            advice = advice,
        )
    }

    /** МОЯ АТАКА: чем можно наказать соперника — те же факты, но на пользу мне, и к чему я веду дело. */
    private fun myAttackSection(
        fen: String,
        features: FeatureSet,
        mover: Side,
        advice: List<CheckedAdvice>,
    ): Section {
        val mine = features.favouring(mover)
        val mate = mine.filter { it is MateFact || it is BackRankFact }
        // Во "взятиях" только то, что можно забрать. Связка или удар следующим
        // ходом ничего пока не берут: это активные ходы.
        val captures = mine.filter {
            it is HangingPieceFact || it is ForkFact || it is RemoveDefenderFact
        }
        val tactics = mine.filter {
            it is PinFact || it is SkewerFact || it is DiscoveredAttackFact || it is OverloadedDefenderFact
        }
        val activity = tactics + mine.filter(::isActiveIdea) + mine.filter {
            it is MobilityEdgeFact || it is OutpostFact || it is OpenFileRookFact ||
                it is CenterControlFact || it is DevelopmentFact
        }
        return Section.MyAttack(
            mate = mate,
            captures = captures,
            activity = activity,
            plan = PlanBuilder.build(features, mover),
            exchanges = mine.filterIsInstance<ExchangeFact>(),
            checks = SafeChecks.of(fen, mover),
            advice = advice,
        )
    }

    /**
     * Свои удары следующим ходом. Детектор видит только узор на доске, поэтому
     * ход проверяет движок: потерю от [ADVICE_MAX_LOSS_CP] не советуем. Вилку,
     * которую сразу меняют, оставляем: тут важно увидеть сам размен.
     */
    private suspend fun ownAdvice(
        fen: String,
        features: FeatureSet,
        mover: Side,
        bestLine: EngineLine?,
    ): List<CheckedAdvice> = features.favouring(mover).filter(::isNextMoveAdvice).mapNotNull { fact ->
        val uci = adviceMove(fact) ?: return@mapNotNull null
        val after = applyUciMove(fen, uci) ?: return@mapNotNull null
        val score = when {
            bestLine == null -> null
            bestLine.pv.firstOrNull() == uci -> bestLine.score
            else -> engineClient.analyse(after, ADVICE_LIMITS).firstOrNull()?.let { ScoreFormat.negate(it.score) }
        }
        if (score == null || bestLine == null) return@mapNotNull CheckedAdvice(fact)
        val loss = ScoreFormat.centipawnLoss(bestLine.score, score)
        if (loss >= ADVICE_MAX_LOSS_CP && !isTraded(fact)) return@mapNotNull null
        CheckedAdvice(fact, ScoreFormat.toWhitePerspective(score, mover))
    }

    /** Удары соперника проверяются так же, но на доске с его очередью: оценку поэтому не показываем. */
    private suspend fun opponentAdvice(fen: String, features: FeatureSet, mover: Side): List<CheckedAdvice> {
        val opponent = mover.flip()
        val facts = features.favouring(opponent).filter(::isNextMoveAdvice)
        if (facts.isEmpty()) return emptyList()
        val flipped = NullMoveUtil.flipSideToMove(fen) ?: return facts.map { CheckedAdvice(it) }
        val best = engineClient.analyse(flipped, ADVICE_LIMITS).firstOrNull()
            ?: return facts.map { CheckedAdvice(it) }
        return facts.filter { fact ->
            val uci = adviceMove(fact)
            val after = uci?.let { applyUciMove(flipped, it) }
            when {
                uci == null || after == null -> false
                isTraded(fact) || best.pv.firstOrNull() == uci -> true
                else -> {
                    val reply = engineClient.analyse(after, ADVICE_LIMITS).firstOrNull()
                    reply == null || ScoreFormat.centipawnLoss(best.score, ScoreFormat.negate(reply.score)) < ADVICE_MAX_LOSS_CP
                }
            }
        }.map { CheckedAdvice(it) }
    }

    private fun isCapture(fen: String, uci: String, mover: Side): Boolean {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return false
        val move = runCatching { Move(uci, mover) }.getOrNull() ?: return false
        return board.getPiece(move.to) != Piece.NONE
    }

    /** Новые удары и позиционные плюсы соперника к концу варианта. Движок тут не нужен, только детекторы. */
    private fun lineGains(fen: String, line: List<String>, mover: Side): List<Fact> {
        val end = line.fold(fen) { current, uci -> applyUciMove(current, uci) ?: return emptyList() }
        val opponent = mover.flip()
        val known = featureSource.features(fen).favouring(opponent).map(::signature).toSet()
        return featureSource.features(end).favouring(opponent).filter { fact ->
            // Висящую фигуру тут не берём: в конце варианта её сторона ещё успевает сходить.
            signature(fact) !in known && (
                fact is ForkFact || fact is TrappedPieceFact || fact is BackRankFact || fact is MateFact ||
                    fact is KingSafetyFact || fact is OutpostFact || fact is PassedPawnFact || fact is WeakSquareFact
                )
        }
    }

    private fun isNextMoveAdvice(fact: Fact): Boolean = fact is ForkThreatFact || fact is PawnBreakFact

    private fun isTraded(fact: Fact): Boolean = fact is ForkThreatFact && fact.tradeLineSan.isNotEmpty()

    private fun adviceMove(fact: Fact): String? = when (fact) {
        is ForkThreatFact -> fact.from.value().lowercase() + fact.to.value().lowercase()
        is PawnBreakFact -> fact.from.value().lowercase() + fact.to.value().lowercase()
        else -> null
    }

    /**
     * Другие взятия на том же поле, что и лучший ход: забрать коня хочется чем
     * угодно, и движок мог не поставить такое взятие в тройку лучших. Сыгранный
     * ход пропускаем, он разбирается отдельно.
     */
    private suspend fun temptingCaptures(
        fen: String,
        mover: Side,
        bestUci: String?,
        playedUci: String?,
        mainLines: List<EngineLine>,
    ): List<EngineLine> {
        if (bestUci == null) return emptyList()
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return emptyList()
        val best = runCatching { Move(bestUci, mover) }.getOrNull() ?: return emptyList()
        if (board.getPiece(best.to) == Piece.NONE) return emptyList()

        val known = mainLines.mapNotNull { it.pv.firstOrNull() }.toSet() + listOfNotNull(playedUci)
        return board.legalMoves()
            .filter { it.to == best.to && it.toString() !in known }
            .take(MAX_TEMPTING_CAPTURES)
            .mapNotNull { move ->
                val after = applyUciMove(fen, move.toString()) ?: return@mapNotNull null
                val reply = engineClient.analyse(after, ADVICE_LIMITS).firstOrNull() ?: return@mapNotNull null
                EngineLine(
                    multiPvIndex = TEMPTING_LINE_INDEX,
                    score = ScoreFormat.negate(reply.score),
                    pv = listOf(move.toString()) + reply.pv,
                    depth = reply.depth,
                )
            }
    }

    /** Признаки, которые говорят, куда играть: идут первыми, потому что в пункте помещается два факта. */
    private fun isActiveIdea(fact: Fact): Boolean =
        fact is WeakSquareFact || fact is EndgameFact

    /**
     * «Человеческий» ход: первый ход поиска малой глубины, который основной
     * поиск оценил почти как лучший. Ход, замеченный при малой глубине, виден и
     * человеку; глубокий поиск отсекает ходы, которые только кажутся хорошими.
     */
    private suspend fun humanMove(
        fen: String,
        bestLine: EngineLine,
        mainLines: List<EngineLine>,
        quickLines: List<EngineLine>,
    ): HumanMove? {
        val bestUci = bestLine.pv.firstOrNull() ?: return null
        val quickFirst = quickLines.sortedBy { it.multiPvIndex }.firstOrNull()?.pv?.firstOrNull() ?: return null
        if (quickFirst == bestUci) return null
        val line = mainLines.firstOrNull { it.pv.firstOrNull() == quickFirst } ?: return null
        val loss = ScoreFormat.centipawnLoss(bestLine.score, line.score)
        if (loss > CoachThresholds.HUMAN_MOVE_MAX_LOSS_CP) return null

        val diff = reasonDiffAnalyzer.analyse(fen, quickFirst, bestLine.score)
        return HumanMove(
            lineSan = MoveNotation.uciToSanOrRaw(fen, line.pv.take(LINE_PLIES)),
            lossVsBestCentipawns = loss,
            facts = diff.whyThisMove?.reasons?.flatMap { it.facts } ?: emptyList(),
        )
    }

    private data class PlayedMoveEvaluation(val classification: MoveClassification, val centipawnLoss: Int)

    /** Null — движку нечем оценить сыгранный ход (совсем экзотическая позиция), классифицировать нечего. */
    private fun evaluatePlayedMove(
        bestScore: Score,
        playedMoveUci: String,
        bestMoveUci: String,
        mainLines: List<EngineLine>,
        fallbackPlayedScore: Score?,
    ): PlayedMoveEvaluation? {
        val playedScore = playedScoreOf(bestScore, playedMoveUci, bestMoveUci, mainLines, fallbackPlayedScore)
            ?: return null

        val cpLoss = ScoreFormat.centipawnLoss(bestScore, playedScore)
        val missedForcedMate = bestScore is Score.Mate && bestScore.movesToMate > 0 &&
            !(playedScore is Score.Mate && playedScore.movesToMate > 0)

        return PlayedMoveEvaluation(MoveClassification.of(cpLoss, missedForcedMate), cpLoss)
    }

    /**
     * Оценка сыгранного хода почти всегда уже известна: либо это лучший ход
     * (потери нет), либо один из показанных MultiPV-альтернатив (оценка есть
     * прямо в нём), и только если сыгранный ход выпал даже из тройки лучших —
     * пригождается запасной вариант, добытый в [ReasonDiffAnalyzer] через
     * подтверждающий поиск после этого самого хода.
     */
    private fun playedScoreOf(
        bestScore: Score,
        playedMoveUci: String,
        bestMoveUci: String,
        mainLines: List<EngineLine>,
        fallbackPlayedScore: Score?,
    ): Score? = when {
        playedMoveUci == bestMoveUci -> bestScore
        else -> mainLines.firstOrNull { it.pv.firstOrNull() == playedMoveUci }?.score ?: fallbackPlayedScore
    }

    /**
     * Ход заметно сильнее остальных, а объяснить его нечем, кроме мелочей.
     *
     * Так выглядят жертвы: детекторы видят после хода разве что открытую
     * линию, потому что смысл раскрывается через несколько полуходов. Выдать
     * открытую линию за причину отдать ладью — значит соврать читателю
     * уверенным тоном, поэтому в таком случае честнее отослать к варианту.
     */
    private fun pointIsInTheLine(
        why: Section.WhyThisMove,
        bestLine: EngineLine?,
        mainLines: List<EngineLine>,
    ): Boolean {
        if (bestLine == null || mainLines.size < 2) return false

        val weighty = setOf(
            ReasonTier.MATERIAL_TACTICS,
            ReasonTier.KING_SAFETY,
            ReasonTier.PROPHYLAXIS,
        )
        if (why.reasons.any { it.tier in weighty }) return false

        val second = mainLines.first { it !== bestLine }
        return ScoreFormat.centipawnLoss(bestLine.score, second.score) >= UNEXPLAINED_EDGE_CP
    }

    /**
     * Порядок чтения разбора.
     *
     * Задаётся отдельно от порядка сборки: движок опрашивается в том порядке,
     * в каком дешевле считать, а читать надо в том, в каком тренер объясняет.
     * Сначала общая картина (оценка позиции, что мне грозит, чем я отвечаю),
     * потом конкретный ход и разбор сыгранного.
     */
    private fun readingOrder(section: Section): Int = when (section) {
        is Section.EvalReason -> -1
        is Section.PositionAssessment -> 0
        is Section.MyPosition -> 1
        is Section.MyAttack -> 2
        is Section.BestMoves -> 3
        is Section.WhyThisMove -> 4
        is Section.PlayedMove -> 5
        is Section.Prophylaxis -> 6
        is Section.Trap -> 7
        is Section.Alternatives -> 8
    }

    private fun alternativesSection(
        fen: String,
        sideToMove: Side,
        bestLine: EngineLine,
        mainLines: List<EngineLine>,
    ): Section? {
        val alternatives = mainLines
            .filter { it.multiPvIndex != bestLine.multiPvIndex && it.pv.firstOrNull() != bestLine.pv.firstOrNull() }
            .distinctBy { it.pv.firstOrNull() }
        if (alternatives.isEmpty()) return null

        val lines = alternatives.mapNotNull { line ->
            val uci = line.pv.firstOrNull() ?: return@mapNotNull null
            val san = MoveNotation.uciToSanOrRaw(fen, listOf(uci)).first()
            val afterFirstFen = applyUciMove(fen, uci)
            val continuationSan = if (afterFirstFen != null && line.pv.size > 1) {
                MoveNotation.uciToSanOrRaw(afterFirstFen, line.pv.drop(1).take(3))
            } else {
                emptyList()
            }

            AlternativeLine(
                moveSan = san,
                scoreFromWhite = ScoreFormat.toWhitePerspective(line.score, sideToMove),
                lossVsBestCentipawns = ScoreFormat.centipawnLoss(bestLine.score, line.score),
                continuationSan = continuationSan,
            )
        }

        return if (lines.isEmpty()) null else Section.Alternatives(lines)
    }
}
