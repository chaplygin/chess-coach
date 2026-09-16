#!/usr/bin/env python3
"""Проверяет выравнивание загружаемых сегментов ELF на размер страницы 16 КБ.

Android 15+ работает на устройствах со страницей 16 КБ. Нативный код,
собранный старым NDK, выровнен на 4 КБ и на таких устройствах не загрузится.
Готовый бинарник Stockfish собран не нами, поэтому это надо не предполагать,
а проверять.

Зависимостей нет специально: разбор заголовков ELF короче, чем возня с
установкой lief в CI.
"""
import struct
import sys

REQUIRED_ALIGN = 16384
PT_LOAD = 1


def load_segment_alignments(path):
    with open(path, "rb") as fh:
        data = fh.read()

    if data[:4] != b"\x7fELF":
        raise SystemExit(f"{path}: не ELF-файл")

    ei_class = data[4]
    if ei_class != 2:
        raise SystemExit(f"{path}: ожидался 64-битный ELF, найден класс {ei_class}")
    little_endian = data[5] == 1
    endian = "<" if little_endian else ">"

    # Elf64_Ehdr: e_phoff по смещению 32, e_phentsize 54, e_phnum 56
    e_phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    e_phentsize = struct.unpack_from(endian + "H", data, 54)[0]
    e_phnum = struct.unpack_from(endian + "H", data, 56)[0]

    alignments = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        p_vaddr = struct.unpack_from(endian + "Q", data, off + 16)[0]
        p_align = struct.unpack_from(endian + "Q", data, off + 48)[0]
        alignments.append((i, p_vaddr, p_align))
    return alignments


def main():
    if len(sys.argv) != 2:
        raise SystemExit("использование: check_elf_align.py <путь к .so>")
    path = sys.argv[1]
    segments = load_segment_alignments(path)

    if not segments:
        raise SystemExit(f"{path}: не найдено ни одного сегмента PT_LOAD")

    print(f"Загружаемые сегменты {path}:")
    worst = None
    for idx, vaddr, align in segments:
        print(f"  PT_LOAD[{idx}] vaddr=0x{vaddr:x} align={align} ({align // 1024} КБ)")
        worst = align if worst is None else min(worst, align)

    if worst >= REQUIRED_ALIGN:
        print(f"OK: минимальное выравнивание {worst} >= {REQUIRED_ALIGN} (16 КБ)")
        return 0

    print(
        f"ВНИМАНИЕ: минимальное выравнивание {worst} < {REQUIRED_ALIGN}.\n"
        "На устройствах Android 15+ со страницей 16 КБ бинарник может не запуститься.\n"
        "Проверить на реальном устройстве (M1). Если не работает — пересобрать\n"
        "Stockfish с NDK r28+ либо выровнять сегменты вручную.",
        file=sys.stderr,
    )
    # Не валим сборку: на странице 4 КБ бинарник рабочий, а вердикт даёт телефон.
    return 0


if __name__ == "__main__":
    sys.exit(main())
