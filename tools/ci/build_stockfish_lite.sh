#!/usr/bin/env bash
# Собирает Stockfish с маленькой сетью и кладёт его туда, откуда Android разрешает запуск.
#
# Обычная сборка Stockfish весит под сто мегабайт почти целиком из-за встроенной
# сети, и каждое обновление APK тащило бы её заново. Исходники stockfish.js те же,
# что у движка на сайте: там сборка с LITE_NET уже настроена, а Makefile годится
# и для NDK.
#
# Начиная с Android 10 SELinux запрещает выполнять файлы из папки данных
# приложения, поэтому движок лежит как lib*.so внутри jniLibs и попадает при
# установке в nativeLibraryDir.
set -euo pipefail

# Коммит закреплён: сборка не должна меняться оттого, что вышла новая версия.
COMMIT="${STOCKFISH_JS_COMMIT:-10b49d8bb4539a0743013772ed735a63853a3b05}"
DEST_DIR="${JNI_LIBS_DIR:-app/src/main/jniLibs/arm64-v8a}"
DEST="$DEST_DIR/libstockfish.so"
# Универсальная сборка сама выбирает код под процессор: инструкции dotprod есть не
# на всех телефонах, а без них жёстко заданная сборка падала бы при запуске.
ARCH="${STOCKFISH_ARCH:-arm64-universal}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
test -n "$NDK"
ROOT="$(pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fL --retry 3 --retry-delay 5 -o "$WORK/src.tar.gz" "https://github.com/nmrugg/stockfish.js/archive/$COMMIT.tar.gz"
tar -xzf "$WORK/src.tar.gz" -C "$WORK"
SRC="$WORK/stockfish.js-$COMMIT/src"

# В этом коммите обычная (не WASM) сборка не компилируется из-за опечатки.
sed -i 's/uci.loop();/uci->loop();/' "$SRC/main.cpp"

# Проверка на этой же машине: сеть должна грузиться, а движок считать. Телефонный
# бинарник на раннере не запустить.
echo "==> Проверочная сборка для раннера"
make -C "$SRC" -j"$(nproc)" build ARCH=x86-64-sse41-popcnt LITE_NET=yes
"$SRC/stockfish" bench 16 1 8 2>&1 | tail -n 4
"$SRC/stockfish" bench 16 1 8 2>&1 | grep -q "Nodes searched"
make -C "$SRC" clean LITE_NET=yes > /dev/null

echo "==> Сборка для Android: $ARCH"
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
make -C "$SRC" -j"$(nproc)" build ARCH="$ARCH" COMP=ndk LITE_NET=yes
BIN="$SRC/stockfish"

# Пустой или битый jniLibs не ломает сборку APK — ошибка вылезла бы только на
# телефоне в виде невнятного падения. Поэтому проверяем здесь и жёстко.
if ! head -c 4 "$BIN" | od -An -tx1 | grep -q "7f 45 4c 46"; then
  echo "ОШИБКА: файл не является ELF-бинарником" >&2
  exit 1
fi
llvm-strip "$BIN"

cd "$ROOT"
mkdir -p "$DEST_DIR"
cp "$BIN" "$DEST"
chmod 755 "$DEST"
echo "==> Установлен: $DEST ($(du -h "$DEST" | cut -f1))"
