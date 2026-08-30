#!/usr/bin/env bash
# دریافت فایل‌های زبان Tesseract (نسخهٔ best) و قراردادن آن‌ها در assets.
# Downloads the *best* (most accurate) traineddata files into app/src/main/assets/tessdata/.
#
# استفاده:  ./scripts/fetch-tessdata.sh
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/tessdata"
BASE="https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main"

mkdir -p "$DEST"

for LANG in fas ara; do
  OUT="$DEST/$LANG.traineddata"
  if [ -s "$OUT" ]; then
    echo "✓ $LANG.traineddata از قبل موجود است ($(wc -c < "$OUT") بایت)"
    continue
  fi
  echo "↓ در حال دریافت $LANG.traineddata …"
  curl -fSL --retry 3 -o "$OUT.tmp" "$BASE/$LANG.traineddata"
  mv "$OUT.tmp" "$OUT"
  echo "✓ $LANG.traineddata ($(wc -c < "$OUT") بایت)"
done

echo
echo "اندازهٔ مورد انتظار:  fas ≈ 3.2 MB   ara ≈ 12.0 MB"
echo "حالا می‌توانید پروژه را بسازید:  ./gradlew :app:assembleDebug"
