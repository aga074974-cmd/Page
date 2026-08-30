package ir.page.persianocr.ocr

import android.content.Context
import android.graphics.Bitmap
import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.image.PreprocessResult
import ir.page.persianocr.log.DiagnosticLog
import ir.page.persianocr.text.AssetLexicon
import ir.page.persianocr.text.CorrectionKind
import ir.page.persianocr.text.PersianSpellCorrector
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
enum class OcrPhase { INSTALLING_DATA, INITIALISING, RECOGNISING, VOTING, POSTPROCESSING }

/** گزارش پیشرفت. [percent] درصدِ کلِ عملیات است (۰ تا ۱۰۰). */
data class OcrProgress(
    val phase: OcrPhase,
    val percent: Int,
    /** برچسب حالت باینری‌سازیِ در حال پردازش (فقط در فاز RECOGNISING). */
    val variantLabel: String? = null,
)

/**
 * لایهٔ هماهنگ‌کنندهٔ OCR: نصب داده‌های زبان، مقداردهی موتور، اجرای چندگذره،
 * رأی‌گیری خط‌به‌خط و پس‌پردازش.
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
        private const val ZWNJ = '‌'

        /** بیشینهٔ تعدادِ اصلاحِ واژگانی که تک‌تک در گزارش می‌آید. */
        private const val MAX_LOGGED_CORRECTIONS = 40
    }

    private val installer = TessDataInstaller(context)
    private val engine = TesseractEngine()
    private val mutex = Mutex()

    /**
     * اجرای کامل OCR روی خروجی پیش‌پردازش.
     *
     * @param preprocessed خروجی خط لولهٔ OpenCV.
     * @param methods حالت‌هایی که باید امتحان شوند. با بیش از یک حالت، متن نهایی از
     *   **رأی‌گیری خط‌به‌خط** ساخته می‌شود، نه از انتخابِ یک حالتِ برنده.
     * @param pageMode حالت قطعه‌بندی صفحه.
     * @param maxBitmapPixels سقفِ اندازهٔ هر Bitmapی که به Tesseract داده می‌شود؛
     *   تصویرِ بزرگ‌تر از این به نوارهای افقی کاشی‌بندی می‌شود.
     */
    suspend fun recognise(
        preprocessed: PreprocessResult,
        methods: List<BinarizationMethod>,
        pageMode: PageMode = PageMode.DEFAULT,
        maxBitmapPixels: Long = Long.MAX_VALUE,
        textOptions: PersianTextOptions = PersianTextOptions(),
        onProgress: (OcrProgress) -> Unit = {},
    ): OcrResult = withContext(dispatcher) {
        require(methods.isNotEmpty()) { "At least one binarization method is required" }
        mutex.withLock {
            val started = System.currentTimeMillis()

            // ── آماده‌سازی ────────────────────────────────────────────────────
            val bands = preprocessed.bands(maxBitmapPixels)
            val dpi = TesseractEngine.effectiveDpi(preprocessed.estimatedCharHeightPx)

            DiagnosticLog.section("اجرای OCR")
            DiagnosticLog.i(
                TAG,
                "حالت‌ها: ${methods.joinToString { it.name }}" +
                    " • ${pageMode.name} (PSM=${pageMode.psm})" +
                    " • تصویر ${preprocessed.width}×${preprocessed.height}" +
                    " • DPI مؤثر $dpi",
            )
            if (bands.size > 1) {
                DiagnosticLog.i(
                    TAG,
                    "تصویر به ${bands.size} نوار افقی کاشی‌بندی شد (برش فقط در ردیف‌های خالی): " +
                        bands.joinToString { "${it.first}–${it.last}" },
                )
            }
            DiagnosticLog.d(TAG, "گزینه‌های پس‌پردازش فارسی: $textOptions")

            onProgress(OcrProgress(OcrPhase.INSTALLING_DATA, 0))
            val dataPath: File = installer.install(LANGUAGES) { name ->
                DiagnosticLog.i(TAG, "کپی $name از assets (فقط بار اول)")
                onProgress(OcrProgress(OcrPhase.INSTALLING_DATA, 0))
            }

            ensureActive()
            onProgress(OcrProgress(OcrPhase.INITIALISING, 2))
            engine.init(dataPath, LANGUAGE_SPEC)

            // ── تشخیص روی هر حالت (و هر کاشی) ─────────────────────────────────
            val outputs = LinkedHashMap<BinarizationMethod, RawOcrOutput>()
            val totalUnits = methods.size * bands.size
            var unitsDone = 0

            for (method in methods) {
                val texts = ArrayList<String>(bands.size)
                val lines = ArrayList<OcrLine>()
                var weightedConfidence = 0.0
                var weight = 0

                for ((bandIndex, band) in bands.withIndex()) {
                    ensureActive()
                    var bitmap: Bitmap? = null
                    val label = if (bands.size == 1) {
                        method.label
                    } else {
                        "${method.label} — کاشی ${bandIndex + 1}/${bands.size}"
                    }
                    try {
                        bitmap = preprocessed.toBitmap(method, band)
                        val done = unitsDone
                        // آفستِ نوار: مختصاتی که Tesseract می‌دهد نسبت به Bitmapِ همین
                        // کاشی است. با افزودن این عدد، مختصاتِ همهٔ کاشی‌ها و همهٔ
                        // حالت‌ها در یک دستگاهِ مشترک قرار می‌گیرد — دقیقاً چیزی که
                        // گروه‌بندیِ مبتنی بر Y به آن نیاز دارد (باگ ۳).
                        val yOffset = preprocessed.bandYOffset(band)
                        val output = engine.recognise(bitmap, pageMode.psm, dpi, yOffset) { enginePercent ->
                            val overall = 5 + (92.0 * (done + enginePercent / 100.0) / totalUnits)
                            onProgress(OcrProgress(OcrPhase.RECOGNISING, overall.toInt(), label))
                        }
                        if (output.text.isNotBlank()) texts += output.text.trim()
                        lines += output.lines
                        // میانگینِ وزنیِ اطمینان بر حسب تعداد خط، تا کاشی‌های کوچک
                        // وزنِ یک صفحهٔ کامل پیدا نکنند.
                        val bandWeight = output.lines.size.coerceAtLeast(1)
                        weightedConfidence += output.meanConfidence.toDouble() * bandWeight
                        weight += bandWeight
                    } catch (t: Throwable) {
                        DiagnosticLog.e(TAG, "گذرهٔ ${method.name} (کاشی ${bandIndex + 1}) شکست خورد", t)
                        throw t
                    } finally {
                        bitmap?.recycle()
                        unitsDone++
                    }
                }

                val merged = RawOcrOutput(
                    text = texts.joinToString("\n"),
                    meanConfidence = if (weight > 0) (weightedConfidence / weight).toInt() else 0,
                    lines = lines,
                )
                outputs[method] = merged
                DiagnosticLog.i(
                    TAG,
                    "${method.name}: اطمینان ${merged.meanConfidence}" +
                        " • ${merged.text.length} کاراکتر" +
                        " • ${merged.lines.size} خط" +
                        " • ${merged.strongWordCount}/${merged.wordCount} کلمهٔ مطمئن",
                )
                DiagnosticLog.d(TAG, "خام[${method.name}]: ${preview(merged.text)}")
            }

            // ── رأی‌گیری خط‌به‌خط ──────────────────────────────────────────────
            ensureActive()
            onProgress(OcrProgress(OcrPhase.VOTING, 96))

            val candidates = buildCandidates(outputs, textOptions)

            // ★ باگ ۲ — حالت‌هایی که آمارشان به‌شکل غیرعادی از میانه فاصله دارد،
            // پیش از رأی‌گیری علامت می‌خورند. حذف نمی‌شوند؛ فقط رأیِ تک‌نفره‌شان
            // اعتبار ندارد.
            val outliers = if (methods.size > 1) {
                ModeOutlierDetector.analyse(
                    outputs.map { (method, output) ->
                        ModeStats(method, output.lines.size, output.meanConfidence)
                    },
                ).also(::logOutliers)
            } else {
                ModeOutlierDetector.Analysis(emptySet(), 0, 0, emptyMap())
            }

            val vote = if (methods.size > 1) {
                LineVoter.combine(
                    outputs.map { (method, output) -> VariantLines(method, output.lines) },
                    outliers.outliers,
                ).also(::logVote)
            } else {
                null
            }

            // در حالت چندگذره متنِ نهایی حاصلِ رأی‌گیری است؛ در حالت تک‌گذره همان
            // خروجیِ تنها حالتِ اجراشده.
            val chosenRaw = vote?.text?.takeIf { it.isNotBlank() } ?: candidates.first().rawText

            ensureActive()
            onProgress(OcrProgress(OcrPhase.POSTPROCESSING, 98))
            val normalised = PersianTextNormalizer.normalise(chosenRaw, textOptions)
            // ★ باگ ۴ — ماژولِ مستقلِ اصلاحِ کاراکتری. پس از یکسان‌سازی اجرا می‌شود
            // تا فرهنگ با حروفِ فارسیِ استاندارد مقایسه شود، و اگر خاموش باشد
            // فرهنگ اصلاً از assets خوانده نمی‌شود.
            val finalText = applyLexiconCorrection(normalised, textOptions)

            logCandidates(candidates, vote)
            logPostProcessing(chosenRaw, finalText)

            onProgress(OcrProgress(OcrPhase.POSTPROCESSING, 100))
            val result = OcrResult(
                text = finalText,
                best = candidates.first(),
                candidates = candidates,
                vote = vote,
                elapsedMillis = System.currentTimeMillis() - started,
            )
            DiagnosticLog.i(
                TAG,
                if (vote == null) {
                    "خروجی از حالت ${result.best.method.name} • ${result.elapsedMillis}ms در کل"
                } else {
                    "خروجی از رأی‌گیری ${vote.variantCount} حالت:" +
                        " ${vote.acceptedLines.size} خط پذیرفته، ${vote.rejectedLines.size} خط رد شد" +
                        " • ${result.elapsedMillis}ms در کل"
                },
            )
            result
        }
    }

    // ─────────────────────── اصلاحِ واژگانی (باگ ۴) ───────────────────────

    /**
     * اجرای [ConfusionCorrector] اگر کاربر روشنش کرده باشد.
     *
     * خاموش که باشد، هیچ فایلی از assets خوانده نمی‌شود و هیچ هزینه‌ای ندارد.
     */
    private fun applyLexiconCorrection(text: String, options: PersianTextOptions): String {
        if (!options.correctWithLexicon || text.isBlank()) return text

        val lexicon = AssetLexicon.load(context)
        if (lexicon.size == 0) return text

        val report = DiagnosticLog.timed(TAG, "اصلاح املایی") {
            PersianSpellCorrector.correct(text, lexicon)
        }
        DiagnosticLog.i(
            TAG,
            "اصلاح املایی: ${report.count} تغییر" +
                " (${CorrectionKind.SUBSTITUTION.label} ${report.countOf(CorrectionKind.SUBSTITUTION)}" +
                "، ${CorrectionKind.SPLIT.label} ${report.countOf(CorrectionKind.SPLIT)}" +
                "، ${CorrectionKind.MERGE.label} ${report.countOf(CorrectionKind.MERGE)}" +
                "، ${CorrectionKind.ZWNJ.label} ${report.countOf(CorrectionKind.ZWNJ)})" +
                " • ${report.unresolved} واژهٔ ناشناخته دست‌نخورده ماند" +
                " • فرهنگ ${lexicon.size} واژه",
        )
        // فهرستِ تغییرها کوتاه نگه داشته می‌شود تا گزارش پر نشود.
        report.corrections.take(MAX_LOGGED_CORRECTIONS).forEach {
            DiagnosticLog.d(TAG, "  [${it.kind.label}] «${it.before}» → «${it.after}»")
        }
        if (report.count > MAX_LOGGED_CORRECTIONS) {
            DiagnosticLog.d(TAG, "  … و ${report.count - MAX_LOGGED_CORRECTIONS} تغییرِ دیگر")
        }
        return report.text
    }

    // ─────────────────────────── ساخت نامزدها ───────────────────────────

    private fun buildCandidates(
        outputs: Map<BinarizationMethod, RawOcrOutput>,
        textOptions: PersianTextOptions,
    ): List<OcrCandidate> = outputs.map { (method, output) ->
        OcrCandidate(
            method = method,
            rawText = output.text,
            text = PersianTextNormalizer.normalise(output.text, textOptions),
            meanConfidence = output.meanConfidence,
            strongWordCount = output.strongWordCount,
            wordCount = output.wordCount,
            lineCount = output.lines.size,
            score = OcrCandidateScorer.score(output.meanConfidence, output.strongWordCount, output.wordCount),
        )
    }.sortedByDescending { it.score }

    // ─────────────────────────── گزارش‌ها ───────────────────────────

    /**
     * جدولِ مقایسهٔ نامزدها — قلبِ اشکال‌یابیِ دقت.
     *
     * حالا ستون «کلمهٔ مطمئن» هم هست: همان چیزی که امتیاز را می‌سازد و نشان می‌دهد
     * چرا یک حالتِ با اطمینانِ بالاترْ لزوماً برنده نیست.
     */
    private fun logCandidates(candidates: List<OcrCandidate>, vote: VoteResult?) {
        DiagnosticLog.i(TAG, "── مقایسهٔ حالت‌ها (امتیاز = اطمینان × کلمهٔ مطمئن) ──")
        candidates.forEachIndexed { rank, candidate ->
            DiagnosticLog.i(
                TAG,
                String.format(
                    Locale.US,
                    "%d) %-18s امتیاز %9.0f | اطمینان %3d | کلمهٔ مطمئن %4d/%4d | %4d کاراکتر | %3d خط",
                    rank + 1,
                    candidate.method.name,
                    candidate.score,
                    candidate.meanConfidence,
                    candidate.strongWordCount,
                    candidate.wordCount,
                    candidate.text.length,
                    candidate.lineCount,
                ),
            )
        }
        if (vote != null) {
            DiagnosticLog.i(
                TAG,
                "توجه: متن نهایی از هیچ‌یک از این حالت‌ها به‌تنهایی نمی‌آید — خط‌به‌خط رأی‌گیری شده است.",
            )
        }
    }

    /** ثبتِ حالت‌هایی که «پرت» شناخته شدند و دلیلش. */
    private fun logOutliers(analysis: ModeOutlierDetector.Analysis) {
        if (analysis.outliers.isEmpty()) {
            DiagnosticLog.i(
                TAG,
                "حالتِ پرتی پیدا نشد (میانه: ${analysis.medianLines} خط،" +
                    " اطمینان ${analysis.medianConfidence}).",
            )
            return
        }
        DiagnosticLog.i(
            TAG,
            "── حالتِ پرت: ${analysis.outliers.joinToString { it.name }} ──" +
                " (میانه: ${analysis.medianLines} خط، اطمینان ${analysis.medianConfidence})",
        )
        analysis.reasons.forEach { (method, reason) ->
            DiagnosticLog.i(TAG, "  ${method.name}: $reason")
        }
        DiagnosticLog.i(
            TAG,
            "رأیِ این حالت‌ها فقط وقتی شمرده می‌شود که حالتِ دیگری هم همان خط را دیده باشد.",
        )
    }

    /** ثبتِ کاملِ نتیجهٔ رأی‌گیری: هر خط، چند رأی آورد و از کدام حالت‌ها. */
    private fun logVote(vote: VoteResult) {
        DiagnosticLog.i(
            TAG,
            "── رأی‌گیری خط‌به‌خط: ${vote.lines.size} خطِ گروه‌شده بین ${vote.variantCount} حالت" +
                " (${if (vote.geometric) "بر اساس مختصات Y" else "بر اساس ترازِ متنی"}) ──",
        )
        vote.lines.forEachIndexed { index, line ->
            val mark = if (line.accepted) "✓" else "✗"
            DiagnosticLog.d(
                TAG,
                String.format(Locale.US, "%s خط %02d | %d/%d رأی", mark, index + 1, line.votes, vote.variantCount) +
                    " | توافق ${line.agreement} | اطمینان ${line.confidence}" +
                    (if (line.centerY != OcrLine.NO_GEOMETRY) " | y=${line.centerY}" else "") +
                    " | ${line.methods.joinToString(",") { it.name.take(4) }}" +
                    " | ${preview(line.text, 90)}" +
                    (line.quality?.let { " | کیفیت: ${it.summary()}" } ?: "") +
                    (line.reason?.let { " | رد: $it" } ?: ""),
            )
        }

        // خطوطی که فقط بعضی حالت‌ها دیده‌اند، دقیقاً همان جایی است که باگِ قبلی
        // متن را می‌انداخت. جدا هم گزارششان می‌کنیم تا زود به چشم بیاید.
        val recovered = vote.acceptedLines.filter { it.votes < vote.variantCount }
        if (recovered.isNotEmpty()) {
            DiagnosticLog.i(
                TAG,
                "${recovered.size} خط با رأی اکثریت نگه داشته شد که دستِ‌کم یک حالت آن را انداخته بود.",
            )
        }
    }

    private fun logPostProcessing(raw: String, normalised: String) {
        DiagnosticLog.d(TAG, "پیش از پس‌پردازش: ${preview(raw)}")
        DiagnosticLog.d(TAG, "پس از پس‌پردازش:  ${preview(normalised)}")
        DiagnosticLog.d(
            TAG,
            "تغییر طول در پس‌پردازش: ${raw.length} → ${normalised.length} کاراکتر" +
                " • نیم‌فاصله‌های افزوده‌شده: ${normalised.count { it == ZWNJ } - raw.count { it == ZWNJ }}",
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
