#!/usr/bin/env bash
# Проверка релизной сборки в эмуляторе: R8 ломает код незаметно для компилятора,
# а падение при запуске отняло бы и кнопку обновления.
set -uo pipefail

APK="$(ls apk/*.apk | head -n1)"
PACKAGE=org.chesscoach.app

adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -W -n "$PACKAGE/.MainActivity" || exit 1
sleep 25
adb exec-out screencap -p > smoke-screen.png
adb logcat -d > smoke-logcat.txt

if ! adb shell pidof "$PACKAGE"; then
  echo "ОШИБКА: приложение не работает после запуска" >&2
  grep -n "FATAL EXCEPTION" -A 25 smoke-logcat.txt >&2
  exit 1
fi
if grep -q "FATAL EXCEPTION" smoke-logcat.txt; then
  grep -n "FATAL EXCEPTION" -A 25 smoke-logcat.txt >&2
  exit 1
fi
echo "Приложение запустилось"

# Движок arm64 на эмуляторе x86_64 идёт через трансляцию, которая есть не в каждом
# образе. Поэтому его отказ здесь только сообщение, а не падение проверки.
LIB_DIR="$(adb shell pm dump "$PACKAGE" | grep -m1 nativeLibraryDir= | cut -d= -f2 | tr -d '[:space:]')"
adb shell "$LIB_DIR/libstockfish.so bench 16 1 8" 2>&1 | tail -n 5 || echo "Движок в эмуляторе не запустился"
