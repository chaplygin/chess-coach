#!/usr/bin/env bash
# Один раз создаёт постоянный ключ подписи APK, кладёт его в секреты GitHub и
# запускает сборку, которая опубликует первый релиз. Запускать из Git Bash.
set -euo pipefail

REPO="chaplygin/chess-coach"
DIR="$HOME/.chesscoach"
KEY="$DIR/release-signing.p12"
PASS="$DIR/release-signing.password"

for tool in gh keytool openssl; do
  command -v "$tool" > /dev/null || { echo "Не найдено: $tool. Установите его и запустите снова."; exit 1; }
done
gh auth status --active > /dev/null || { echo "Сначала выполните: gh auth login"; exit 1; }

# Подмена ключа после первого релиза сломала бы обновления на всех телефонах.
if gh secret list --repo "$REPO" | grep -q SIGNING_KEYSTORE_BASE64; then
  echo "Секреты подписи уже есть на GitHub, ничего не меняю."
  exit 0
fi

mkdir -p "$DIR"
if [ ! -f "$KEY" ]; then
  openssl rand -hex 32 | tr -d '[:space:]' > "$PASS"
  keytool -genkeypair -alias chesscoach -keyalg RSA -keysize 4096 -validity 20000 \
    -storetype PKCS12 -keystore "$KEY" -storepass "$(cat "$PASS")" -dname "CN=Chess Coach"
fi

base64 -w0 "$KEY" | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$REPO"
gh secret set SIGNING_PASSWORD --repo "$REPO" < "$PASS"
gh workflow run "Build APK" --repo "$REPO" --ref main

echo "Готово. Сохраните копию папки $DIR: без неё новые версии не встанут поверх установленной."
