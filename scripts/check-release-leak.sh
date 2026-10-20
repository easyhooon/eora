#!/usr/bin/env bash
# Fails if the sample's prodRelease APK contains Eora implementation classes, Eora activities, or any permission
# Eora could have added. Only the no-op `Eora` object and the shared API types may ship.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
apk="$(ls "$root"/sample/build/outputs/apk/prod/release/*.apk | head -1)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
apkanalyzer="$(ls -d "$sdk"/cmdline-tools/*/bin/apkanalyzer 2>/dev/null | head -1)"
[ -n "$apkanalyzer" ] || { echo "apkanalyzer not found under $sdk/cmdline-tools"; exit 1; }

status=0
classes="$("$apkanalyzer" dex packages --defined-only "$apk" | awk '$1 == "C" { print $NF }')"
# Ink and its native library come in only through the real eora module.
leaked="$(echo "$classes" | grep -E '^(com\.easyhooon\.eora\.(internal\.|LocalExportUploader)|androidx\.ink\.)' || true)"
if [ -n "$leaked" ]; then
  echo "Eora implementation classes in prodRelease:"; echo "$leaked"; status=1
fi

native="$(unzip -Z1 "$apk" | grep -E '^lib/.*ink' || true)"
if [ -n "$native" ]; then
  echo "Ink native libraries in prodRelease:"; echo "$native"; status=1
fi

manifest="$("$apkanalyzer" manifest print "$apk")"
if echo "$manifest" | grep -q 'com.easyhooon.eora.internal'; then
  echo "Eora components in the prodRelease manifest."; status=1
fi
# AndroidX core adds an app-private DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION to every app; it is not Eora's.
permissions="$(echo "$manifest" | tr '\n' ' ' | grep -oE '<uses-permission[^>]*android:name="[^"]+"' | grep -oE 'name="[^"]+"' \
  | grep -v 'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' || true)"
if [ -n "$permissions" ]; then
  echo "Unexpected permissions in the prodRelease manifest:"; echo "$permissions"; status=1
fi

[ "$status" -eq 0 ] && echo "prodRelease contains no Eora implementation, UI or permissions."
exit "$status"
