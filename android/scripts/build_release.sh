#!/usr/bin/env bash
# Genera el APK de release (docs/08 F8, docs/10 "APK").
# Requiere el keystore configurado en ~/.gradle/gradle.properties (nunca en el repo):
#   CHECKIN_RELEASE_STORE_FILE=/ruta/absoluta/al.keystore
#   CHECKIN_RELEASE_STORE_PASSWORD=...
#   CHECKIN_RELEASE_KEY_ALIAS=...
#   CHECKIN_RELEASE_KEY_PASSWORD=...
set -euo pipefail

cd "$(dirname "$0")/.."

if ! grep -q "^CHECKIN_RELEASE_STORE_FILE=" "$HOME/.gradle/gradle.properties" 2>/dev/null; then
  echo "Falta configurar el keystore de release en ~/.gradle/gradle.properties (ver docs/10-despliegue.md)." >&2
  echo "Variables necesarias: CHECKIN_RELEASE_STORE_FILE, CHECKIN_RELEASE_STORE_PASSWORD, CHECKIN_RELEASE_KEY_ALIAS, CHECKIN_RELEASE_KEY_PASSWORD." >&2
  exit 1
fi

./gradlew testDebugUnitTest lint assembleRelease

APK=$(find app/build/outputs/apk/release -name "*.apk" | head -n1)
if [ -z "$APK" ]; then
  echo "No se generó ningún APK en app/build/outputs/apk/release." >&2
  exit 1
fi

echo "APK de release listo: $APK"
