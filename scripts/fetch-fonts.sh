#!/usr/bin/env sh
# Fetches the two open-source (SIL OFL) variable fonts the UI uses into app/src/main/res/font.
# Run once before a local build; CI runs it automatically.
set -e
dir="$(dirname "$0")/../app/src/main/res/font"
mkdir -p "$dir"
base=https://raw.githubusercontent.com/google/fonts/main/ofl
[ -s "$dir/inter.ttf" ] || curl -fsSL -o "$dir/inter.ttf" "$base/inter/Inter%5Bopsz,wght%5D.ttf"
[ -s "$dir/plus_jakarta_sans.ttf" ] || curl -fsSL -o "$dir/plus_jakarta_sans.ttf" "$base/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf"
echo "Fonts ready in $dir"
