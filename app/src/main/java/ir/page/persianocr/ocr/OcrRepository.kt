package ir.page.persianocr.ocr

import android.content.Context
import android.graphics.Bitmap
import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.image.PreprocessResult
import ir.page.persianocr.log.DiagnosticLog
import ir.page.persianocr.text.PersianTextNormalizer
import ir.page.persianocr.text.PersianTextOptions
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** مرحله‌ای که هم‌اکنون در حال اجراست — برای نمایش در نوار پیشرفت. */
enum class OcrPhase { INSTALLING_DATA, INITIALISING, RECOGNISING, POSTPROCESSING }

/** گزارش پیشرفت. [percent] درصدِ کلِ عملیات است (۰ تا ۱۰۰). */
data class OcrProgress(
    val phase: OcrPhase,
    val percent: Int,
    /** برچسب حالت باینری‌سازیِ در حال پردازش (فقط در فاز RECOGNISING). */
    val variantLabel: String? = null,
)

/**
 * لایهٔ هماهنگ‌کنندهٔ OCR: نصب داده‌های زبان، مقداردهی موتور، اجرای چندگذره و پس‌پردازش.
 *
 * تمام کارهای سنگین روی [dispatcher] (پیش‌فرض: [Dispatchers.Default]) اجرا می‌شوند و
 * یک [Mutex] تضمین می‌کند که هرگز دو فراخوانیِ هم‌زمان به Tesseract (که thread-safe
 * نیست) انجام نشود.
 */
class OcrRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    companion object {
        private const val TAG = "OCR"

        /** زبان‌های موردنیاز — فارسی + عربی. */
        val LANGUAGES = listOf("fas", "ara")

        /** رشته‌ای که به `TessBaseAPI.init()` داده می‌شود. */
        const val LANGUAGE_SPEC = "fas+ara"

        /** نیم‌فاصله — فقط برای شمارش در گزارش. */
        private const val ZWNJ = '\u200C'
    }

    private val installer = TessDataInstaller(context)
    private val engine = TesseractEngine()
    private val mutex = Mutex()

    /**
     * اجرای کامل OCR روی خروجی پیش‌پردازش.
     *
     * @param preprocessed خروجی خط لولهٔ OpenCV.
     * @param methods حالت‌هایی که باید امتحان شوند. برای «چندگذره» همهٔ حالت‌ها و برای
     *   اجرای سریع فقط حالت انتخابی کاربر را بدهید.
     * @param pageSegMode یکی از [TesseractEngine.PSM_AUTO] یا [TesseractEngine.PSM_SINGLE_BLOCK].
     */
    suspend fun recognise(
        preprocessed: PreprocessResult,
        methods: List<BinarizationMethod>,
        pageSegMode: Int = TesseractEngine.PSM_AUTO,
        textOptions: PersianTextOptions = PersianTextOptions(),
        onProgress: (OcrProgress) -> Unit = {},
    ): OcrResult = withContext(dispatcher) {
        require(methods.isNotEmpty()) { "At least one binarization method is required" }
        mutex.withLock {
            val started = System.currentTimeMillis()

            DiagnosticLog.section("اجرای OCR")
            DiagnosticLog.i(
                TAG,
                "حالت‌ها: ${methods.joinToString { it.name }}" +
                    " • PSM=$pageSegMode" +
                    " • تصویر ${preprocessed.width}×${preprocessed.height}",
            )
            DiagnosticLog.d(TAG, "گزینه‌های پس‌پردازش فارسی: $textOptions")

            // ── آماده‌سازی: کپی داده‌های زبان و مقداردهی موتور ──────────────────
            onProgress(OcrProgress(OcrPhase.INSTALLING_DATA, 0))
            val dataPath: File = installer.install(LANGUAGES) { name ->
                DiagnosticLog.i(TAG, "کپی $name از assets (فقط بار اول)")
                onProgress(OcrProgress(OcrPhase.INSTALLING_DATA, 0))
            }

            ensureActive()
            onProgress(OcrProgress(OcrPhase.INITIALISING, 2))
            engine.init(dataPath, LANGUAGE_SPEC)

            // ── تشخیص روی هر حالت ────────────────────────────────────────────
            val raw = ArrayList<Triple<BinarizationMethod, String, Int>>(methods.size)
            methods.forEachIndexed { index, method ->
                ensureActive()
                var bitmap: Bitmap? = null
                try {
                    bitmap = preprocessed.toBitmap(method)
                    val output = engine.recognise(bitmap, pageSegMode) { enginePercent ->
                        // درصدِ کل = سهم گذره‌های تمام‌شده + پیشرفت گذرهٔ جاری
                        val overall = 5 + (92.0 * (index + enginePercent / 100.0) / methods.size)
                        onProgress(
                            OcrProgress(OcrPhase.RECOGNISING, overall.toInt(), method.label),
                        )
                    }
                    raw += Triple(method, output.text, output.meanConfidence)
                    DiagnosticLog.i(
                        TAG,
                        "گذرهٔ ${index + 1}/${methods.size} — ${method.name}:" +
                            " اطمینان ${output.meanConfidence}" +
                            " • ${output.text.length} کاراکتر",
                    )
                    DiagnosticLog.d(TAG, "خام[${method.name}]: ${preview(output.text)}")
                } catch (t: Throwable) {
                    DiagnosticLog.e(TAG, "گذرهٔ ${method.name} شکست خورد", t)
                    throw t
                } finally {
                    bitmap?.recycle()
                }
            }

            // ── پس‌پردازش فارسی و امتیازدهی ───────────────────────────────────
            ensureActive()
            onProgress(OcrProgress(OcrPhase.POSTPROCESSING, 98))

            val normalised = raw.map { (method, text, confidence) ->
                Triple(method, PersianTextNormalizer.normalise(text, textOptions), confidence)
            }
            val maxLength = normalised.maxOf { it.second.filterNot(Char::isWhitespace).length }

            val candidates = normalised.mapIndexed { index, (method, text, confidence) ->
                OcrCandidate(
                    method = method,
                    rawText = raw[index].second,
                    text = text,
                    meanConfidence = confidence,
                    score = OcrCandidateScorer.score(text, confidence, maxLength),
                )
            }.sortedByDescending { it.score }

            logCandidates(candidates)

            onProgress(OcrProgress(OcrPhase.POSTPROCESSING, 100))
            val result = OcrResult(
                best = candidates.first(),
                candidates = candidates,
                elapsedMillis = System.currentTimeMillis() - started,
            )
            DiagnosticLog.i(
                TAG,
                "برگزیده: ${result.best.method.name}" +
                    " • امتیاز ${"%.1f".format(Locale.US, result.best.score)}" +
                    " • ${result.elapsedMillis}ms در کل",
            )
            result
        }
    }

    /**
     * جدولِ مقایسهٔ نامزدها — قلبِ اشکال‌یابیِ دقت.
     *
     * وقتی خروجی نهایی بد است، این جدول نشان می‌دهد که آیا حالتِ بهتری وجود داشته و
     * امتیازدهی اشتباه انتخاب کرده، یا اصلاً هیچ حالتی متن درست را نگرفته است.
     */
    private fun logCandidates(candidates: List<OcrCandidate>) {
        DiagnosticLog.i(TAG, "── مقایسهٔ نامزدها (به ترتیب امتیاز) ──")
        candidates.forEachIndexed { rank, candidate ->
            DiagnosticLog.i(
                TAG,
                String.format(
                    Locale.US,
                    "%d) %-18s امتیاز %7.1f | اطمینان %3d | %4d کاراکتر | %3d خط",
                    rank + 1,
                    candidate.method.name,
                    candidate.score,
                    candidate.meanConfidence,
                    candidate.text.length,
                    candidate.text.count { it == '\n' } + 1,
                ),
            )
        }

        // اثرِ پس‌پردازش فارسی روی متنِ برگزیده: اگر خروجی نهایی مشکل نگارشی دارد،
        // مقایسهٔ این دو خط نشان می‌دهد ایراد از Tesseract است یا از نرمال‌سازی.
        val best = candidates.first()
        DiagnosticLog.d(TAG, "پیش از پس‌پردازش: ${preview(best.rawText)}")
        DiagnosticLog.d(TAG, "پس از پس‌پردازش:  ${preview(best.text)}")
        DiagnosticLog.d(
            TAG,
            "تغییر طول در پس‌پردازش: ${best.rawText.length} → ${best.text.length} کاراکتر" +
                " • نیم‌فاصله‌های افزوده‌شده: ${best.text.count { it == ZWNJ } - best.rawText.count { it == ZWNJ }}",
        )
    }

    /**
     * نمایهٔ کوتاه و تک‌خطیِ یک متن برای گزارش.
     * فقط بخش کوچکی از متن ثبت می‌شود — هم برای خوانایی گزارش و هم برای اینکه
     * کل محتوای سند کاربر بی‌دلیل در فایل گزارش تکرار نشود.
     */
    private fun preview(text: String, maxChars: Int = 220): String {
        val flat = text.replace('\n', '⏎').replace('\r', ' ').trim()
        return if (flat.length <= maxChars) {
            "«$flat»"
        } else {
            "«${flat.take(maxChars)}…» (+${flat.length - maxChars} کاراکتر دیگر)"
        }
    }

    /** درخواست لغو تشخیصِ در حال اجرا. */
    fun requestStop() = engine.requestStop()

    /** نسخهٔ کتابخانهٔ بومی Tesseract — پس از مقداردهی معتبر است. */
    fun engineVersion(): String = engine.engineVersion()

    /** آزادسازی موتور — از `ViewModel.onCleared()` صدا زده می‌شود. */
    fun close() = engine.close()
}
