"""Добирает золотые фикстуры из открытой базы задач Lichess.

Ручка /api/puzzle/next отдаёт анонимным запросам скупо и быстро упирается в
ограничение частоты, поэтому недостающие темы берём из выгрузки базы. Файл
большой, но читается потоком и обрывается, как только нужное набрано, — целиком
качать его незачем.

Колонки выгрузки: PuzzleId, FEN, Moves, Rating, RatingDeviation, Popularity,
NbPlays, Themes, GameUrl, OpeningTags.

FEN в выгрузке — позиция ДО первого хода, и этот первый ход делает соперник.
Позиция задачи получается после него; именно так её показывает сайт, и именно
так её надо класть в фикстуру, иначе мотива на доске ещё нет.

Запуск:
    python tools/fixtures/harvest_from_database.py
"""

import csv
import io
import json
import sys
import urllib.request

import chess
import zstandard

URL = "https://database.lichess.org/lichess_db_puzzle.csv.zst"
OUT = "coach/src/test/resources/puzzle_fixtures.json"

WANTED = {
    "skewer": 3,
    "backRankMate": 3,
    "hangingPiece": 3,
    "discoveredAttack": 3,
}

# Читатель разбора — клубный игрок, и фикстуры должны быть из его мира.
RATING_RANGE = (1200, 1800)

# Тема, названная вместе с десятком других, обычно означает длинную
# комбинацию, где мотив возникает глубоко в варианте. Для эталона нужны
# задачи, где он на виду.
MAX_THEMES = 6


def position_of(fen, moves):
    """Позиция задачи и её решение (без первого хода соперника)."""
    board = chess.Board(fen)
    first = chess.Move.from_uci(moves[0])
    if first not in board.legal_moves:
        return None, None
    board.push(first)
    return board, moves[1:]


def load_existing():
    try:
        with open(OUT, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return []


def main():
    collected = load_existing()
    seen = {item["id"] for item in collected}
    need = dict(WANTED)
    for item in collected:
        if item["theme"] in need:
            need[item["theme"]] = max(0, need[item["theme"]] - 1)

    if not any(need.values()):
        print("Всё уже собрано.")
        return 0

    print("Ищу: " + ", ".join(k + " x" + str(v) for k, v in need.items() if v))

    request = urllib.request.Request(URL, headers={"User-Agent": "ChessCoach/0.1"})
    decompressor = zstandard.ZstdDecompressor()

    with urllib.request.urlopen(request, timeout=120) as response:
        with decompressor.stream_reader(response) as stream:
            text = io.TextIOWrapper(stream, encoding="utf-8", newline="")
            reader = csv.DictReader(text)

            for row in reader:
                if not any(need.values()):
                    break

                themes = row["Themes"].split()
                if len(themes) > MAX_THEMES:
                    continue

                matching = [theme for theme in themes if need.get(theme, 0) > 0]
                if not matching:
                    continue

                rating = int(row["Rating"])
                if not RATING_RANGE[0] <= rating <= RATING_RANGE[1]:
                    continue
                if row["PuzzleId"] in seen:
                    continue

                board, solution = position_of(row["FEN"], row["Moves"].split())
                if board is None:
                    continue

                theme = matching[0]
                need[theme] -= 1
                seen.add(row["PuzzleId"])
                collected.append(
                    {
                        "id": row["PuzzleId"],
                        "theme": theme,
                        "themes": themes,
                        "rating": rating,
                        "fen": board.fen(),
                        "sideToMove": "white" if board.turn == chess.WHITE else "black",
                        "solution": solution,
                    }
                )
                print("  " + theme + "/" + row["PuzzleId"] + " рейтинг " + str(rating))

    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(collected, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    remaining = [k for k, v in need.items() if v]
    if remaining:
        print("Не нашлось: " + ", ".join(remaining))
    print("Всего фикстур: " + str(len(collected)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
