package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.File
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.PositionCharacter
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.StrategicIdea

/**
 * Характер позиции и общая идея — без обращения к движку, целиком из доски и
 * уже посчитанного [FeatureSet]. Это первое, что читает человек, поэтому
 * ошибиться здесь особенно обидно: "позиция открытая" в закрытой структуре
 * подсказывает неверный план на весь оставшийся разбор.
 */
object PositionAssessor {

    /**
     * Центр — линии d и e, не шире. Расширение до c-f превратило бы закрытый
     * фланг (пешки на b4-c5) в "закрытый центр", хотя игра там идёт совсем по
     * другим правилам, чем в настоящем закрытом центре вроде французской защиты.
     */
    private val CENTER_FILES = listOf(File.FILE_D, File.FILE_E)

    /**
     * Не больше одной пешечной пары на d и e вместе (обе стороны) — то есть
     * пешек в центре почти нет. Порог взят не "на глаз": на позиции с четырьмя
     * и более центральными пешками фигурам уже тесно, а вот две пешки
     * (например, только e4 и e5, при этом ещё и не запертые друг о друга)
     * реально оставляют фигурам простор.
     */
    private const val OPEN_MAX_CENTER_PAWNS = 2

    /**
     * Обе центральные линии заперты пешечными парами "лоб в лоб" — d4/d5 и
     * e4/e5 разом. Одной запертой линии мало: половина центра ещё может
     * вскрыться разменом, а вот когда заперто всё — так и есть закрытый центр.
     */
    private const val CLOSED_MIN_LOCKED_CENTER_FILES = 2

    /** Не больше двух идей одновременно — план из пяти пунктов никто не запомнит. */
    private const val MAX_IDEAS = 2

    /**
     * Перевес, при котором стоит не просто разменивать фигуры (FORCE_EXCHANGES),
     * а прямо стремиться в эндшпиль (SIMPLIFY_TO_ENDGAME): величиной примерно в
     * лёгкую фигуру и больше — разница, которую трудно упустить даже в простом
     * окончании.
     */
    private const val LARGE_MATERIAL_ADVANTAGE_CP = 500

    fun assess(
        fen: String,
        features: FeatureSet,
        mover: Side,
        opening: String? = null,
    ): Section.PositionAssessment {
        val board = Board()
        val boardLoaded = runCatching { board.loadFromFen(fen) }.isSuccess
        val character = if (boardLoaded) {
            characterOf(board)
        } else {
            // Без доски по-честному характер позиции не определить — но раздел
            // всё равно должен появиться, раз материал и идеи считаются отдельно.
            PositionCharacter.SEMI_OPEN
        }

        return Section.PositionAssessment(
            materialCentipawns = features.materialCentipawns,
            character = character,
            ideas = ideasFor(features, mover, character),
            opening = opening,
            pieceDifference = if (boardLoaded) pieceDifference(board) else emptyMap(),
        )
    }

    /**
     * Сколько фигур каждого вида лишних у белых (минус — у чёрных).
     *
     * Считается по доске, а не выводится из сантипешек: из одного числа
     * "плюс 200" нельзя понять, две это лишние пешки или лишний конь против
     * пешки, а разница между этими случаями на доске огромная.
     */
    private fun pieceDifference(board: Board): Map<PieceType, Int> {
        val counted = listOf(
            PieceType.QUEEN,
            PieceType.ROOK,
            PieceType.BISHOP,
            PieceType.KNIGHT,
            PieceType.PAWN,
        )
        return counted.associateWith { type ->
            board.getPieceLocation(Piece.make(Side.WHITE, type)).size -
                board.getPieceLocation(Piece.make(Side.BLACK, type)).size
        }.filterValues { it != 0 }
    }

    private fun characterOf(board: Board): PositionCharacter {
        val centerPawns = centralPawnCount(board)
        val lockedCenterFiles = CENTER_FILES.count { isLockedFile(board, it) }
        val openFiles = openFileCount(board)

        return when {
            lockedCenterFiles >= CLOSED_MIN_LOCKED_CENTER_FILES && openFiles == 0 -> PositionCharacter.CLOSED
            centerPawns <= OPEN_MAX_CENTER_PAWNS && openFiles > 0 && lockedCenterFiles == 0 -> PositionCharacter.OPEN
            else -> PositionCharacter.SEMI_OPEN
        }
    }

    private fun centralPawnCount(board: Board): Int = CENTER_FILES.sumOf { file ->
        Side.values().sumOf { side ->
            board.getPieceLocation(Piece.make(side, PieceType.PAWN)).count { it.file == file }
        }
    }

    /** Линия [file] заперта: своя и чужая пешка стоят друг напротив друга, ни одна не может шагнуть вперёд. */
    private fun isLockedFile(board: Board, file: File): Boolean {
        val whitePawns = board.getPieceLocation(Piece.make(Side.WHITE, PieceType.PAWN)).filter { it.file == file }
        val blackPawns = board.getPieceLocation(Piece.make(Side.BLACK, PieceType.PAWN)).filter { it.file == file }
        return whitePawns.any { white -> blackPawns.any { black -> black.rank.ordinal - white.rank.ordinal == 1 } }
    }

    /** Линии вовсе без пешек — по ним может гулять любая ладья, это и есть открытые линии позиции в целом. */
    private fun openFileCount(board: Board): Int {
        val pawnFiles = Side.values()
            .flatMap { side -> board.getPieceLocation(Piece.make(side, PieceType.PAWN)) }
            .map { it.file }
            .toSet()
        // File.allFiles — это на самом деле File.values(), а в нём девятым
        // элементом сидит File.NONE (не настоящая линия на доске): без
        // фильтра пустая доска давала бы девять "открытых линий" вместо восьми.
        return File.allFiles.count { it != File.NONE && it !in pawnFiles }
    }

    /**
     * Идеи выбираются по приоритету и обрезаются до [MAX_IDEAS] — если условий
     * сработало больше, показываем самые уместные, а не все подряд. Пустой
     * список — честный ответ, когда позиция сама ничего не подсказывает; но
     * IMPROVE_PIECES — запасной вариант именно на этот случай, а не молчание.
     */
    private fun ideasFor(features: FeatureSet, mover: Side, character: PositionCharacter): List<StrategicIdea> {
        val opponent = mover.flip()
        val myKingUnsafe = features.pick<KingSafetyFact>().any { it.kingSide == mover }
        val opponentKingUnsafe = features.pick<KingSafetyFact>().any { it.kingSide == opponent }
        val haveAttackingResources = features.favouring(mover).any {
            it is HangingPieceFact || it is ForkFact || it is PinFact || it is SkewerFact ||
                it is DiscoveredAttackFact || it is OverloadedDefenderFact || it is MateFact
        }
        val hasPassedPawn = features.pick<PassedPawnFact>().any { it.owner == mover }

        // От лица того, чей сейчас ход: материал считается от белых, поэтому
        // для чёрных знак разворачивается.
        val materialForMover = if (mover == Side.WHITE) features.materialCentipawns else -features.materialCentipawns

        val candidates = mutableListOf<StrategicIdea>()

        // Свой король важнее чужого. Когда раскрыты оба, совет "атаковать
        // короля и укрепить оборону" читается как шум: сделать можно что-то
        // одно. Кто успеет первым, признаками позиции не определяется, а для
        // клубного игрока разобраться со своим королём — лучшее умолчание,
        // потому что мат заканчивает партию, а упущенная атака — нет.
        if (myKingUnsafe) {
            candidates += StrategicIdea.DEFEND
        } else if (opponentKingUnsafe && haveAttackingResources) {
            candidates += StrategicIdea.ATTACK_KING
        }

        when {
            materialForMover >= LARGE_MATERIAL_ADVANTAGE_CP -> candidates += StrategicIdea.SIMPLIFY_TO_ENDGAME
            materialForMover >= CoachThresholds.MATERIAL_NOTE_CP -> candidates += StrategicIdea.FORCE_EXCHANGES
        }

        if (hasPassedPawn) candidates += StrategicIdea.PUSH_PAWNS

        if (materialForMover >= CoachThresholds.MATERIAL_NOTE_CP && character == PositionCharacter.CLOSED) {
            candidates += StrategicIdea.OPEN_THE_POSITION
        }
        if (materialForMover <= -CoachThresholds.MATERIAL_NOTE_CP && character == PositionCharacter.CLOSED) {
            candidates += StrategicIdea.KEEP_IT_CLOSED
        }

        if (candidates.isEmpty()) candidates += StrategicIdea.IMPROVE_PIECES

        return candidates.distinct().take(MAX_IDEAS)
    }
}
