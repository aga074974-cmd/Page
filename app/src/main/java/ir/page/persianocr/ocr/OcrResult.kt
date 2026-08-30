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
    /** امتیاز ترکیبی که برای انتخاب بهترین خروجی استفاده می‌شود. */
    val score: Double,
)

/** نتیجهٔ نهایی یک اجرای کامل. */
data class OcrResult(
    val best: OcrCandidate,
    /** همهٔ نامزدها به ترتیب نزولیِ امتیاز (در حالت تک‌گذره فقط یک عضو دارد). */
    val candidates: List<OcrCandidate>,
    val elapsedMillis: Long,
)

/**
 * امتیازدهی به نامزدها.
 *
 * تکیهٔ صرف به `meanConfidence` کافی نیست: Tesseract گاهی به یک خروجیِ کوتاه و
 * پرت اطمینان بالایی می‌دهد. بنابراین اطمینان را با سه سیگنالِ ساختاری ترکیب می‌کنیم:
 *
 *  • نسبت حروف فارسی/عربی — خروجیِ درست باید عمدتاً فارسی باشد.
 *  • نسبت کاراکترهای «آشغال» — نمادهایی که در متن فارسی جایی ندارند.
 *  • طول نسبی — خروجی‌ای که نصف بقیه است، احتمالاً بخشی از متن را جا انداخته.
 *
 * Confidence alone is a poor selector; these structural signals catch the cases where
 * Tesseract is confidently wrong.
 */
object OcrCandidateScorer {

    private const val WEIGHT_CONFIDENCE = 1.0
    private const val WEIGHT_PERSIAN_RATIO = 25.0
    private const val WEIGHT_GARBAGE_RATIO = 45.0
    private const val WEIGHT_LENGTH = 20.0

    /** حروف و ارقام فارسی/عربی. */
    private fun isPersianLetter(ch: Char): Boolean = ch in 'ء'..'ۿ'

    /** کاراکترهایی که در یک متن فارسیِ سالم انتظار می‌رود. */
    private fun isExpected(ch: Char): Boolean = when {
        ch.isWhitespace() -> true
        isPersianLetter(ch) -> true
        ch.isLetterOrDigit() -> true // لاتین و ارقام هم طبیعی‌اند
        ch in "‌.,;:!?()[]{}«»\"'/\\-–—_%+=*&@#…،؛؟٪" -> true
        else -> false
    }

    /**
     * @param maxLength بلندترین طول میان همهٔ نامزدها (برای نرمال‌سازی سیگنال طول).
     */
    fun score(text: String, meanConfidence: Int, maxLength: Int): Double {
        val meaningful = text.filterNot { it.isWhitespace() }
        if (meaningful.isEmpty()) return 0.0

        val persianRatio = meaningful.count(::isPersianLetter).toDouble() / meaningful.length
        val garbageRatio = meaningful.count { !isExpected(it) }.toDouble() / meaningful.length
        val lengthRatio = if (maxLength > 0) {
            (meaningful.length.toDouble() / maxLength).coerceAtMost(1.0)
        } else {
            0.0
        }

        return WEIGHT_CONFIDENCE * meanConfidence +
            WEIGHT_PERSIAN_RATIO * persianRatio -
            WEIGHT_GARBAGE_RATIO * garbageRatio +
            WEIGHT_LENGTH * lengthRatio
    }
}
