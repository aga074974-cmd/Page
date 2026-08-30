package ir.page.persianocr.ocr

import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File

/** خطای مقداردهی موتور OCR. */
class TesseractInitException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** خروجی خام یک گذرِ OCR. */
data class RawOcrOutput(val text: String, val meanConfidence: Int)

/**
 * پوشش نازکی روی [TessBaseAPI].
 *
 * ⚠ [TessBaseAPI] اصلاً thread-safe نیست. همهٔ فراخوانی‌ها باید از یک نخ (یا با قفل)
 * انجام شوند؛ این کار در [OcrRepository] با یک Mutex تضمین شده است.
 */
class TesseractEngine : Closeable {

    companion object {
        private const val TAG = "TesseractEngine"

        /** حالت پیش‌فرض قطعه‌بندی صفحه: تشخیص خودکار چیدمان. */
        const val PSM_AUTO = TessBaseAPI.PageSegMode.PSM_AUTO

        /** برای متن پاراگرافیِ یکدست، این حالت معمولاً دقیق‌تر است. */
        const val PSM_SINGLE_BLOCK = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
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
        if (initialisedWith == languages && api != null) return
        close()

        val notifier = TessBaseAPI.ProgressNotifier { values ->
            progressSink?.invoke(values.getPercent().coerceIn(0, 100))
        }

        val instance = try {
            TessBaseAPI(notifier)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError روی ABI پشتیبانی‌نشده اینجا ظاهر می‌شود.
            throw TesseractInitException("Cannot create TessBaseAPI (native library missing?)", t)
        }

        val ok = try {
            // OEM_LSTM_ONLY: فقط موتور عصبیِ Tesseract 5 — برای خط فارسی به‌مراتب دقیق‌تر
            // از موتور کلاسیک است و مدل‌های tessdata_best هم برای همین ساخته شده‌اند.
            instance.init(dataPath.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)
        } catch (t: Throwable) {
            runCatching { instance.recycle() }
            throw TesseractInitException("TessBaseAPI.init threw", t)
        }

        if (!ok) {
            runCatching { instance.recycle() }
            throw TesseractInitException(
                "TessBaseAPI.init returned false for languages='$languages' at ${dataPath.absolutePath}",
            )
        }

        applyAccuracyVariables(instance)

        api = instance
        initialisedWith = languages
        Log.i(TAG, "Tesseract ${engineVersion()} initialised with '$languages'")
    }

    /**
     * تنظیم‌های ریزی که روی دقتِ متن فارسی اثر مستقیم دارند.
     */
    private fun applyAccuracyVariables(instance: TessBaseAPI) {
        // فاصله‌های بین‌کلمه‌ای را همان‌طور که تشخیص داده شده نگه دار (برای متن RTL مهم است).
        instance.setVariable("preserve_interword_spaces", "1")
        // ما خودمان تصویر را تا ~۳۰۰ DPI بزرگ کرده‌ایم؛ به Tesseract هم همین را می‌گوییم
        // تا هشدار «DPI نامشخص» ندهد و تخمین اندازهٔ قلمش درست باشد.
        instance.setVariable("user_defined_dpi", "300")
        // قطبیت تصویر در پیش‌پردازش یکدست شده؛ تلاش دوبارهٔ Tesseract برای وارونه‌کردن
        // فقط وقت تلف می‌کند و گاهی نتیجه را بدتر می‌کند.
        instance.setVariable("tessedit_do_invert", "0")
    }

    /**
     * اجرای OCR روی یک تصویر باینریِ پیش‌پردازش‌شده.
     *
     * Tesseract از روی [bitmap] یک کپیِ داخلی (Pix) می‌سازد، بنابراین فراخوان می‌تواند
     * بلافاصله پس از بازگشتِ این تابع `recycle()` را صدا بزند.
     */
    fun recognise(
        bitmap: Bitmap,
        pageSegMode: Int = PSM_AUTO,
        onProgress: (Int) -> Unit = {},
    ): RawOcrOutput {
        val instance = api ?: throw IllegalStateException("Engine not initialised")
        progressSink = onProgress
        return try {
            instance.setPageSegMode(pageSegMode)
            instance.setImage(bitmap)

            // این ترتیب عمدی و مطابق نمونهٔ رسمی کتابخانه است:
            //   • getHOCRText(0) تشخیص را اجرا می‌کند و تنها مسیری است که
            //     ProgressNotifier را صدا می‌زند و با stop() قابل قطع‌کردن است.
            //   • getUTF8Text() بعد از آن فقط نتیجهٔ آماده را برمی‌گرداند (بدون اجرای دوباره).
            // اگر مستقیم getUTF8Text() صدا زده شود، نه نوار پیشرفت کار می‌کند و نه لغو.
            instance.getHOCRText(0)
            val text = instance.getUTF8Text().orEmpty()
            val confidence = runCatching { instance.meanConfidence() }.getOrDefault(0)
            RawOcrOutput(text, confidence.coerceIn(0, 100))
        } finally {
            progressSink = null
            // آزادکردن تصویرِ داخلیِ Tesseract تا حافظهٔ بومی انباشته نشود.
            runCatching { instance.clear() }
        }
    }

    /** درخواست توقف تشخیصِ در حال اجرا (برای لغو از سمت کاربر). */
    fun requestStop() {
        runCatching { api?.stop() }
    }

    override fun close() {
        api?.let { runCatching { it.recycle() } }
        api = null
        initialisedWith = null
        progressSink = null
    }
}
