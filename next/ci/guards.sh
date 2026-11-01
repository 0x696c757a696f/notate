#!/usr/bin/env bash
# Architecture guards for Notate Next (plans/rewrite/06-engineering.md §4). Run from anywhere.
set -u
cd "$(dirname "$0")/../.."
fail=0
report() { echo "::error::$1"; echo "$2"; fail=1; }

# 1. core must stay pure JVM.
hits=$(grep -rnE 'import (android\.|androidx\.|com\.onyx)' next/core --include='*.kt' || true)
[ -n "$hits" ] && report "next/core must not import android/androidx/onyx" "$hits"

# 2. Raw-drawing switches live only in OnyxDriverPort.kt.
hits=$(grep -rnE 'setRawDrawingEnabled|setRawDrawingRenderEnabled|openRawDrawing|closeRawDrawing' next --include='*.kt' \
  | grep -v '/next/android/platform/.*/OnyxDriverPort\.kt:' | grep -v '^next/android/platform/.*/OnyxDriverPort\.kt:' || true)
[ -n "$hits" ] && report "Raw drawing calls belong in platform/OnyxDriverPort.kt only" "$hits"

# 3. EpdController only in :next:platform.
hits=$(grep -rn 'EpdController\.' next --include='*.kt' | grep -v '^next/android/platform/' || true)
[ -n "$hits" ] && report "EpdController may only be used in next/android/platform" "$hits"

# 4. English is the only UI language: no values-<qualifier>/strings.xml under next/.
hits=$(find next -path '*/res/values-*/strings*.xml' -not -path '*/build/*' || true)
[ -n "$hits" ] && report "Only res/values/strings.xml is allowed (English only)" "$hits"

[ $fail -eq 0 ] && echo "guards: ok"
exit $fail
