package ir.page.persianocr.ocr

import ir.page.persianocr.image.BinarizationMethod
import kotlin.math.max
import kotlin.math.min

/** خروجی خطیِ یک حالت باینری‌سازی، ورودیِ رأی‌گیری. */
data class VariantLines(val method: BinarizationMethod, val lines: List<OcrLine>)

/** یک نسخه از یک خط، همراه با حالتی که آن را تولید کرده. */
data class LineCandidate(val method: BinarizationMethod, val line: OcrLine)

/** نتیجهٔ رأی‌گیری برای یک خط. */
data class VotedLine(
    val text: String,
    /** تعداد حالت‌هایی که این خط را (به هر شکلی) دیده‌اند. */
    val votes: Int,
    /** تعداد حالت‌هایی که دقیقاً روی همین نسخه توافق دارند. */
    val agreement: Int,
    val confidence: Int,
    val methods: List<BinarizationMethod>,
    /** آیا این خط به متن نهایی راه یافت؟ */
    val accepted: Boolean,
    /** اگر پذیرفته نشد، چرا. */
    val reason: String? = null,
)

/** خروجی کاملِ رأی‌گیری. */
data class VoteResult(
    val text: String,
    val lines: List<VotedLine>,
    val variantCount: Int,
) {
    val acceptedLines: List<VotedLine> get() = lines.filter { it.accepted }
    val rejectedLines: List<VotedLine> get() = lines.filterNot { it.accepted }
}

/**
 * ★ رأی‌گیریِ خط‌به‌خط بین حالت‌های باینری‌سازی.
 *
 * ── چرا این جایگزینِ «انتخاب بهترین حالت» شد ─────────────────────────────────
 * انتخابِ یک حالتِ برنده بر پایهٔ میانگین اطمینان، حذفِ متن را پاداش می‌داد: وقتی یک
 * حالت یک خطِ سخت را کامل جا می‌انداخت، کاراکترهای باقی‌مانده تمیزتر بودند و
 * میانگین اطمینانش بالاتر می‌رفت. در عمل SAUVOLA با اطمینان ۸۸ برنده شد در حالی که
 * یک خطِ کامل را انداخته بود و OTSU و ADAPTIVE_MEAN همان خط را درست خوانده بودند.
 *
 * حالا هیچ حالتی به‌تنهایی برنده نمی‌شود. خطوطِ متناظرِ همهٔ حالت‌ها تراز می‌شوند و
 * هر خط جداگانه رأی‌گیری می‌شود. خطی که اکثریتِ حالت‌ها دیده‌اند در متن می‌ماند، حتی
 * اگر مطمئن‌ترین حالت آن را ندیده باشد.
 *
 * ── چطور کار می‌کند ─────────────────────────────────────────────────────────
 * ۱. حالت‌ها به ترتیبِ تعداد خط مرتب می‌شوند و کاملـ‌ترین‌شان اسکلتِ اولیه می‌شود.
 * ۲. هر حالت بعدی با الگوریتم ترازِ توالی (Needleman–Wunsch) روی *خطوط* به اسکلت
 *    چسبانده می‌شود؛ معیارِ شباهت، فاصلهٔ لِوِنشتاینِ نرمال‌شده روی شکلِ متعارفِ خط است.
 * ۳. هر خوشه = یک خطِ واقعی از سند، با نسخه‌های مختلفش.
 * ۴. برندهٔ هر خوشه: نسخه‌ای که بیشترین حالت‌ها رویش توافق دارند؛ در تساوی،
 *    بالاترین اطمینانِ همان خط.
 *
 * Line-level voting across binarization modes: presence in a majority of modes
 * beats one confident-but-incomplete mode.
 */
object LineVoter {

    /** کمینهٔ شباهت برای اینکه دو خط «همان خط» به حساب بیایند. */
    private const val MATCH_THRESHOLD = 0.55

    /**
     * هزینهٔ ایجاد شکاف در تراز. عمداً کم است: نبودنِ یک خط در یک حالت اتفاقِ
     * عادی و دقیقاً همان چیزی است که می‌خواهیم تشخیص دهیم، پس تراز باید به‌راحتی
     * شکاف بپذیرد و به‌سختی دو خطِ بی‌ربط را جفت کند.
     */
    private const val GAP_PENALTY = -0.05

    /** جریمهٔ جفت‌کردنِ دو خطِ ناهمسان — از دو شکاف هم بدتر. */
    private const val MISMATCH_PENALTY = -1.0

    /**
     * خطی که فقط *یک* حالت دیده، تنها با این اطمینان پذیرفته می‌شود.
     * زیرِ این آستانه معمولاً زبالهٔ باینری‌سازی است (مثل «اب 0 روشو» در گزارش‌ها).
     */
    private const val SINGLETON_MIN_CONFIDENCE = 72

    /** بیشینهٔ طولِ متنی که برای سنجش شباهت مقایسه می‌شود (کنترل هزینهٔ لِوِنشتاین). */
    private const val MAX_COMPARE_CHARS = 200

    /**
     * تلفیقِ خروجی همهٔ حالت‌ها در یک متنِ واحد.
     *
     * @param variants خروجی خطیِ هر حالت. با یک عضو، همان عضو بدون تغییر برمی‌گردد.
     */
    fun combine(variants: List<VariantLines>): VoteResult {
        require(variants.isNotEmpty()) { "At least one variant is required" }

        if (variants.size == 1) {
            val only = variants.single()
            val lines = only.lines.map {
                VotedLine(it.text, votes = 1, agreement = 1, confidence = it.confidence, methods = listOf(only.method), accepted = true)
            }
            return VoteResult(lines.joinToString("\n") { it.text }, lines, variantCount = 1)
        }

        // کاملـ‌ترین حالت (بیشترین خط) اسکلت می‌شود تا تراز بیشترین لنگرگاه را داشته باشد.
        val ordered = variants.sortedByDescending { it.lines.size }
        var clusters: List<MutableList<LineCandidate>> = ordered.first().lines
            .map { mutableListOf(LineCandidate(ordered.first().method, it)) }

        for (variant in ordered.drop(1)) {
            clusters = merge(clusters, variant)
        }

        val voted = clusters.map { decide(it, variants.size) }
        val text = voted.filter { it.accepted }.joinToString("\n") { it.text }
        return VoteResult(text, voted, variants.size)
    }

    // ─────────────────────── تراز و ادغام یک حالت تازه ───────────────────────

    /**
     * ترازِ خطوطِ [variant] با خوشه‌های موجود و ادغامشان.
     *
     * ماتریس برنامه‌نویسی پویا دقیقاً همان Needleman–Wunsch است، فقط به‌جای
     * کاراکترها روی خطوط اجرا می‌شود.
     */
    private fun merge(
        clusters: List<MutableList<LineCandidate>>,
        variant: VariantLines,
    ): List<MutableList<LineCandidate>> {
        val lines = variant.lines
        if (lines.isEmpty()) return clusters
        if (clusters.isEmpty()) return lines.map { mutableListOf(LineCandidate(variant.method, it)) }

        val rows = clusters.size
        val cols = lines.size

        // امتیازِ جفت‌شدنِ هر خوشه با هر خط — یک بار حساب و بازاستفاده می‌شود.
        val pairScore = Array(rows) { r ->
            DoubleArray(cols) { c ->
                val similarity = clusters[r].maxOf { similarity(it.line.text, lines[c].text) }
                if (similarity >= MATCH_THRESHOLD) similarity else MISMATCH_PENALTY
            }
        }

        val dp = Array(rows + 1) { DoubleArray(cols + 1) }
        for (r in 1..rows) dp[r][0] = dp[r - 1][0] + GAP_PENALTY
        for (c in 1..cols) dp[0][c] = dp[0][c - 1] + GAP_PENALTY
        for (r in 1..rows) {
            for (c in 1..cols) {
                dp[r][c] = maxOf(
                    dp[r - 1][c - 1] + pairScore[r - 1][c - 1],
                    dp[r - 1][c] + GAP_PENALTY,
                    dp[r][c - 1] + GAP_PENALTY,
                )
            }
        }

        // بازگشت روی مسیر بهینه، از انتها به ابتدا.
        val merged = ArrayDeque<MutableList<LineCandidate>>()
        var r = rows
        var c = cols
        while (r > 0 || c > 0) {
            val diagonal = if (r > 0 && c > 0) dp[r - 1][c - 1] + pairScore[r - 1][c - 1] else Double.NEGATIVE_INFINITY
            val up = if (r > 0) dp[r - 1][c] + GAP_PENALTY else Double.NEGATIVE_INFINITY
            when {
                r > 0 && c > 0 && dp[r][c] == diagonal -> {
                    clusters[r - 1] += LineCandidate(variant.method, lines[c - 1])
                    merged.addFirst(clusters[r - 1])
                    r--; c--
                }

                r > 0 && dp[r][c] == up -> {
                    // این خوشه در حالتِ تازه دیده نشده — یعنی این حالت خط را انداخته.
                    merged.addFirst(clusters[r - 1])
                    r--
                }

                else -> {
                    // خطی که فقط این حالت دیده — به‌عنوان خوشهٔ تازه وارد می‌شود.
                    merged.addFirst(mutableListOf(LineCandidate(variant.method, lines[c - 1])))
                    c--
                }
            }
        }
        return merged.toList()
    }

    // ──────────────────────────── رأی‌گیریِ یک خوشه ────────────────────────────

    /**
     * انتخابِ نسخهٔ برندهٔ یک خوشه.
     *
     * نکتهٔ ظریف: شکلِ متعارف فاصله‌ها را حذف می‌کند، پس «خدمات را» و «خدماترا» در
     * یک گروه می‌افتند. بین اعضای یک گروه، نسخه‌ای را برمی‌داریم که *بیشترین کلمهٔ
     * جدا* را دارد — یعنی اگر حتی یک حالت فاصله را نگه داشته باشد، همان برنده است.
     */
    private fun decide(cluster: List<LineCandidate>, variantCount: Int): VotedLine {
        val votes = cluster.map { it.method }.distinct().size
        val groups = cluster.groupBy { canonical(it.line.text) }

        val winningGroup = groups.values.maxWith(
            compareBy(
                { group -> group.map { it.method }.distinct().size },
                { group -> group.maxOf { it.line.confidence } },
                { group -> group.maxOf { it.line.strongWordCount } },
            ),
        )
        val winner = winningGroup.maxWith(
            compareBy(
                // بیشترین کلمهٔ جدا: نسخه‌ای که فاصله‌ها را از دست نداده.
                { it.line.text.split(' ').count(String::isNotBlank) },
                { it.line.confidence },
                { it.line.text.length },
            ),
        )

        val agreement = winningGroup.map { it.method }.distinct().size
        val confidence = winningGroup.maxOf { it.line.confidence }

        // حضور در اکثریتِ حالت‌ها بر اطمینانِ بالای یک حالتِ ناقص اولویت دارد.
        val minVotes = if (variantCount >= 3) 2 else 1
        val accepted = votes >= minVotes || confidence >= SINGLETON_MIN_CONFIDENCE
        val reason = if (accepted) {
            null
        } else {
            "فقط $votes از $variantCount حالت این خط را دیدند و اطمینانش ($confidence) از $SINGLETON_MIN_CONFIDENCE کمتر بود"
        }

        return VotedLine(
            text = winner.line.text.trim(),
            votes = votes,
            agreement = agreement,
            confidence = confidence,
            methods = cluster.map { it.method }.distinct(),
            accepted = accepted,
            reason = reason,
        )
    }

    // ──────────────────────────── سنجش شباهت ────────────────────────────

    /**
     * شکل متعارفِ یک خط برای مقایسه: فقط حروف و ارقام.
     *
     * فاصله‌ها، نقطه‌گذاری و نیم‌فاصله عمداً حذف می‌شوند — دقیقاً همان چیزهایی که
     * OCR در آن‌ها بی‌ثبات است و نباید باعث شوند دو نسخه از یک خط، «دو خط» شمرده شوند.
     */
    internal fun canonical(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch.isLetterOrDigit() -> append(ch.lowercaseChar())
                else -> Unit
            }
        }
    }

    /**
     * شباهتِ دو خط: ۱ منهای فاصلهٔ لِوِنشتاینِ نرمال‌شده روی شکل متعارف.
     * بازهٔ خروجی ۰ تا ۱ است.
     */
    internal fun similarity(a: String, b: String): Double {
        val left = canonical(a).take(MAX_COMPARE_CHARS)
        val right = canonical(b).take(MAX_COMPARE_CHARS)
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0

        // پیش‌فیلترِ ارزان: دو خط با اختلاف طولِ بیش از دو برابر هرگز یکی نیستند،
        // پس بی‌خود ماتریس لِوِنشتاین را نمی‌سازیم.
        val longer = max(left.length, right.length)
        if (min(left.length, right.length).toDouble() / longer < MATCH_THRESHOLD) return 0.0

        return 1.0 - levenshtein(left, right).toDouble() / longer
    }

    /** فاصلهٔ ویرایشی با حافظهٔ خطی (فقط دو سطر از ماتریس نگه داشته می‌شود). */
    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
