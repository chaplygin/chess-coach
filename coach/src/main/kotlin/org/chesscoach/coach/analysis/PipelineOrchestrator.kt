package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.model.CaptureCheck
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
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.ExchangeFact
import org.chesscoach.coach.model.Reason
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
import org.chesscoach.coach.model.PreviousMove
import org.chesscoach.coach.model.NaturalMove
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

        const val ADVICE_MAX_LOSS_CP = CoachThresholds.ADVICE_MAX_LOSS_CP

        /** Потеря к концу варианта, при которой пойманная фигура действительно теряется. */
        const val TRAPPED_LOSS_CP = 200

        /** Сколько полуходов лучшей линии смотреть, ища шах королю. */
        const val KING_TALK_PLIES = 5

        const val MAX_TEMPTING_CAPTURES = 2
        const val TEMPTING_LINE_INDEX = 99
        val ADVICE_LIMITS = CoachSearchLimits(movetimeMs = 250, multiPv = 1)
    }

    /**
     * [previousFen] и [previousMoveUci] — прошлый ход соперника, если он известен:
     * разбор начинается с того, зачем соперник так сыграл.
     */
    suspend fun explain(
        fen: String,
        playedMoveUci: String? = null,
        opening: String? = null,
        previousFen: String? = null,
        previousMoveUci: String? = null,
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
        val previous = previousMove(previousFen, previousMoveUci, fen, featuresBefore, sideToMove)
        sections += myPositionSection(fen, featuresBefore, sideToMove, threat, opponentAdvice(fen, featuresBefore, sideToMove), previous)
        sections += myAttackSection(fen, featuresBefore, sideToMove, ownAdvice(fen, featuresBefore, sideToMove, bestLine), bestLine)
            .let { attack -> attack.copy(captureChecks = captureChecks(fen, attack.captures, sideToMove, bestLine)) }

        // 4. Почему лучший ход хорош, профилактика и разбор сыгранного хода
        // в сравнении с лучшим.
        var classification: MoveClassification? = null
        if (bestLine != null && bestMoveUci != null) {
            val bestDiff = reasonDiffAnalyzer.analyse(fen, bestMoveUci, bestLine.score)
            withMateFirst(bestDiff.whyThisMove, bestMoveSan, bestLine, sideToMove)
                ?.let { withoutIdleKingTalk(it, fen, bestLine) }
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
                    // Упущенный мат и есть упущенное: вилка с королём при нём ничего не добавляет.
                    val missedMate = engineMateFact(bestLine, sideToMove)
                    val missed = listOfNotNull(missedMate) +
                        bestFacts.filterNot { it in playedFacts || (missedMate != null && it is ForkFact) }

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
                    val swing = LineMaterial.swing(fen, playedLine, sideToMove)
                    sections += Section.PlayedMove(
                        scoreAfterFromWhite = playedScore?.let { ScoreFormat.toWhitePerspective(it, sideToMove) },
                        bestScoreFromWhite = scoreFromWhite,
                        lineSan = MoveNotation.uciToSanOrRaw(fen, playedLine),
                        lineUci = playedLine,
                        materialSwing = swing,
                        // "Конь пойман" к концу варианта — только если вариант и правда
                        // отнимает фигуру. Иначе конь просто стоит пассивно.
                        lineGains = lineGains(fen, playedLine, sideToMove).filter {
                            it !is TrappedPieceFact || (swing ?: 0) <= -TRAPPED_LOSS_CP
                        },
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
                    afterMoveSection(fen, playedSan, playedPv, playedScore, sideToMove)?.let { sections += it }
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
        previous: PreviousMove?,
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
            mates = MatingMoves.of(fen, mover.flip()),
            previousMove = previous,
        )
    }

    /**
     * Зачем соперник сделал прошлый ход: что он взял, шах ли это и какие удары
     * и угрозы появились у него после хода, которых не было до. Только
     * детекторы, без движка: ответ на вопрос "что он задумал" виден на доске.
     */
    private fun previousMove(
        previousFen: String?,
        previousUci: String?,
        fen: String,
        features: FeatureSet,
        mover: Side,
    ): PreviousMove? {
        if (previousFen == null || previousUci == null) return null
        val opponent = mover.flip()
        val before = Board()
        if (!runCatching { before.loadFromFen(previousFen) }.isSuccess) return null
        val move = runCatching { Move(previousUci, opponent) }.getOrNull() ?: return null
        val captured = before.getPiece(move.to).takeIf { it != Piece.NONE }?.pieceType
            ?: if (before.getPiece(move.from).pieceType == com.github.bhlangonijr.chesslib.PieceType.PAWN &&
                move.from.file != move.to.file
            ) {
                com.github.bhlangonijr.chesslib.PieceType.PAWN
            } else {
                null
            }
        val after = Board()
        if (!runCatching { after.loadFromFen(fen) }.isSuccess) return null

        val known = featureSource.features(previousFen).favouring(opponent).map(::signature).toSet()
        val fresh = features.favouring(opponent).filter { fact ->
            signature(fact) !in known && (
                fact is HangingPieceFact || fact is ForkFact || fact is PinFact || fact is SkewerFact ||
                    fact is DiscoveredAttackFact || fact is BackRankFact || fact is MateFact ||
                    fact is TrappedPieceFact || fact is ForkThreatFact || fact is OutpostFact ||
                    fact is OpenFileRookFact || fact is PassedPawnFact || fact is KingSafetyFact
                )
        }
        return PreviousMove(
            san = MoveNotation.uciToSanOrRaw(previousFen, listOf(previousUci)).first(),
            moveNumberLabel = MoveNotation.fullMoveNumber(previousFen).toString() +
                if (opponent == Side.WHITE) "." else "...",
            captured = captured,
            check = after.isKingAttacked,
            facts = fresh,
        )
    }

    /**
     * ПОСЛЕ ХОДА: оценка позиции на доске и почему она такая — тот же разбор
     * варианта, что и до хода, но из позиции после сыгранного хода.
     */
    private suspend fun afterMoveSection(
        fen: String,
        playedSan: String,
        playedPv: List<String>,
        playedScore: Score?,
        mover: Side,
    ): Section.AfterMove? {
        val first = playedPv.firstOrNull() ?: return null
        val afterFen = applyUciMove(fen, first) ?: return null
        val score = playedScore ?: return Section.AfterMove(playedSan, null, null)
        // Вариант после хода — с очередью соперника, поэтому и счёт с его стороны.
        val line = EngineLine(multiPvIndex = 1, score = ScoreFormat.negate(score), pv = playedPv.drop(1), depth = 0)
        val reason = if (line.pv.isEmpty() && score !is Score.Mate) null else lineAnalyzer.analyse(afterFen, line)
        return Section.AfterMove(playedSan, ScoreFormat.toWhitePerspective(score, mover), reason)
    }

    /** МОЯ АТАКА: чем можно наказать соперника — те же факты, но на пользу мне, и к чему я веду дело. */
    private fun myAttackSection(
        fen: String,
        features: FeatureSet,
        mover: Side,
        advice: List<CheckedAdvice>,
        bestLine: EngineLine?,
    ): Section.MyAttack {
        val mine = features.favouring(mover)
        val matesInOne = MatingMoves.of(fen, mover)
        // Мат в один ход виден по доске и назван поимённо; дальний мат знает только движок.
        val engineMate = engineMateFact(bestLine, mover).takeIf { matesInOne.isEmpty() }
        val mate = listOfNotNull(engineMate) + mine.filter { it is MateFact || it is BackRankFact }
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
            exchanges = mine.filterIsInstance<ExchangeFact>().map { withEngineChoice(fen, it, bestLine) },
            checks = SafeChecks.of(fen, mover),
            advice = advice,
            mates = matesInOne,
        )
    }

    /**
     * Свой размен с равным продолжением: если лучшая линия движка играет
     * обязательную часть, а дальше вместо равного взятия делает другой ход,
     * этот ход и называем — "движок предпочитает Be7".
     */
    private fun withEngineChoice(fen: String, fact: ExchangeFact, bestLine: EngineLine?): ExchangeFact {
        val forced = fact.forcedPlies
        if (bestLine == null || fact.lineSan.size <= forced || bestLine.pv.size <= forced) return fact
        val engineSan = MoveNotation.uciToSanOrRaw(fen, bestLine.pv.take(forced + 1))
        if (engineSan.take(forced) != fact.lineSan.take(forced)) return fact
        val choice = engineSan.getOrNull(forced) ?: return fact
        return if (choice == fact.lineSan[forced]) fact else fact.copy(engineChoiceSan = choice)
    }

    /**
     * Когда лучший ход ведёт к мату, мат и есть причина. Вилка на короля и пешку
     * рядом с ним только сбивает: пешку никто брать не будет.
     */
    private fun withMateFirst(
        why: Section.WhyThisMove?,
        bestMoveSan: String,
        bestLine: EngineLine,
        mover: Side,
    ): Section.WhyThisMove? {
        val mate = engineMateFact(bestLine, mover) ?: return why
        val rest = why?.reasons.orEmpty().mapNotNull { reason ->
            reason.facts.filterNot { it is ForkFact || it is MateFact }
                .takeIf { it.isNotEmpty() }
                ?.let { Reason(reason.tier, it) }
        }
        val reasons = listOf(Reason(ReasonTier.MATERIAL_TACTICS, listOf(mate))) + rest
        return why?.copy(reasons = reasons, pointIsInTheLine = false) ?: Section.WhyThisMove(bestMoveSan, reasons)
    }

    /**
     * "Король белых на e1 под давлением" в причинах хода — только если лучшая
     * линия и правда идёт на короля: шахует в первых ходах. Иначе это примета
     * позиции, а не то, ради чего ход сделан. Пустой список причин — без раздела.
     */
    private fun withoutIdleKingTalk(why: Section.WhyThisMove, fen: String, bestLine: EngineLine): Section.WhyThisMove? {
        val attacksKing = MoveNotation.uciToSanOrRaw(fen, bestLine.pv.take(KING_TALK_PLIES)).any { it.contains('+') || it.contains('#') }
        if (attacksKing) return why
        val reasons = why.reasons.mapNotNull { reason ->
            reason.facts.filterNot { it is KingSafetyFact }.takeIf { it.isNotEmpty() }?.let { Reason(reason.tier, it) }
        }
        return why.copy(reasons = reasons)
    }

    /** Мат за [mover], если лучшая линия движка его находит. */
    private fun engineMateFact(bestLine: EngineLine?, mover: Side): MateFact? {
        val score = bestLine?.score as? Score.Mate ?: return null
        if (score.movesToMate <= 0) return null
        return MateFact(score.movesToMate, mover, Evidence.fromEngine(bestLine))
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
        // Матующий ход уже назван в пункте "Шахи и маты": вилкой его не пересказываем.
        if (MatingMoves.gives(fen, uci)) return@mapNotNull null
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

    /**
     * Свои взятия висящих фигур тоже проверяет движок: детектор считает размен
     * только на одном поле и не видит, что у самого берущего висит ферзь.
     */
    private suspend fun captureChecks(
        fen: String,
        captures: List<Fact>,
        mover: Side,
        bestLine: EngineLine?,
    ): List<CaptureCheck> {
        if (bestLine == null) return emptyList()
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return emptyList()
        return captures.filterIsInstance<HangingPieceFact>().mapNotNull { fact ->
            val from = fact.firstAttacker ?: return@mapNotNull null
            val promotes = board.getPiece(from).pieceType == com.github.bhlangonijr.chesslib.PieceType.PAWN &&
                (fact.square.rank == com.github.bhlangonijr.chesslib.Rank.RANK_8 || fact.square.rank == com.github.bhlangonijr.chesslib.Rank.RANK_1)
            val uci = from.value().lowercase() + fact.square.value().lowercase() + if (promotes) "q" else ""
            val after = applyUciMove(fen, uci) ?: return@mapNotNull null
            val (score, reply) = if (bestLine.pv.firstOrNull() == uci) {
                bestLine.score to bestLine.pv.getOrNull(1)
            } else {
                val line = engineClient.analyse(after, ADVICE_LIMITS).firstOrNull() ?: return@mapNotNull null
                ScoreFormat.negate(line.score) to line.pv.firstOrNull()
            }
            val afterBoard = Board().also { it.loadFromFen(after) }
            val replyTakes = reply?.takeIf { it.length >= 4 }?.let {
                afterBoard.getPiece(Square.valueOf(it.substring(2, 4).uppercase())).pieceType
            }
            CaptureCheck(
                square = fact.square,
                moveSan = MoveNotation.uciToSanOrRaw(fen, listOf(uci)).first(),
                scoreFromWhite = ScoreFormat.toWhitePerspective(score, mover),
                lossVsBestCentipawns = ScoreFormat.centipawnLoss(bestLine.score, score),
                replySan = reply?.let { MoveNotation.uciToSanOrRaw(after, listOf(it)).first() },
                replyTakes = replyTakes,
            )
        }
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
        val bestKind = naturalKind(fen, bestUci)
        // Почти равные лучшему ходы, самый заметный первым: взятие, шах, развитие.
        // Если сам ход компьютера такой же заметный, человеку искать другой незачем.
        val close = mainLines
            .filter { it.pv.firstOrNull() != null && it.pv.first() != bestUci }
            .distinctBy { it.pv.first() }
            .filter { ScoreFormat.centipawnLoss(bestLine.score, it.score) <= CoachThresholds.HUMAN_MOVE_MAX_LOSS_CP }
        val natural = close
            .map { it to naturalKind(fen, it.pv.first()) }
            .filter { (_, kind) -> kind.ordinal < bestKind.ordinal }
            .minWithOrNull(compareBy({ it.second.ordinal }, { ScoreFormat.centipawnLoss(bestLine.score, it.first.score) }))
        val quickFirst = quickLines.sortedBy { it.multiPvIndex }.firstOrNull()?.pv?.firstOrNull()
        val (line, kind) = natural
            ?: close.firstOrNull { it.pv.first() == quickFirst }?.let { it to naturalKind(fen, it.pv.first()) }
            // Заметнее хода компьютера нет: человек сыграл бы его же, и это тоже ответ.
            ?: return HumanMove(
                lineSan = MoveNotation.uciToSanOrRaw(fen, bestLine.pv.take(1)),
                lossVsBestCentipawns = 0,
                facts = emptyList(),
                kind = bestKind,
                computerQuiet = bestKind == NaturalMove.OTHER,
                sameAsComputer = true,
            )
        val uci = line.pv.first()
        val loss = ScoreFormat.centipawnLoss(bestLine.score, line.score)

        val diff = reasonDiffAnalyzer.analyse(fen, uci, bestLine.score)
        return HumanMove(
            lineSan = MoveNotation.uciToSanOrRaw(fen, line.pv.take(LINE_PLIES)),
            lossVsBestCentipawns = loss,
            facts = diff.whyThisMove?.reasons?.flatMap { it.facts } ?: emptyList(),
            kind = kind,
            computerQuiet = bestKind == NaturalMove.OTHER,
        )
    }

    /** Чем ход бросается в глаза: взятие, шах, вывод лёгкой фигуры с первой горизонтали. */
    private fun naturalKind(fen: String, uci: String): NaturalMove {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return NaturalMove.OTHER
        val mover = board.sideToMove
        val move = runCatching { Move(uci, mover) }.getOrNull() ?: return NaturalMove.OTHER
        val piece = board.getPiece(move.from)
        if (board.getPiece(move.to) != Piece.NONE) return NaturalMove.CAPTURE
        val after = applyUciMove(fen, uci)?.let { f -> Board().also { it.loadFromFen(f) } }
        if (after?.isKingAttacked == true) return NaturalMove.CHECK
        val homeRank = if (mover == Side.WHITE) com.github.bhlangonijr.chesslib.Rank.RANK_1 else com.github.bhlangonijr.chesslib.Rank.RANK_8
        val minor = piece.pieceType == com.github.bhlangonijr.chesslib.PieceType.KNIGHT ||
            piece.pieceType == com.github.bhlangonijr.chesslib.PieceType.BISHOP
        if (minor && move.from.rank == homeRank) return NaturalMove.DEVELOPMENT
        return NaturalMove.OTHER
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
        is Section.AfterMove -> 9
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
