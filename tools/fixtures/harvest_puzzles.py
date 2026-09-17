"""Собирает золотые фикстуры из задач Lichess с проставленными темами.

Зачем отдельный скрипт, а не FEN'ы, написанные от руки: позиция со сложным
мотивом, придуманная по памяти, слишком часто оказывается нелегальной или
такой, где задуманная тактика на самом деле не лучший ход. Здесь мотив
размечен самим Lichess, решение известно, а рейтинг задачи показывает, что она
как раз для нашего читателя — клубного игрока 1200-1800.

Скрипт обращается к сети, поэтому в CI не запускается: он готовит файл с
фикстурами, который коммитится и дальше живёт в репозитории.

Запуск:
    python tools/fixtures/harvest_puzzles.py
"""

import json
import sys
import time
import urllib.request

import chess
import chess.pgn
import io

THEMES = [
    "fork",
    "pin",
    "skewer",
    "backRankMate",
    "hangingPiece",
    "discoveredAttack",
]

PER_THEME = 1
USER_AGENT = "ChessCoach/0.1 personal game review"
OUT = "coach/src/test/resources/puzzle_fixtures.json"


def fetch(theme):
    """Запрос с выдержкой.

    Lichess просит обращаться к нему по одному запросу за раз и на 429 отвечает
    заголовком Retry-After. Ломиться дальше в этот момент — верный способ
    получить временную блокировку, поэтому ждём столько, сколько просят.
    """
    url = "https://lichess.org/api/puzzle/next?angle=" + theme
    for attempt in range(4):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            if error.code != 429:
                raise
            wait = int(error.headers.get("Retry-After") or 65)
            print("    429, жду " + str(wait) + " с")
            time.sleep(wait)
    raise RuntimeError("Lichess не отдал задачу после нескольких попыток")


def position_of(payload):
    """FEN позиции, с которой начинается задача, и сторона, которая решает.

    Lichess отдаёт партию целиком и номер полухода, после которого задача
    начинается. Разыгрывать её надо честно по ходам: самому складывать FEN из
    описания — верный способ разойтись с источником и не заметить этого.
    """
    game = chess.pgn.read_game(io.StringIO(payload["game"]["pgn"]))
    if game is None:
        return None

    board = game.board()
    moves = list(game.mainline_moves())
    initial_ply = payload["puzzle"]["initialPly"]

    for index, move in enumerate(moves):
        if index > initial_ply:
            break
        board.push(move)

    return board


def load_existing():
    """Уже собранное не выбрасываем: добор — обычное дело при ограничениях сети."""
    try:
        with open(OUT, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return []


def main():
    collected = load_existing()
    seen = {item["id"] for item in collected}
    have = {}
    for item in collected:
        have[item["theme"]] = have.get(item["theme"], 0) + 1

    for theme in THEMES:
        gathered = have.get(theme, 0)
        attempts = 0
        while gathered < PER_THEME and attempts < PER_THEME * 4:
            attempts += 1
            try:
                payload = fetch(theme)
            except Exception as error:
                print("  " + theme + ": сеть недоступна (" + type(error).__name__ + ")")
                break

            puzzle = payload["puzzle"]
            if puzzle["id"] in seen:
                time.sleep(3)
                continue

            board = position_of(payload)
            if board is None:
                continue

            solution = puzzle["solution"]
            first = solution[0]
            # Проверяем, что решение вообще применимо к разыгранной позиции.
            # Если нет, значит мы неверно поняли initialPly, и такую фикстуру
            # брать нельзя: молча испорченный эталон хуже отсутствующего.
            try:
                move = chess.Move.from_uci(first)
            except ValueError:
                continue
            if move not in board.legal_moves:
                print("  " + theme + "/" + puzzle["id"] + ": решение нелегально, пропуск")
                continue

            seen.add(puzzle["id"])
            gathered += 1
            collected.append(
                {
                    "id": puzzle["id"],
                    "theme": theme,
                    "themes": puzzle["themes"],
                    "rating": puzzle.get("rating"),
                    "fen": board.fen(),
                    "sideToMove": "white" if board.turn == chess.WHITE else "black",
                    "solution": solution,
                }
            )
            print("  " + theme + "/" + puzzle["id"] + " рейтинг " + str(puzzle.get("rating")))
            time.sleep(70)

    if not collected:
        print("Ничего не собрано, файл не трогаю.")
        return 1

    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(collected, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print("Записано " + str(len(collected)) + " фикстур в " + OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
