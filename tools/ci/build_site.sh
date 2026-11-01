#!/usr/bin/env bash
# Собирает статический сайт: страница, тренер (TeaVM) и Stockfish на WebAssembly.
set -euo pipefail

STOCKFISH_NPM_VERSION="19.0.1"
OUT="web/build/site"

./gradlew :web:generateJavaScript --stacktrace

rm -rf "$OUT"
mkdir -p "$OUT/engine"
cp -r web/site/* "$OUT/"
cp web/build/generated/teavm/js/coach.js "$OUT/"

# Версия та же, что у APK этой сборки. Вне CI номера сборки нет.
sed -i "s/__APP_VERSION__/0.2.${GITHUB_RUN_NUMBER:-dev}/" "$OUT/index.html"

# Движок берётся из npm, а не из репозитория: это чужой бинарник, как и в APK.
TMP="$(mktemp -d)"
curl -fsSL -o "$TMP/stockfish.tgz" "https://registry.npmjs.org/stockfish/-/stockfish-$STOCKFISH_NPM_VERSION.tgz"
tar xzf "$TMP/stockfish.tgz" -C "$TMP"
cp "$TMP/package/bin/stockfish-19-lite-single.js" "$OUT/engine/"
cp "$TMP/package/bin/stockfish-19-lite-single.wasm" "$OUT/engine/"
cp "$TMP/package/Copying.txt" "$OUT/engine/"

ls -lh "$OUT" "$OUT/engine"
