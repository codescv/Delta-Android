#!/usr/bin/env bash
# CI content gates for Delta for Android.
# Run from the repository root:  bash tools/ci_gates.sh
# Exits non-zero (with the offending file:line list) if any gate fails.
set -euo pipefail

if [[ ! -d app/src/main ]]; then
  echo "ci_gates: run this script from the repository root" >&2
  exit 2
fi

failures=0

# Directories that hold build output / caches and must never be scanned.
EXCLUDE_DIRS=(--exclude-dir=build --exclude-dir=.cxx --exclude-dir=.gradle --exclude-dir=.externalNativeBuild)

report() {
  local title="$1" hits="$2"
  if [[ -n "$hits" ]]; then
    echo "::error::ci_gates: ${title}"
    echo "FAIL: ${title}"
    echo "$hits" | sed 's/^/  /'
    failures=$((failures + 1))
  else
    echo "ok:   ${title}"
  fi
}

# (a) Forbidden user-visible strings (iOS Patreon/paywall wording).
hits_a="$(grep -rnIE "Patreon|Patrons|Become a Patron|RevenueCat|Unlocked|\bFree\b" \
  "${EXCLUDE_DIRS[@]}" app/src/main/res app/src/main/java || true)"
report "forbidden UI strings (Patreon/Patrons/RevenueCat/Unlocked/Free) in app/src/main/{res,java}" "$hits_a"

# (b) Internal-path / internal-package leaks. This script itself contains the
#     patterns, so it is excluded.
hits_b="$(grep -rnIE "experimental/users|google3|com\.google\.experimental|EXPERIMENTAL_USERS_CHII" \
  "${EXCLUDE_DIRS[@]}" --exclude=ci_gates.sh app tools || true)"
report "internal path / package leaks in app/ and tools/" "$hits_b"

# (c) Personal e-mail addresses in first-party code and resources.
#     app/src/main/cpp/cores/ holds verbatim upstream emulator sources whose
#     copyright notices (author e-mails) must be preserved by their licenses,
#     so that directory is excluded.
hits_c="$(grep -rnIE "@google\.com|@gmail\.com" \
  "${EXCLUDE_DIRS[@]}" app | grep -v '^app/src/main/cpp/cores/' || true)"
report "personal e-mail addresses in app/ (excluding upstream cores)" "$hits_c"

if (( failures > 0 )); then
  echo "ci_gates: ${failures} gate(s) failed" >&2
  exit 1
fi
echo "ci_gates: all gates passed"
