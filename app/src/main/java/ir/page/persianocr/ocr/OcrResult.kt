package ir.page.persianocr.ocr

import ir.page.persianocr.image.BinarizationMethod

/** نتیجهٔ OCR روی یک حالت باینری‌سازی. */
data class OcrCandidate(
    val method: BinarizationMethod,
    /** خروجی خام Tesseract، پیش از پس‌پردازش فارسی. */
    val rawText: String,
    /** متن نهایی پس از نرمال‌سازی فارسی. */
    val text: String,
    /** میانگین اطمینان Tesseract (۰ تا ۱۰۰). */
    val meanConfidence: Int,
    /** تعداد کلماتی که اطمینانشان از [OcrLine.STRONG_WORD_CONFIDENCE] بیشتر است. */
    val strongWordCount: Int,
    val wordCount: Int,
    val lineCount: Int,
    /** امتیاز این حالت — رجوع کنید به [OcrCandidateScorer]. */
    val score: Double,
)

/** نتیجهٔ نهایی یک اجرای کامل. */
data class OcrResult(
    /**
     * متنِ تحویل‌شده به کاربر.
     *
     * در حالت چندگذره این متن حاصلِ **رأی‌گیری خط‌به‌خط** بین همهٔ حالت‌هاست، نه
     * خروجیِ دست‌نخوردهٔ یک حالتِ برنده.
     */
    val text: String,
    /** پرامتیازترین حالتِ منفرد — فقط برای نمایش آمار و مقایسه در گزارش. */
    val best: OcrCandidate,
    /** همهٔ نامزدها به ترتیب نزولیِ امتیاز. */
    val candidates: List<OcrCandidate>,
    /** جزئیات رأی‌گیری؛ در حالت تک‌گذره `null` است. */
    val vote: VoteResult? = null,
    val elapsedMillis: Long,
)

/**
 * امتیازدهی به یک حالتِ باینری‌سازی *به‌عنوان یک کل*.
 *
 * ── چرا فرمول قبلی حذف شد ────────────────────────────────────────────────────
 * فرمول قبلی وزن سنگینی به `meanConfidence` می‌داد و آن را با نسبت‌های ساختاری
 * (سهم حروف فارسی، سهم کاراکترهای آشغال، طول نسبی) ترکیب می‌کرد. مشکل بنیادی‌اش
 * این بود که **میانگینِ اطمینان، حذفِ متن را پاداش می‌دهد**: وقتی یک حالت یک خطِ
 * سختِ متن را کامل جا می‌اندازد، کاراکترهای باقی‌مانده تمیزترند و میانگین بالا
 * می‌رود. در عمل SAUVOLA با اطمینان ۸۸ و ۱۲۶۰ کاراکتر برندهٔ OTSU با اطمینان ۸۴ و
 * ۱۳۵۹ کاراکتر شد — در حالی که یک خطِ کامل را انداخته بود.
 *
 * ── فرمول تازه ───────────────────────────────────────────────────────────────
 *     score = میانگین‌اطمینان × تعدادِ کلماتِ با اطمینانِ بالای ۶۰
 *
 * ضربِ کیفیت در **کمیتِ کلماتِ قابل‌اعتماد** یعنی متنِ کوتاه‌شده دیگر پاداش نمی‌گیرد:
 * حذفِ یک خط، شمارندهٔ کلمات را پایین می‌آورد و کاهشِ آن بر افزایشِ چند واحدی
 * اطمینان می‌چربد.
 *
 * ⚠ این امتیاز دیگر **تعیین‌کنندهٔ متنِ نهایی نیست** — آن کار با [LineVoter] و
 * رأی‌گیری خط‌به‌خط انجام می‌شود. این عدد فقط برای رتبه‌بندی در گزارش و برای حالتِ
 * تک‌گذره (وقتی کاربر چندگذره را خاموش کرده) استفاده می‌شود.
 */
object OcrCandidateScorer {

    /**
     * @param meanConfidence میانگین اطمینان Tesseract (۰ تا ۱۰۰).
     * @param strongWordCount تعداد کلماتِ با اطمینانِ بالای آستانه.
     * @param wordCount کلِ کلمات — فقط وقتی استفاده می‌شود که داده‌های سطحِ کلمه در
     *   دسترس نباشد (مثلاً وقتی `ResultIterator` چیزی برنگرداند) و آن‌گاه
     *   [strongWordCount] برای همهٔ حالت‌ها صفر است و تمایزی ایجاد نمی‌کند.
     */
    fun score(meanConfidence: Int, strongWordCount: Int, wordCount: Int): Double {
        val completeness = if (strongWordCount > 0) strongWordCount else wordCount
        if (completeness <= 0) return 0.0
        return meanConfidence.toDouble() * completeness
    }
}
