# Tesseract4Android — کلاس‌هایی که از سمت JNI صدا زده می‌شوند نباید مبهم‌سازی شوند.
# توجه: نام بستهٔ جاوا با نام گروه Maven فرق دارد (میراث tess-two).
-keep class com.googlecode.tesseract.android.** { *; }
-keepclassmembers class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-keepclassmembers class com.googlecode.leptonica.android.** { *; }

# OpenCV — بارگذاری بومی بر پایهٔ نام کلاس‌ها انجام می‌شود.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# مدل‌های داده‌ای ساده که فقط از UI مصرف می‌شوند.
-keep class ir.page.persianocr.ocr.OcrResult { *; }
