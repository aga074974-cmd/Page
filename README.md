# متن‌یاب فارسی — Persian OCR (کاملاً آفلاین)

اپلیکیشن اندروید بومی به زبان **Kotlin** برای استخراج متن فارسی از تصویر، با
**دقت به‌عنوان اولویت مطلق** و **بدون هیچ ارتباط شبکه‌ای**.

> A native Kotlin Android app that extracts Persian text from images entirely offline,
> optimising for accuracy over speed. Tesseract 5 (LSTM) + an OpenCV preprocessing pipeline
> + Persian-specific text post-processing.

---

## فهرست

1. [ویژگی‌ها](#ویژگیها)
2. [استک فنی](#استک-فنی)
3. [ساختار پوشه‌ها](#ساختار-پوشهها)
4. [راه‌اندازی گام‌به‌گام](#راهاندازی-گامبهگام)
   - [۱) افزودن فایل‌های traineddata](#۱-افزودن-فایلهای-traineddata)
   - [۲) افزودن OpenCV](#۲-افزودن-opencv)
   - [۳) افزودن Tesseract4Android](#۳-افزودن-tesseract4android)
   - [۴) ساخت و اجرا](#۴-ساخت-و-اجرا)
5. [خط لولهٔ پیش‌پردازش](#خط-لولهٔ-پیشپردازش)
6. [تنظیمات Tesseract](#تنظیمات-tesseract)
7. [پس‌پردازش متن فارسی](#پسپردازش-متن-فارسی)
8. [معماری](#معماری)
9. [تست‌ها](#تستها)
10. [نکات دقت و کارایی](#نکات-دقت-و-کارایی)
11. [عیب‌یابی](#عیبیابی)

---

## ویژگی‌ها

- 📷 ورودی از **گالری** یا **دوربین**
- ✂️ مرحلهٔ **برش دستی** با کادر کشیدنی (بدون هیچ کتابخانهٔ خارجی)
- 🔬 خط لولهٔ **پیش‌پردازش OpenCV**: خاکستری‌سازی → بزرگ‌نمایی تا ~۳۰۰ DPI →
  کاهش نویز → صاف‌کردن کجی → باینری‌سازی → مورفولوژی ملایم
- 👁️ **نمایش تصویر پیش‌پردازش‌شده پیش از OCR** تا کاربر کیفیت را ببیند
- 🔁 **OCR چندگذره**: اجرای Tesseract روی ۵ حالت باینری‌سازی و انتخاب بهترین خروجی
  با امتیازدهی ترکیبی
- 🇮🇷 **پس‌پردازش فارسی**: نرمال‌سازی ي/ك، ارقام، **نیم‌فاصله (ZWNJ)**، تمیزکاری فاصله‌ها
- ↔️ رابط کاربری کاملاً **راست‌به‌چپ**
- 📋 کپی به کلیپ‌بورد و اشتراک‌گذاری
- 🚫 **صفر درخواست شبکه** — مجوز `INTERNET` اصلاً اعلام نشده است

---

## استک فنی

| مورد | انتخاب |
|------|--------|
| زبان | Kotlin 2.2 |
| معماری | MVVM (ViewModel + StateFlow + Coroutines) |
| minSdk / targetSdk / compileSdk | ۲۴ / ۳۶ / ۳۶ |
| AGP / Gradle | 8.11.1 / 8.14.3 |
| موتور OCR | `cz.adaptech.tesseract4android:tesseract4android-openmp:4.8.0` (Tesseract 5، چندنخی) |
| پردازش تصویر | `org.opencv:opencv:4.12.0` |
| دادهٔ زبان | `fas.traineddata` + `ara.traineddata` از **tessdata_best** |
| UI | View system + ViewBinding + Material 3 |

> **چرا Views و نه Compose؟** صورت مسئله صراحتاً `TextView/EditText` و سپردن نمایش
> دوسویه (BiDi) به خود اندروید را خواسته است؛ `EditText` بومی بالغ‌ترین پشتیبانی BiDi
> و انتخاب متن RTL را دارد.

---

## ساختار پوشه‌ها

```
.
├── settings.gradle.kts            ← مخازن (شامل JitPack که برای Tesseract الزامی است)
├── build.gradle.kts               ← پیکربندی سطح پروژه
├── gradle/libs.versions.toml      ← کاتالوگ نسخه‌ها
├── gradle.properties
├── gradlew / gradlew.bat          ← Gradle Wrapper
├── scripts/
│   └── fetch-tessdata.sh          ← دریافت خودکار فایل‌های زبان
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro         ← نگه‌داشتن کلاس‌های JNI (Tesseract/Leptonica/OpenCV)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/tessdata/   ← 👈 فایل‌های *.traineddata اینجا
        │   ├── java/ir/page/persianocr/
        │   │   ├── PersianOcrApp.kt
        │   │   ├── image/
        │   │   │   ├── BinarizationMethod.kt   ← ۵ حالت باینری‌سازی
        │   │   │   ├── ImagePreprocessor.kt    ← ★ خط لولهٔ OpenCV
        │   │   │   └── OpenCvBootstrap.kt      ← بارگذاری امن کتابخانهٔ بومی
        │   │   ├── ocr/
        │   │   │   ├── TessDataInstaller.kt    ← کپی امن assets → filesDir
        │   │   │   ├── TesseractEngine.kt      ← پوشش TessBaseAPI
        │   │   │   ├── OcrResult.kt            ← مدل نتیجه + امتیازدهی نامزدها
        │   │   │   └── OcrRepository.kt        ← هماهنگ‌کنندهٔ چندگذره
        │   │   ├── text/
        │   │   │   └── PersianTextNormalizer.kt ← ★ پس‌پردازش فارسی
        │   │   ├── ui/
        │   │   │   ├── MainActivity.kt
        │   │   │   ├── MainViewModel.kt
        │   │   │   ├── MainUiState.kt
        │   │   │   └── CropImageView.kt        ← نمای برش دستی
        │   │   └── util/BitmapIo.kt
        │   └── res/
        │       ├── layout/activity_main.xml
        │       ├── values/       ← رشته‌های فارسی (پیش‌فرض)، رنگ‌ها، تم RTL
        │       ├── values-en/    ← ترجمهٔ انگلیسی
        │       └── xml/file_paths.xml
        └── test/java/ir/page/persianocr/
            ├── PersianTextNormalizerTest.kt
            └── OcrCandidateScorerTest.kt
```

---

## راه‌اندازی گام‌به‌گام

### ۱) افزودن فایل‌های traineddata

اپ به دو فایل زبان از مخزن **[tessdata_best]** نیاز دارد. این‌ها به‌خاطر حجمشان
در گیت نگه‌داری نمی‌شوند.

> ⚠️ **حتماً `tessdata_best` را بگیرید، نه `tessdata_fast` و نه `tessdata`.**
> نسخهٔ `best` مدل‌های LSTM با بالاترین دقت‌اند. کندترند، ولی چون در این اپ سرعت
> اهمیتی ندارد، دقیقاً همان چیزی است که می‌خواهیم.

**روش خودکار:**

```bash
./scripts/fetch-tessdata.sh
```

**روش دستی:**

```bash
mkdir -p app/src/main/assets/tessdata
cd app/src/main/assets/tessdata
curl -LO https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/fas.traineddata
curl -LO https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/ara.traineddata
```

نتیجهٔ نهایی باید این باشد:

```
app/src/main/assets/tessdata/
├── fas.traineddata   (≈ 3,325,955 بایت)
└── ara.traineddata   (≈ 12,603,724 بایت)
```

**این فایل‌ها چطور به دستگاه می‌رسند؟**

Tesseract یک کتابخانهٔ C++ است و فایل‌ها را با **مسیر فایل‌سیستمی** باز می‌کند، اما
assets داخل APK فشرده‌اند و مسیر واقعی ندارند. بنابراین
[`TessDataInstaller`](app/src/main/java/ir/page/persianocr/ocr/TessDataInstaller.kt)
در **اولین اجرا** آن‌ها را به پوشهٔ خصوصی اپ کپی می‌کند:

```
filesDir/tesseract/tessdata/fas.traineddata
filesDir/tesseract/tessdata/ara.traineddata
                └── و مسیرِ داده‌شده به init() همان filesDir/tesseract است
```

کپی «امن» است: نوشتن در فایل `.tmp`، سپس `fsync` و `rename` اتمیک. اگر پروسه وسط
کار کشته شود، فایل ناقص هرگز جای فایل سالم را نمی‌گیرد. در اجراهای بعدی، اندازهٔ
فایل مقصد با اندازهٔ asset مقایسه می‌شود و اگر یکی بود کپی تکرار نمی‌شود.

در `app/build.gradle.kts` هم این تنظیم لازم است تا فایل‌ها فشرده نشوند
(در غیر این صورت `openFd()` کار نمی‌کند و کپی کند و پرحافظه می‌شود):

```kotlin
androidResources {
    noCompress += listOf("traineddata")
}
```

[tessdata_best]: https://github.com/tesseract-ocr/tessdata_best

---

### ۲) افزودن OpenCV

از نسخهٔ ۴٫۹٫۰ به بعد، OpenCV یک **AAR رسمی روی Maven Central** منتشر می‌کند. پس
دیگر نیازی به دانلود دستی «OpenCV Android SDK»، وارد کردن ماژول `sdk/java`، یا
نصب اپ «OpenCV Manager» نیست. تنها کاری که لازم است در
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) انجام شده:

```toml
opencv = "4.12.0"
...
opencv = { group = "org.opencv", name = "opencv", version.ref = "opencv" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.opencv)
```

> 📝 **توجه به نام artifact:** در صورت مسئله `org.opencv:opencv-android` ذکر شده بود،
> ولی چنین artifact ای روی Maven Central وجود ندارد؛ نام رسمی `org.opencv:opencv` است
> و همان AAR اندرویدی است (شامل `jni/{armeabi-v7a,arm64-v8a,x86,x86_64}/libopencv_java4.so`).

بارگذاری در زمان اجرا با `OpenCVLoader.initLocal()` انجام می‌شود که کتابخانهٔ بومیِ
باندل‌شده در خود APK را بار می‌کند و **هیچ ارتباط شبکه‌ای ندارد** — ببینید
[`OpenCvBootstrap.kt`](app/src/main/java/ir/page/persianocr/image/OpenCvBootstrap.kt).

<details>
<summary>اگر ترجیح می‌دهید OpenCV را دستی (به‌صورت ماژول) اضافه کنید</summary>

1. از [opencv.org/releases](https://opencv.org/releases/) فایل `opencv-4.x.y-android-sdk.zip` را بگیرید.
2. در اندروید استودیو: `File → New → Import Module…` و مسیر `OpenCV-android-sdk/sdk` را بدهید.
3. در `settings.gradle.kts` خط `include(":opencv")` اضافه شود.
4. در `app/build.gradle.kts` به‌جای `implementation(libs.opencv)` بنویسید `implementation(project(":opencv"))`.
5. `minSdk` ماژول opencv را با پروژه هماهنگ کنید.

بقیهٔ کد بدون تغییر کار می‌کند.
</details>

---

### ۳) افزودن Tesseract4Android

> ⚠️ **این مهم‌ترین نکتهٔ راه‌اندازی است.** کتابخانهٔ Tesseract4Android روی
> **Maven Central منتشر نمی‌شود** و فقط از **JitPack** در دسترس است. بدون افزودن
> مخزن JitPack، Gradle نمی‌تواند آن را پیدا کند.

این مورد در [`settings.gradle.kts`](settings.gradle.kts) اعمال شده است:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // ← الزامی
    }
}
```

```kotlin
// app/build.gradle.kts — نسخهٔ OpenMP (چندنخی)
implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.8.0")
```

> 📝 **نکتهٔ دوم (که وقت زیادی از آدم می‌گیرد):** نام گروه Maven برابر
> `cz.adaptech.tesseract4android` است، ولی **نام بستهٔ جاوا** میراث `tess-two` است:
>
> ```kotlin
> import com.googlecode.tesseract.android.TessBaseAPI   // ✔ درست
> import cz.adaptech.tesseract4android.TessBaseAPI      // ✘ وجود ندارد
> ```
>
> به همین دلیل قواعد ProGuard هم روی `com.googlecode.**` تنظیم شده‌اند.

---

### ۴) ساخت و اجرا

```bash
./scripts/fetch-tessdata.sh          # یک‌بار
./gradlew :app:assembleDebug         # ساخت
./gradlew :app:installDebug          # نصب روی دستگاه متصل
./gradlew :app:test                  # تست‌های واحد JVM
```

APK خروجی: `app/build/outputs/apk/debug/app-debug.apk`

**مجوزها:** اپ هیچ مجوزی درخواست نمی‌کند.
- انتخاب از گالری با `PickVisualMedia` انجام می‌شود که به مجوز حافظه نیاز ندارد.
- عکس‌گرفتن از طریق اپِ دوربینِ خودِ دستگاه (`ACTION_IMAGE_CAPTURE`) و یک
  `FileProvider` انجام می‌شود؛ چون مجوز `CAMERA` **اعلام نشده**، اندروید آن را الزامی نمی‌کند.
- مجوز `INTERNET` هم اعلام نشده — یعنی حتی اگر بخواهیم هم نمی‌توانیم به شبکه وصل شویم.

---

## خط لولهٔ پیش‌پردازش

پیاده‌سازی: [`ImagePreprocessor.kt`](app/src/main/java/ir/page/persianocr/image/ImagePreprocessor.kt)

| گام | کاری که انجام می‌شود | چرا |
|-----|----------------------|-----|
| ۰ | **یکسان‌سازی قطبیت** | با نسبت پیکسل‌های روشن/تیره تشخیص می‌دهیم متن روشن روی زمینهٔ تیره است یا برعکس، و در صورت نیاز معکوس می‌کنیم. از این به بعد همهٔ گام‌ها «متن تیره روی زمینهٔ روشن» فرض می‌کنند. |
| ۱ | **خاکستری‌سازی** `COLOR_RGBA2GRAY` | Tesseract رنگ نمی‌خواهد. |
| ۲ | **بزرگ‌نمایی تا ~۳۰۰ DPI** | DPI فیزیکیِ یک عکس موبایل معلوم نیست، پس به‌جای حدس‌زدن، **بلندای واقعی حروف** را با `connectedComponentsWithStats` می‌سنجیم (میانهٔ ارتفاع مؤلفه‌های شبیه‌حرف) و تصویر را طوری بزرگ می‌کنیم که به ~۳۴ پیکسل برسد — معادل عملیِ ۱۰pt در ۳۰۰ DPI. با `INTER_LANCZOS4` (گران‌تر از CUBIC ولی لبهٔ حروف تمیزتر). ضریب بین ۱× و ۴× و با سقف حافظهٔ ۱۰ مگاپیکسل. |
| ۳ | **کاهش نویز** | `fastNlMeansDenoising(h=7, template=7, search=21)` برای تصاویر تا ۶ مگاپیکسل؛ بالاتر از آن `GaussianBlur(3×3)` تا زمان پردازش از کنترل خارج نشود. |
| ۴ | **صاف‌کردن کجی** | تخمین زاویه با **بیشینه‌سازی نوسان نمای افقی** (horizontal projection profile): وقتی سطرهای متن افقی باشند، مجموع پیکسل‌های هر سطر بیشترین نوسان را دارد. جست‌وجوی دومرحله‌ای: درشت ‎±۱۵° با گام ۰٫۵° و سپس ظریف با گام ۰٫۰۵°. از `minAreaRect` یا Hough برای متن چندستونی مقاوم‌تر است. چرخش با `INTER_CUBIC` + `BORDER_REPLICATE` و بزرگ‌کردن بوم تا هیچ گوشه‌ای بریده نشود. |
| ۵ | **باینری‌سازی (۵ حالت)** | `Sauvola` (محلی، پیاده‌سازی‌شده با integral image)، `adaptiveThreshold` گاوسی، `adaptiveThreshold` میانگین، `Otsu` سراسری، و `CLAHE + Otsu`. اندازهٔ پنجره از روی بلندای تخمینیِ حروف حساب می‌شود. |
| ۶ | **مورفولوژی ملایم** | `MORPH_CLOSE` با هستهٔ ۲×۲ برای پرکردن شکاف‌های ریزِ قلم، حذف بسیار محافظه‌کارانهٔ لکه‌ها (فقط مؤلفه‌های ≤ ۴ پیکسل — چون **در فارسی نقطه‌ها معنادارند** و پاک‌شدنشان «ب» را به «ا» تبدیل می‌کند)، و افزودن ۲۴ پیکسل حاشیهٔ سفید که Tesseract به آن نیاز دارد. |

تصویر حاصل **پیش از OCR** به کاربر نشان داده می‌شود و می‌تواند بین ۵ حالت جابه‌جا شود.

**مدیریت حافظه:** نسخه‌های باینری‌شده به‌صورت `Mat` (حافظهٔ بومی، ~۱۰ مگابایت هرکدام)
نگه داشته می‌شوند، نه `Bitmap` (که ARGB_8888 برای ۱۰ مگاپیکسل ≈ ۴۰ مگابایت از هیپ
جاواست). `Bitmap` فقط در لحظهٔ نیاز و برای یک حالت ساخته و بلافاصله بعد از OCR
`recycle` می‌شود. `PreprocessResult` یک `Closeable` است و ViewModel آن را می‌بندد.

---

## تنظیمات Tesseract

پیاده‌سازی: [`TesseractEngine.kt`](app/src/main/java/ir/page/persianocr/ocr/TesseractEngine.kt)

```kotlin
tess.init(dataPath, "fas+ara", TessBaseAPI.OEM_LSTM_ONLY)
tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)   // یا PSM_SINGLE_BLOCK
tess.setVariable("preserve_interword_spaces", "1")
tess.setVariable("user_defined_dpi", "300")
tess.setVariable("tessedit_do_invert", "0")
```

- **`OEM_LSTM_ONLY`** — فقط موتور عصبیِ Tesseract 5. برای خط فارسی به‌مراتب دقیق‌تر
  از موتور کلاسیک است و مدل‌های `tessdata_best` هم برای همین ساخته شده‌اند.
- **`"fas+ara"`** — بارگذاری هم‌زمان فارسی و عربی، تا واژه‌های عربیِ داخل متن فارسی
  و حروف مشترک بهتر تشخیص داده شوند.
- **`PSM_AUTO`** پیش‌فرض است؛ یک کلید در UI آن را به **`PSM_SINGLE_BLOCK`** تغییر
  می‌دهد که برای متن پاراگرافیِ یکدست معمولاً دقیق‌تر است.
- **`user_defined_dpi=300`** — چون خودمان تصویر را تا ~۳۰۰ DPI بزرگ کرده‌ایم؛ این کار
  هشدار «DPI نامشخص» را حذف و تخمین اندازهٔ قلم را درست می‌کند.

### ترتیب فراخوانی (نکتهٔ مهم API)

```kotlin
tess.setImage(bitmap)
tess.getHOCRText(0)          // ← تشخیص را اجرا می‌کند
val text = tess.getUTF8Text() // ← فقط نتیجهٔ آماده را برمی‌گرداند
```

این ترتیب عمدی است و مطابق نمونهٔ رسمی کتابخانه: **`getHOCRText(0)` تنها مسیری است
که `ProgressNotifier` را صدا می‌زند و با `stop()` قابل قطع‌کردن است.** اگر مستقیم
`getUTF8Text()` صدا زده شود، نه نوار پیشرفت کار می‌کند و نه لغو.

### OCR چندگذره

با فعال‌بودن کلید «اجرای OCR روی همهٔ حالت‌های باینری‌سازی»، هر ۵ نسخه به Tesseract
داده می‌شود و بهترین خروجی انتخاب می‌شود. امتیازدهی
([`OcrCandidateScorer`](app/src/main/java/ir/page/persianocr/ocr/OcrResult.kt))
فقط به `meanConfidence` تکیه نمی‌کند، چون Tesseract گاهی به یک خروجیِ کوتاه و پرت
اطمینان بالایی می‌دهد:

```
امتیاز = 1.0 × اطمینان
       + 25  × نسبت حروف فارسی
       − 45  × نسبت کاراکترهای بی‌ربط («آشغال»)
       + 20  × طول نسبی نسبت به بلندترین نامزد
```

---

## پس‌پردازش متن فارسی

پیاده‌سازی: [`PersianTextNormalizer.kt`](app/src/main/java/ir/page/persianocr/text/PersianTextNormalizer.kt)
— بدون هیچ وابستگی اندرویدی، پس روی JVM تست می‌شود.

| مرحله | نمونه |
|-------|-------|
| `NFKC` | `ﻻ` (ligature) → `لا` |
| نرمال‌سازی حروف | `ي`→`ی` ، `ك`→`ک` ، `ة`→`ه` ، `أ/إ/ٱ`→`ا` ، `ى`→`ی` |
| حذف اعراب و تطویل | `مُحَمَّدـــی` → `محمدی` |
| نرمال‌سازی ارقام | `١٢٣` → `۱۲۳` ، `2024` → `۲۰۲۴` (هر دو قابل خاموش‌کردن) |
| **نیم‌فاصله — پیشوند** | `می کند` → `می‌کند` ، `نمی شود` → `نمی‌شود` |
| **نیم‌فاصله — پسوند** | `کتاب ها` → `کتاب‌ها` ، `بزرگ ترین` → `بزرگ‌ترین` ، `خانه های` → `خانه‌های` |
| تمیزکاری فاصله | حذف فاصلهٔ اضافی، فاصلهٔ پیش از نقطه‌گذاری، و خطوط خالی زائد |

نکات پیاده‌سازی:

- قاعدهٔ پسوند فقط وقتی اعمال می‌شود که **واژهٔ پیشین دست‌کم دو حرف** داشته باشد.
  این‌طور «خشک و تر» به‌اشتباه «خشک و‌تر» نمی‌شود (`تر` اینجا واژهٔ مستقل است).
- نیم‌فاصله (`U+200C`) هرگز حذف نمی‌شود، ولی `ZWJ`/`ZWSP`/`LRM`/`RLM`/`BOM` حذف می‌شوند.
- متنی که از قبل نیم‌فاصلهٔ درست دارد بدون تغییر باقی می‌ماند (تست شده).
- ترتیب مراحل مهم است: اول یکسان‌سازی کاراکترها، بعد نیم‌فاصله، آخر تمیزکاری فاصله.

همهٔ این رفتارها در [`PersianTextNormalizerTest`](app/src/test/java/ir/page/persianocr/PersianTextNormalizerTest.kt) پوشش داده شده‌اند.

---

## معماری

```
MainActivity  ──(رویداد)──▶  MainViewModel  ──▶  OcrRepository ──▶ TesseractEngine
     ▲                            │                     │
     └────(StateFlow<MainUiState>)┘                     └──▶ TessDataInstaller
                                  │
                                  └──▶ ImagePreprocessor (OpenCV)
                                  └──▶ PersianTextNormalizer
```

- **Activity** فقط «وضعیت را رسم» و «رویداد می‌فرستد»؛ هیچ منطقی ندارد.
- **ViewModel** کل جریان کار را مدیریت می‌کند و یک `MainUiState` تغییرناپذیر منتشر می‌کند.
- همهٔ کارهای سنگین روی `Dispatchers.Default` / `Dispatchers.IO` اجرا می‌شوند؛
  نخ اصلی هرگز مسدود نمی‌شود.
- `TessBaseAPI` **thread-safe نیست**؛ یک `Mutex` در `OcrRepository` تضمین می‌کند
  هیچ‌وقت دو فراخوانی هم‌زمان انجام نشود.
- لغو کاربر: `Job.cancel()` به‌علاوهٔ `TessBaseAPI.stop()`.

---

## تست‌ها

```bash
./gradlew :app:test
```

۱۸ تست واحد روی JVM، بدون نیاز به شبیه‌ساز:

- `PersianTextNormalizerTest` — نرمال‌سازی حروف، ارقام، نیم‌فاصله (شامل موارد منفی)، تمیزکاری فاصله
- `OcrCandidateScorerTest` — رفتار امتیازدهی نامزدها

---

## نکات دقت و کارایی

- **مهم‌ترین کار خودِ کاربر است:** برش دقیق فقط روی ناحیهٔ متن، بیشترین تأثیر را روی
  دقت دارد — بیشتر از هر تنظیمی در خط لوله.
- عکس صاف و بدون سایه بگیرید؛ روشناییِ یکنواخت مهم‌تر از رزولوشن بالاست.
- برای متن پاراگرافیِ یکدست، کلید **PSM_SINGLE_BLOCK** را روشن کنید.
- زمان پردازش با حالت چندگذره روی یک صفحهٔ A5 معمولاً **۳۰ تا ۹۰ ثانیه** است.
  این عمدی است: صورت مسئله گفته سرعت اهمیتی ندارد. برای تست سریع‌تر، کلید چندگذره
  را خاموش کنید تا فقط حالت انتخابی اجرا شود.
- `MAX_WORKING_PIXELS` در `ImagePreprocessor` سقف حافظه است (پیش‌فرض ۱۰ مگاپیکسل).
  روی دستگاه‌های پرحافظه می‌توانید بالاتر ببرید تا برای متن‌های خیلی ریز دقت بیشتر شود.

---

## عیب‌یابی

| نشانه | علت و راه حل |
|-------|--------------|
| `Could not find cz.adaptech.tesseract4android:...` | مخزن **JitPack** به `settings.gradle.kts` اضافه نشده. |
| `Unresolved reference: cz.adaptech.tesseract4android.TessBaseAPI` | نام بستهٔ جاوا `com.googlecode.tesseract.android` است، نه نام گروه Maven. |
| «مقداردهی اولیهٔ Tesseract ناموفق بود» | فایل‌های `*.traineddata` در `assets/tessdata/` نیستند یا خراب‌اند. `./scripts/fetch-tessdata.sh` را اجرا و اپ را دوباره نصب کنید. |
| «بارگذاری OpenCV ناموفق بود» | ABI دستگاه در `abiFilters` نیست، یا کتابخانهٔ بومی در APK نیامده. `abiFilters` را در `app/build.gradle.kts` بررسی کنید. |
| «حافظه کافی نیست» | ناحیهٔ کوچک‌تری برش بزنید یا `MAX_WORKING_PIXELS` را کم کنید. |
| خروجی خالی است | کیفیت پیش‌نمایشِ پیش‌پردازش را ببینید؛ اگر متن در تصویر باینری محو یا شکسته است، برش را اصلاح کنید یا حالت باینری‌سازی دیگری را امتحان کنید. |
| متن به‌هم‌ریخته نمایش داده می‌شود | این معمولاً مشکل *نمایش* است نه OCR. نمایش BiDi به خود اندروید سپرده شده؛ اگر متن را جای دیگری کپی می‌کنید، مطمئن شوید آن برنامه از RTL پشتیبانی می‌کند. |

---

## مجوزها

- کد این پروژه: هرطور صلاح می‌دانید استفاده کنید.
- Tesseract4Android و Tesseract OCR و Leptonica: Apache 2.0
- OpenCV: Apache 2.0
- فایل‌های `tessdata_best`: Apache 2.0
