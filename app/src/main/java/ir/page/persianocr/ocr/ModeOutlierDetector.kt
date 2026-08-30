package ir.page.persianocr.ocr

import ir.page.persianocr.image.BinarizationMethod

/** آمارِ خلاصهٔ یک حالتِ باینری‌سازی — ورودیِ [ModeOutlierDetector]. */
data class ModeStats(
    val method: BinarizationMethod,
    val lineCount: Int,
    val meanConfidence: Int,
)

/**
 * ★ تشخیصِ «حالتِ پرت» — باگ ۲.
 *
 * ── مسئله ────────────────────────────────────────────────────────────────────
 * در گزارشِ واقعی، ADAPTIVE_GAUSSIAN از نویز و خط‌خوردگیِ کاغذ ۴۰ خط و ۴۴۶۲
 * کاراکتر درآورد، در حالی که چهار حالتِ دیگر ۱۴ تا ۲۱ خط داشتند؛ اطمینانِ کلی‌اش
 * هم ۵۳ بود در برابر ۸۳ تا ۸۷. تقریباً همهٔ خطوطِ آشغال از همین یک حالت آمدند.
 *
 * ── معیار ─────────────────────────────────────────────────────────────────────
 * یک حالت پرت است اگر **هرکدام** از این دو درست باشد:
 *  • تعداد خطش بیش از [LINE_COUNT_RATIO] برابرِ *میانهٔ* تعداد خط‌ها باشد؛
 *  • اطمینانش بیش از [CONFIDENCE_DROP] واحد زیرِ *میانهٔ* اطمینان‌ها باشد.
 *
 * از میانه استفاده می‌کنیم نه میانگین: میانگین را دقیقاً همان حالتِ پرتی که
 * می‌خواهیم پیدا کنیم به‌سمتِ خودش می‌کشد.
 *
 * ── این نشان چه می‌کند ────────────────────────────────────────────────────────
 * حالتِ پرت **حذف نمی‌شود** — ممکن است جایی که بقیه کور بوده‌اند متن را دیده باشد.
 * فقط خطوطِ *تک‌رأییِ* آن هرگز پذیرفته نمی‌شوند؛ رأیش وقتی به حساب می‌آید که
 * دستِ‌کم یک حالتِ دیگر هم همان خط را دیده باشد. (اجرا در [LineVoter]).
 */
object ModeOutlierDetector {

    /** چند برابرِ میانهٔ تعدادِ خط، «غیرعادی» شمرده می‌شود. */
    const val LINE_COUNT_RATIO = 1.5

    /** چند واحد زیرِ میانهٔ اطمینان، «غیرعادی» شمرده می‌شود. */
    const val CONFIDENCE_DROP = 20

    /** با کمتر از این تعداد حالت، «میانه» معنا ندارد و هیچ‌کس پرت شمرده نمی‌شود. */
    const val MIN_MODES = 3

    /** نتیجهٔ تحلیل: کدام حالت‌ها پرت‌اند و میانه‌ها چه بودند. */
    data class Analysis(
        val outliers: Set<BinarizationMethod>,
        val medianLines: Int,
        val medianConfidence: Int,
        /** توضیحِ خوانا برای هر حالتِ پرت — مستقیم به گزارش می‌رود. */
        val reasons: Map<BinarizationMethod, String>,
    )

    fun analyse(stats: List<ModeStats>): Analysis {
        if (stats.size < MIN_MODES) {
            return Analysis(emptySet(), medianLines = 0, medianConfidence = 0, reasons = emptyMap())
        }

        val medianLines = median(stats.map { it.lineCount })
        val medianConfidence = median(stats.map { it.meanConfidence })

        val lineCeiling = medianLines * LINE_COUNT_RATIO
        val confidenceFloor = medianConfidence - CONFIDENCE_DROP

        val reasons = LinkedHashMap<BinarizationMethod, String>()
        val strict = LinkedHashSet<BinarizationMethod>()   // هر دو معیار
        val loose = LinkedHashSet<BinarizationMethod>()    // دستِ‌کم یک معیار

        for (stat in stats) {
            val tooManyLines = medianLines > 0 && stat.lineCount > lineCeiling
            val tooLowConfidence = stat.meanConfidence < confidenceFloor
            if (!tooManyLines && !tooLowConfidence) continue

            loose += stat.method
            if (tooManyLines && tooLowConfidence) strict += stat.method

            val parts = buildList {
                if (tooManyLines) {
                    add("${stat.lineCount} خط در برابر میانهٔ $medianLines")
                }
                if (tooLowConfidence) {
                    add("اطمینان ${stat.meanConfidence} در برابر میانهٔ $medianConfidence")
                }
            }
            reasons[stat.method] = parts.joinToString(" • ")
        }

        // ── محافظ ────────────────────────────────────────────────────────────
        // اگر نیمی یا بیشترِ حالت‌ها «پرت» شوند، یعنی معیار روی این تصویر معنا
        // ندارد (مثلاً صفحه‌ای که همهٔ حالت‌ها رویش بد کار می‌کنند). در آن صورت
        // اول به معیارِ سخت‌گیرانه‌تر (هر دو شرط) پناه می‌بریم و اگر باز هم نیمی
        // از حالت‌ها پرت بودند، هیچ‌کس را کنار نمی‌گذاریم — بهتر است نویز را
        // دروازهٔ کیفیت بگیرد تا اینکه متنِ درست را دور بیندازیم.
        val selected = when {
            loose.size * 2 < stats.size -> loose
            strict.size * 2 < stats.size -> strict
            else -> emptySet()
        }
        return Analysis(
            outliers = selected,
            medianLines = medianLines,
            medianConfidence = medianConfidence,
            reasons = reasons.filterKeys { it in selected },
        )
    }

    /** میانه — برای فهرستِ زوج‌عضو، عضوِ بالاییِ وسط (نیازی به میانگین‌گیری نیست). */
    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
