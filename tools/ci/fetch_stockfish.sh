#!/usr/bin/env bash
# Кладёт бинарник Stockfish туда, откуда Android разрешает его запускать.
#
# Начиная с Android 10 SELinux запрещает выполнять файлы из папки данных
# приложения, поэтому единственный рабочий путь — положить бинарник как
# lib*.so внутрь jniLibs. При установке он попадёт в nativeLibraryDir,
# который помечен исполняемым.
set -euo pipefail

TAG="${STOCKFISH_RELEASE_TAG:-sf_19}"
ASSET="${STOCKFISH_ASSET:-stockfish-android-arm64-universal.tar.gz}"
DEST_DIR="${JNI_LIBS_DIR:-app/src/main/jniLibs/arm64-v8a}"
DEST="$DEST_DIR/libstockfish.so"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

URL="https://github.com/official-stockfish/Stockfish/releases/download/${TAG}/${ASSET}"
echo "==> Скачиваю $URL"
curl -fL --retry 3 --retry-delay 5 -o "$WORK/sf.tar.gz" "$URL"

tar -xzf "$WORK/sf.tar.gz" -C "$WORK"

# Архив содержит не только бинарник, но и полное дерево исходников (stockfish/src/**).
# Имена бинарника между релизами менялись, поэтому отбираем по признакам, а не по
# точному имени: вне src/ и крупнее мегабайта. Исходники весят килобайты, бинарник
# с встроенной сетью NNUE — под сотню мегабайт, так что разделение надёжное.
# Права доступа как признак не годятся: на Windows-ФС они не сохраняются.
BIN="$(find "$WORK" -type f -iname "stockfish*" ! -path "*/src/*" -size +1M | head -n1)"
if [ -z "$BIN" ]; then
  echo "ОШИБКА: в архиве не найден бинарник stockfish*" >&2
  echo "Содержимое архива:" >&2
  find "$WORK" -type f | head -50 >&2
  exit 1
fi
echo "==> Найден бинарник: $BIN"

# Пустой или битый jniLibs не ломает assembleDebug — ошибка вылезла бы только
# на телефоне в виде невнятного падения. Поэтому проверяем здесь и жёстко.
if [ ! -s "$BIN" ]; then
  echo "ОШИБКА: бинарник пустой" >&2
  exit 1
fi
if ! head -c 4 "$BIN" | od -An -tx1 | grep -q "7f 45 4c 46"; then
  echo "ОШИБКА: файл не является ELF-бинарником" >&2
  exit 1
fi

mkdir -p "$DEST_DIR"
cp "$BIN" "$DEST"
chmod 755 "$DEST"

echo "==> Установлен: $DEST ($(du -h "$DEST" | cut -f1))"
