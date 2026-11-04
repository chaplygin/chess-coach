package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.HangingPieceFact

/**
 * Фигуры, которые прямо сейчас можно забрать с выгодой по честному размену.
 *
 * "Выгодно" решает SeeCalculator, а не подсчёт атакующих: одна лишняя фигура
 * защитника ничего не значит, если она связана и не может ответить.
 */
class HangingDetector : Detector {
    override val name: String = "hanging"

    override fun detect(board: Board): List<Fact> {
        val original = board
        val facts = ArrayList<Fact>()
        val calm = withoutCheckers(original)

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = original.getPiece(square)
            if (piece == Piece.NONE || piece.pieceType == PieceType.KING) continue

            val attacker = piece.pieceSide.flip()
            // Удар соперника стороны, которой шах: её взятия обратно считаем на
            // доске без шахующей фигуры, будто на шах уже ответили. Иначе среди
            // законных ходов только уходы от шаха, и защищённая фигура
            // выглядела бы брошенной: "конь e7, защиты нет" при слоне d6.
            @Suppress("NAME_SHADOWING")
            val board = if (calm != null && original.sideToMove != attacker) calm else original
            val loss = SeeCalculator.evaluateSquare(board, square, attacker)
            // Меньше порога — почти равный размен, им занимается ExchangeDetector.
            if (loss >= SeeCalculator.WINNING_MIN_CP) {
                // Поле нападающего кладём в доказательство вместе с фигурой на
                // нём: назвать причину ("бьёт пешка e4") потом будет неоткуда —
                // факт может дожить до текста, относящегося к другой доске.
                val first = SeeCalculator.firstCapture(board, square, attacker)
                // На доске без шахующих фигур линии могут открыться: свой конь g3
                // закрывал ладье g8 путь к g1. Взятие должно быть видно и на настоящей доске.
                if (board !== original && first != null && !AttackMap.attackersOf(original, square, attacker).contains(first.from)) continue
                val squares = listOfNotNull(square, first?.from)
                val withCheck = first != null && !first.recaptureExists && capturesWithCheck(board, square, attacker, first.from)

                facts += HangingPieceFact(
                    square = square,
                    piece = piece,
                    lossCentipawns = loss,
                    favours = attacker,
                    evidence = Evidence(
                        squares = squares,
                        seeCentipawns = loss,
                        pieces = squares.associateWith { board.getPiece(it) }.filterValues { it != Piece.NONE },
                    ),
                    firstAttacker = first?.from,
                    defended = first?.recaptureExists ?: false,
                    recaptureLineSan = SeeCalculator.recaptureLineSan(board, square, attacker),
                    recapturer = recapturerOf(board, square, attacker),
                    capturedWithCheck = withCheck,
                )
            }
        }

        return facts
    }

    /** Копия доски без фигур, которые шахуют стороне на очереди. Null — шаха нет. */
    private fun withoutCheckers(board: Board): Board? {
        // Позиция без короля (задачи, тесты) роняет chesslib на проверке шаха.
        if (!runCatching { board.isKingAttacked }.getOrDefault(false)) return null
        val side = board.sideToMove
        val calm = board.clone()
        AttackMap.attackersOf(board, board.getKingSquare(side), side.flip()).forEach { square ->
            calm.unsetPiece(board.getPiece(square), square)
        }
        return calm
    }

    /**
     * Взять обратно нечем только потому, что первое взятие даёт шах: защитник
     * на доске есть. Тогда "защиты нет" — неправда, правда — "взятие с шахом".
     */
    private fun capturesWithCheck(board: Board, square: Square, attacker: Side, from: Square): Boolean = runCatching {
        val after = boardAfter(board, attacker, Move(from, square))
        after.isKingAttacked && AttackMap.attackersOf(after, square, attacker.flip()).any { after.getPiece(it).pieceType != PieceType.KING }
    }.getOrDefault(false)

    /** Какой фигурой пришлось бы брать обратно: именно она и теряется, если размен продолжат. */
    private fun recapturerOf(board: Board, square: Square, attacker: Side): Piece? {
        val first = SeeCalculator.firstCapture(board, square, attacker) ?: return null
        if (!first.recaptureExists) return null
        val after = boardAfter(board, attacker, Move(first.from, square))
        return after.legalMoves()
            .filter { it.to == square }
            .minByOrNull { SeeCalculator.value(after.getPiece(it.from).pieceType) }
            ?.let { after.getPiece(it.from) }
    }
}
