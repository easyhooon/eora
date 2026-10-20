#!/usr/bin/env bash
# Fails if the public `Eora` API differs between eora and eora-noop, so swapping the dependency per variant never
# breaks host code. Run via `./gradlew checkEoraApiParity`.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
classes="build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"

public_api() {
  javap -public -cp "$root/$1/$classes" "$2" \
    | grep -E '^  public' \
    | grep -vE '\$[a-z_-]+\(|access\$|\$stable' \
    | sed -E 's/ synchronized / /' \
    | sort
}

expected="$(public_api eora com.easyhooon.eora.Eora)"
actual="$(public_api eora-noop com.easyhooon.eora.Eora)"
if ! diff <(echo "$expected") <(echo "$actual"); then
  echo "com.easyhooon.eora.Eora differs between :eora (<) and :eora-noop (>)."
  exit 1
fi
echo "Eora API matches across eora and eora-noop."
