# فایل‌های زبان Tesseract

این پوشه باید شامل فایل‌های زیر باشد (که به‌خاطر حجمشان در مخزن گیت نگه‌داری نمی‌شوند):

| فایل | منبع | حجم تقریبی |
|------|------|------------|
| `fas.traineddata` | [tessdata_best](https://github.com/tesseract-ocr/tessdata_best) | ‎۳٫۲ مگابایت |
| `ara.traineddata` | [tessdata_best](https://github.com/tesseract-ocr/tessdata_best) | ‎۱۲٫۰ مگابایت |

**حتماً از مخزن `tessdata_best` استفاده کنید، نه `tessdata_fast` و نه `tessdata`.**
نسخهٔ `best` مدل‌های LSTM با بیشترین دقت را دارد؛ کندتر است ولی در این اپ سرعت اهمیتی ندارد.

## دریافت خودکار

```bash
./scripts/fetch-tessdata.sh
```

## دریافت دستی

```bash
cd app/src/main/assets/tessdata
curl -LO https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/fas.traineddata
curl -LO https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/ara.traineddata
```

اگر این فایل‌ها نباشند، اپ کامپایل و اجرا می‌شود ولی هنگام اجرای OCR پیام خطای
«فایل‌های زبان در assets/tessdata/ پیدا نشد» را نشان می‌دهد.
