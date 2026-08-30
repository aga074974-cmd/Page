package ir.page.persianocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.image.PreprocessResult
import ir.page.persianocr.text.PersianTextNormalizer
import ir.page.persianocr.text.PersianTextOptions
import java.io.File
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
        private const val TAG = "OcrRepository"

        /** زبان‌های موردنیاز — فارسی + عربی. */
        val LANGUAGES = listOf("fas", "ara")

        /** رشته‌ای که به `TessBaseAPI.init()` داده می‌شود. */
        const val LANGUAGE_SPEC = "fas+ara"
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

            // ── آماده‌سازی: کپی داده‌های زبان و مقداردهی موتور ──────────────────
            onProgress(OcrProgress(OcrPhase.INSTALLING_DATA, 0))
            val dataPath: File = installer.install(LANGUAGES) { name ->
                Log.i(TAG, "Copying $name from assets (first run)")
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
                    Log.d(TAG, "${method.name}: conf=${output.meanConfidence} len=${output.text.length}")
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

            onProgress(OcrProgress(OcrPhase.POSTPROCESSING, 100))
            OcrResult(
                best = candidates.first(),
                candidates = candidates,
                elapsedMillis = System.currentTimeMillis() - started,
            )
        }
    }

    /** درخواست لغو تشخیصِ در حال اجرا. */
    fun requestStop() = engine.requestStop()

    /** آزادسازی موتور — از `ViewModel.onCleared()` صدا زده می‌شود. */
    fun close() = engine.close()
}
