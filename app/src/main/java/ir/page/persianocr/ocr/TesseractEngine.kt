package ir.page.persianocr.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import ir.page.persianocr.log.DiagnosticLog
import java.io.Closeable
import java.io.File

/** خطای مقداردهی موتور OCR. */
class TesseractInitException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** یک کلمه به‌همراه اطمینانِ خودش. */
data class OcrWord(val text: String, val confidence: Int)

/**
 * یک خطِ متن با اطمینانِ خودش.
 *
 * اطمینانِ *هر خط* چیزی است که «رأی‌گیری خط‌به‌خط» به آن نیاز دارد؛ میانگینِ
 * اطمینانِ کلِ صفحه برای این کار بی‌فایده است، چون دقیقاً همان عددی است که وقتی یک
 * حالت یک خطِ سخت را حذف می‌کند بالا می‌رود.
 */
data class OcrLine(
    val text: String,
    val confidence: Int,
    /** تعداد کلماتِ این خط. */
    val wordCount: Int = 0,
    /** تعداد کلماتی که اطمینانشان از [STRONG_WORD_CONFIDENCE] بیشتر است. */
    val strongWordCount: Int = 0,
    /**
     * لبهٔ بالای کادرِ محصورِ خط، در مختصاتِ **تصویرِ کاملِ پیش‌پردازش‌شده** (نه کاشی).
     * [NO_GEOMETRY] یعنی Tesseract مختصات نداد و باید به ترازِ متنی برگردیم.
     */
    val top: Int = NO_GEOMETRY,
    /** لبهٔ پایینِ کادرِ محصور، در همان مختصات. */
    val bottom: Int = NO_GEOMETRY,
) {
    /** آیا مختصاتِ معتبری داریم که بشود بر اساسش خطوط را گروه و مرتب کرد؟ */
    val hasGeometry: Boolean get() = top != NO_GEOMETRY && bottom > top

    /** مرکز عمودیِ خط — کلیدِ اصلیِ مرتب‌سازی. */
    val centerY: Int get() = (top + bottom) / 2

    val heightPx: Int get() = (bottom - top).coerceAtLeast(1)

    companion object {
        /** آستانهٔ «کلمهٔ قابل‌اعتماد». */
        const val STRONG_WORD_CONFIDENCE = 60

        /** نشانهٔ «مختصات در دسترس نیست». */
        const val NO_GEOMETRY = -1
    }
}

/** خروجی خام یک گذرِ OCR. */
data class RawOcrOutput(
    val text: String,
    val meanConfidence: Int,
    /** خطوطِ تفکیک‌شده با اطمینانِ جداگانه؛ در بدترین حالت از شکستنِ [text] ساخته می‌شود. */
    val lines: List<OcrLine> = emptyList(),
) {
    /** تعداد کل کلماتِ با اطمینانِ بالا در کل خروجی — سنجهٔ «کامل‌بودن» متن. */
    val strongWordCount: Int get() = lines.sumOf { it.strongWordCount }

    val wordCount: Int get() = lines.sumOf { it.wordCount }
}

/**
 * پوشش نازکی روی [TessBaseAPI].
 *
 * ⚠ [TessBaseAPI] اصلاً thread-safe نیست. همهٔ فراخوانی‌ها باید از یک نخ (یا با قفل)
 * انجام شوند؛ این کار در [OcrRepository] با یک Mutex تضمین شده است.
 */
class TesseractEngine : Closeable {

    companion object {
        private const val TAG = "Tesseract"

        /** حالت‌های قطعه‌بندی صفحه در [PageMode] تعریف شده‌اند. */
        const val PSM_AUTO = TessBaseAPI.PageSegMode.PSM_AUTO
        const val PSM_SINGLE_BLOCK = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        const val PSM_SINGLE_COLUMN = TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN

        /**
         * ارتفاعِ حروف (بر حسب پیکسل) که «۳۰۰ DPI» را نمایندگی می‌کند. برای تبدیل
         * ارتفاع واقعیِ حروف به یک DPI مؤثر و صادقانه استفاده می‌شود.
         */
        private const val CHAR_HEIGHT_AT_300_DPI = 34.0

        private const val MIN_DPI = 70
        private const val MAX_DPI = 600

        /** تبدیل ارتفاع تخمینیِ حروف به DPI مؤثر برای `user_defined_dpi`. */
        fun effectiveDpi(charHeightPx: Double): Int =
            (300.0 * charHeightPx / CHAR_HEIGHT_AT_300_DPI)
                .toInt()
                .coerceIn(MIN_DPI, MAX_DPI)
    }

    private var api: TessBaseAPI? = null
    private var initialisedWith: String? = null

    @Volatile
    private var progressSink: ((Int) -> Unit)? = null

    val isInitialised: Boolean get() = api != null

    /** نسخهٔ کتابخانهٔ بومی Tesseract — برای نمایش/دیباگ. (متدِ نمونه است، نه استاتیک) */
    fun engineVersion(): String = runCatching { api?.getVersion() }.getOrNull() ?: "unknown"

    /**
     * مقداردهی اولیه با هر دو زبان.
     *
     * @param dataPath والدِ پوشهٔ `tessdata` (خروجی [TessDataInstaller.install]).
     * @param languages رشتهٔ زبان‌ها به شکل Tesseract، مثلاً `"fas+ara"`.
     *   ترکیب فارسی و عربی کمک می‌کند واژه‌های عربیِ داخل متن فارسی (و حروف مشترک)
     *   بهتر تشخیص داده شوند.
     */
    @Throws(TesseractInitException::class)
    fun init(dataPath: File, languages: String) {
        if (initialisedWith == languages && api != null) {
            DiagnosticLog.d(TAG, "موتور از قبل با «$languages» آماده است؛ مقداردهی دوباره لازم نیست.")
            return
        }
        close()

        DiagnosticLog.i(TAG, "مقداردهی موتور — زبان‌ها: «$languages» • مسیر داده: ${dataPath.absolutePath}")
        // فهرست فایل‌های واقعیِ روی دیسک: اگر init شکست بخورد، اولین چیزی که باید دید همین است.
        val installed = File(dataPath, "tessdata").listFiles()
            ?.joinToString { "${it.name} (${it.length()}B)" }
            ?: "(پوشهٔ tessdata خوانده نشد)"
        DiagnosticLog.d(TAG, "فایل‌های زبانِ روی دیسک: $installed")

        val notifier = TessBaseAPI.ProgressNotifier { values ->
            progressSink?.invoke(values.getPercent().coerceIn(0, 100))
        }

        val instance = try {
            TessBaseAPI(notifier)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError روی ABI پشتیبانی‌نشده اینجا ظاهر می‌شود.
            DiagnosticLog.e(TAG, "ساخت TessBaseAPI ممکن نشد — کتابخانهٔ بومی برای این معماری وجود ندارد؟", t)
            throw TesseractInitException("Cannot create TessBaseAPI (native library missing?)", t)
        }

        val ok = try {
            // OEM_LSTM_ONLY: فقط موتور عصبیِ Tesseract 5 — برای خط فارسی به‌مراتب دقیق‌تر
            // از موتور کلاسیک است و مدل‌های tessdata_best هم برای همین ساخته شده‌اند.
            DiagnosticLog.timed(TAG, "TessBaseAPI.init") {
                instance.init(dataPath.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)
            }
        } catch (t: Throwable) {
            DiagnosticLog.e(TAG, "TessBaseAPI.init استثنا انداخت", t)
            runCatching { instance.recycle() }
            throw TesseractInitException("TessBaseAPI.init threw", t)
        }

        if (!ok) {
            DiagnosticLog.e(
                TAG,
                "TessBaseAPI.init مقدار false برگرداند — زبان‌ها «$languages» در ${dataPath.absolutePath}",
            )
            runCatching { instance.recycle() }
            throw TesseractInitException(
                "TessBaseAPI.init returned false for languages='$languages' at ${dataPath.absolutePath}",
            )
        }

        api = instance
        initialisedWith = languages
        DiagnosticLog.i(TAG, "موتور Tesseract ${engineVersion()} با «$languages» آماده شد.")
    }

    /**
     * تنظیم‌های ریزی که روی دقتِ متن فارسی اثر مستقیم دارند.
     *
     * ⚠ ترتیب مهم است: مستندات خودِ کتابخانه می‌گوید «`setVariable` باید *بعد از*
     * `init()` صدا زده شود و فقط روی متغیرهای غیرِ init کار می‌کند». پس این متد
     * نمی‌تواند و نباید پیش از init اجرا شود. برای اینکه هیچ شکی نماند، مقدارِ
     * بازگشتیِ هر فراخوانی بررسی و ثبت می‌شود؛ اگر Tesseract نام متغیری را نشناسد
     * `false` برمی‌گرداند و آن را به‌صورت هشدار در گزارش می‌بینید.
     *
     * @param dpi وضوحِ مؤثرِ واقعیِ تصویر. عددِ ثابتِ ۳۰۰ اشتباه بود: وقتی بزرگ‌نمایی
     *   محدود می‌شد، تصویری با ارتفاع حروف ۱۴ پیکسل (معادل ~۱۲۰ DPI) به Tesseract
     *   می‌رسید در حالی که به آن «۳۰۰» گفته بودیم. حالا عددِ درست را می‌گوییم.
     */
    private fun applyAccuracyVariables(instance: TessBaseAPI, dpi: Int) {
        val variables = listOf(
            // فاصله‌های بین‌کلمه‌ای را همان‌طور که تشخیص داده شده نگه دار (برای متن RTL مهم است).
            "preserve_interword_spaces" to "1",
            "user_defined_dpi" to dpi.toString(),
            // قطبیت تصویر در پیش‌پردازش یکدست شده؛ تلاش دوبارهٔ Tesseract برای وارونه‌کردن
            // فقط وقت تلف می‌کند و گاهی نتیجه را بدتر می‌کند.
            "tessedit_do_invert" to "0",
        )
        val applied = variables.map { (name, value) ->
            val ok = runCatching { instance.setVariable(name, value) }.getOrDefault(false)
            if (!ok) DiagnosticLog.w(TAG, "Tesseract متغیر «$name» را نپذیرفت.")
            "$name=$value${if (ok) "" else " (رد شد!)"}"
        }
        DiagnosticLog.d(TAG, "متغیرها: ${applied.joinToString("، ")}")
    }

    /**
     * اجرای OCR روی یک تصویر باینریِ پیش‌پردازش‌شده.
     *
     * Tesseract از روی [bitmap] یک کپیِ داخلی (Pix) می‌سازد، بنابراین فراخوان می‌تواند
     * بلافاصله پس از بازگشتِ این تابع `recycle()` را صدا بزند.
     */
    /**
     * @param yOffset فاصلهٔ عمودیِ این کاشی از بالای تصویرِ کامل. مختصاتی که
     *   Tesseract می‌دهد نسبت به *همین Bitmap* است؛ با افزودن این عدد به مختصاتِ
     *   سراسری تبدیل می‌شود تا خطوطِ کاشی‌های مختلف با هم قابل‌مقایسه باشند.
     */
    fun recognise(
        bitmap: Bitmap,
        pageSegMode: Int = PageMode.DEFAULT.psm,
        dpi: Int = 300,
        yOffset: Int = 0,
        onProgress: (Int) -> Unit = {},
    ): RawOcrOutput {
        val instance = api ?: throw IllegalStateException("Engine not initialised")
        progressSink = onProgress
        val started = System.currentTimeMillis()
        DiagnosticLog.d(
            TAG,
            "شروع تشخیص — تصویر ${bitmap.width}×${bitmap.height} • PSM=$pageSegMode • DPI=$dpi",
        )
        return try {
            instance.setPageSegMode(pageSegMode)
            // متغیرها پیش از هر تصویر دوباره اعمال می‌شوند: هم DPI به تصویر وابسته است و
            // هم اگر جایی `clear()` تنظیمی را دور انداخته باشد، این کار جبرانش می‌کند.
            applyAccuracyVariables(instance, dpi)
            instance.setImage(bitmap)

            // این ترتیب عمدی و مطابق نمونهٔ رسمی کتابخانه است:
            //   • getHOCRText(0) تشخیص را اجرا می‌کند و تنها مسیری است که
            //     ProgressNotifier را صدا می‌زند و با stop() قابل قطع‌کردن است.
            //   • getUTF8Text() بعد از آن فقط نتیجهٔ آماده را برمی‌گرداند (بدون اجرای دوباره).
            // اگر مستقیم getUTF8Text() صدا زده شود، نه نوار پیشرفت کار می‌کند و نه لغو.
            instance.getHOCRText(0)
            val text = instance.getUTF8Text().orEmpty()
            val confidence = runCatching { instance.meanConfidence() }.getOrDefault(0)
            val lines = readLines(instance, text, yOffset)
            val output = RawOcrOutput(text, confidence.coerceIn(0, 100), lines)
            DiagnosticLog.d(
                TAG,
                "پایان تشخیص — ${System.currentTimeMillis() - started}ms" +
                    " • اطمینان $confidence" +
                    " • ${text.length} کاراکتر" +
                    " • ${lines.size} خط" +
                    " • ${output.strongWordCount}/${output.wordCount} کلمهٔ مطمئن",
            )
            output
        } catch (t: Throwable) {
            DiagnosticLog.e(TAG, "تشخیص با استثنا متوقف شد", t)
            throw t
        } finally {
            progressSink = null
            // آزادکردن تصویرِ داخلیِ Tesseract تا حافظهٔ بومی انباشته نشود.
            runCatching { instance.clear() }
        }
    }

    /**
     * استخراج خطوط به‌همراه اطمینانِ هر خط و شمارش کلماتِ مطمئنِ هر خط.
     *
     * دو پیمایش روی همان iterator انجام می‌شود (کتابخانه صراحتاً اجازه می‌دهد سطوح
     * مختلف را در هم بیامیزیم و `begin()` پیمایش را از نو شروع می‌کند):
     *  ۱. سطح خط — برای *متنِ اصلیِ* خط. متن را از روی کلمه‌ها بازسازی نمی‌کنیم،
     *     چون همان فاصله‌های بین‌کلمه‌ای که دنبالشان هستیم گم می‌شود.
     *  ۲. سطح کلمه — فقط برای شمارش و اطمینانِ کلمه‌ها.
     *
     * اگر iterator در دسترس نباشد، به شکستنِ سادهٔ [fallbackText] برمی‌گردیم تا
     * رأی‌گیری خط‌به‌خط در هر شرایطی کار کند.
     */
    private fun readLines(instance: TessBaseAPI, fallbackText: String, yOffset: Int): List<OcrLine> {
        val iterator = runCatching { instance.getResultIterator() }.getOrNull()
            ?: return splitFallback(fallbackText)

        return try {
            val texts = ArrayList<ScannedLine>()
            iterator.begin()
            do {
                val line = runCatching { iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) }
                    .getOrNull()
                    ?.trimEnd()
                    .orEmpty()
                if (line.isNotBlank()) {
                    val confidence = runCatching {
                        iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    }.getOrDefault(0f)
                    val box = boundingBox(iterator, yOffset)
                    texts += ScannedLine(line, confidence.toInt().coerceIn(0, 100), box.first, box.second)
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))

            if (texts.isEmpty()) return splitFallback(fallbackText)

            // پیمایش دوم: شمارش کلمه‌ها به تفکیک خط.
            val words = IntArray(texts.size)
            val strong = IntArray(texts.size)
            var index = -1
            iterator.begin()
            do {
                if (runCatching { iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) }
                        .getOrDefault(false)
                ) {
                    index++
                }
                if (index !in texts.indices) continue
                val word = runCatching { iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                if (word.isEmpty()) continue
                words[index]++
                val confidence = runCatching {
                    iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                }.getOrDefault(0f)
                if (confidence >= OcrLine.STRONG_WORD_CONFIDENCE) strong[index]++
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

            texts.mapIndexed { i, scanned ->
                OcrLine(scanned.text, scanned.confidence, words[i], strong[i], scanned.top, scanned.bottom)
            }
        } catch (t: Throwable) {
            DiagnosticLog.w(TAG, "خواندن خطوط از ResultIterator ممکن نشد؛ متن خام شکسته می‌شود.", t)
            splitFallback(fallbackText)
        } finally {
            runCatching { iterator.delete() }
        }
    }

    /** یک خطِ خوانده‌شده پیش از آنکه کلمه‌هایش شمرده شوند. */
    private data class ScannedLine(val text: String, val confidence: Int, val top: Int, val bottom: Int)

    /**
     * کادرِ محصورِ خطِ جاری در مختصاتِ تصویرِ کامل.
     *
     * `PageIterator.getBoundingBox` آرایه‌ای چهارتایی می‌دهد که در خودِ کتابخانه
     * به‌صورت `Rect(box[0], box[1], box[2], box[3])` — یعنی چپ/بالا/راست/پایین —
     * تفسیر می‌شود؛ ولی مستندِ بالای همان متد آن را «x, y, w, h» می‌نامد. برای
     * اینکه هر دو تفسیر درست کار کند، اگر عددِ چهارم از عددِ دوم کوچک‌تر بود آن را
     * *ارتفاع* می‌گیریم و به بالا اضافه می‌کنیم.
     *
     * @return جفتِ (بالا، پایین) در مختصاتِ سراسری، یا [OcrLine.NO_GEOMETRY] اگر
     *   کتابخانه مختصاتی نداد (مسیرهای جایگزین باید همچنان کار کنند).
     */
    private fun boundingBox(iterator: ResultIterator, yOffset: Int): Pair<Int, Int> {
        val box = runCatching { iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) }
            .getOrNull()
        if (box == null || box.size < 4) return OcrLine.NO_GEOMETRY to OcrLine.NO_GEOMETRY
        val top = box[1]
        val bottom = if (box[3] > box[1]) box[3] else box[1] + box[3]
        if (bottom <= top) return OcrLine.NO_GEOMETRY to OcrLine.NO_GEOMETRY
        return (top + yOffset) to (bottom + yOffset)
    }

    /**
     * مسیر جایگزین: خطوط را از خودِ متن می‌سازد. اطمینانِ خط را نداریم، پس ۰
     * می‌گذاریم — رأی‌گیری همچنان کار می‌کند و فقط تساوی‌شکنی ضعیف‌تر می‌شود.
     */
    private fun splitFallback(text: String): List<OcrLine> = text.lines()
        .map(String::trimEnd)
        .filter { it.isNotBlank() }
        .map { line ->
            val count = line.split(Regex("\\s+")).count { it.isNotBlank() }
            OcrLine(line, confidence = 0, wordCount = count, strongWordCount = 0)
        }

    /** درخواست توقف تشخیصِ در حال اجرا (برای لغو از سمت کاربر). */
    fun requestStop() {
        DiagnosticLog.i(TAG, "درخواست توقف تشخیص از سوی کاربر.")
        runCatching { api?.stop() }
    }

    override fun close() {
        if (api != null) DiagnosticLog.d(TAG, "آزادسازی موتور Tesseract.")
        api?.let { runCatching { it.recycle() } }
        api = null
        initialisedWith = null
        progressSink = null
    }
}
