#!/usr/bin/env bash
# Pre-renders every bundled controller-skin PDF to an alpha-preserving PNG at 4x (288 dpi).
# Bundled skins use these (Tier 2, design §3.3): on-device testing showed Android's pdfium
# renders some soft-mask shadows (e.g. gba iphone_dpad.pdf) with opaque corners.
# Requires poppler's pdftocairo.
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/assets/ControllerSkins"
mkdir -p prerendered
for f in *.pdf; do
  pdftocairo -png -transp -singlefile -r 288 "$f" "prerendered/${f%.pdf}@4x" &
done
wait
ls prerendered | wc -l
