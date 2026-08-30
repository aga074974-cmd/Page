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
    /** مرکز عمودیِ خط در تصویرِ کامل، یا [OcrLine.NO_GEOMETRY]. */
    val centerY: Int = OcrLine.NO_GEOMETRY,
    /** سنجه‌های دروازهٔ کیفیت — فقط برای خطوطِ تک‌رأیی محاسبه می‌شود. */
    val quality: LineQuality? = null,
)

/** خروجی کاملِ رأی‌گیری. */
data class VoteResult(
    val text: String,
    val lines: List<VotedLine>,
    val variantCount: Int,
    /** آیا خطوط بر اساس مختصاتِ Y گروه و مرتب شدند؟ (در غیر این‌صورت: ترازِ متنی) */
    val geometric: Boolean = false,
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
 * میانگین اطمینانش بالاتر می‌رفت. حالا هیچ حالتی به‌تنهایی برنده نمی‌شود؛ خطوطِ
 * متناظرِ همهٔ حالت‌ها گروه می‌شوند و هر خط جداگانه رأی‌گیری می‌شود.
 *
 * ── گروه‌بندی: مختصاتِ Y، نه اندیسِ تراز (باگ ۳) ──────────────────────────────
 * نسخهٔ اول خطوط را با ترازِ توالی (Needleman–Wunsch) روی *اندیس* جفت می‌کرد. تا
 * وقتی همهٔ حالت‌ها تعداد خطِ نزدیکی دارند این کار می‌کند، ولی وقتی یک حالت ۴۰ خط
 * درمی‌آورد و بقیه ۱۴ تا ۲۱ خط، تراز جابه‌جا می‌شود و خطی که وسطِ پاراگراف است سرِ
 * دیگری می‌نشیند.
 *
 * حالا از چیزی استفاده می‌کنیم که *به‌طور دقیق* قابل‌مقایسه است: همهٔ حالت‌ها از
 * **یک تصویرِ پیش‌پردازش‌شدهٔ واحد** و با **یک کاشی‌بندیِ واحد** خوانده می‌شوند، پس
 * مختصاتِ عمودیِ خطوطشان در یک دستگاهِ مختصاتِ مشترک است. دو خط «همان خط»اند اگر
 * بازهٔ عمودی‌شان به‌اندازهٔ کافی روی هم بیفتد — و ترتیبِ نهایی هم همان ترتیبِ
 * مرکزِ عمودی است، نه ترتیبِ تراز.
 *
 * ترازِ متنیِ قبلی به‌عنوان مسیرِ جایگزین باقی مانده: اگر Tesseract مختصات ندهد
 * (مسیرِ fallback)، همان الگوریتم قبلی اجرا می‌شود.
 *
 * ── پذیرش ────────────────────────────────────────────────────────────────────
 * خطی که دو حالت یا بیشتر دیده‌اند پذیرفته می‌شود. خطِ تک‌رأیی باید سه شرط را با هم
 * داشته باشد: از حالتِ پرت نیامده باشد (باگ ۲)، از [LineQualityGate] رد شود
 * (باگ ۱)، و اطمینانش از [SINGLETON_MIN_CONFIDENCE] کمتر نباشد.
 */
object LineVoter {

    /** کمینهٔ شباهت برای اینکه دو خط «همان خط» به حساب بیایند (مسیرِ ترازِ متنی). */
    private const val MATCH_THRESHOLD = 0.55

    /**
     * هزینهٔ ایجاد شکاف در تراز. عمداً کم است: نبودنِ یک خط در یک حالت اتفاقِ
     * عادی و دقیقاً همان چیزی است که می‌خواهیم تشخیص دهیم.
     */
    private const val GAP_PENALTY = -0.05

    /** جریمهٔ جفت‌کردنِ دو خطِ ناهمسان — از دو شکاف هم بدتر. */
    private const val MISMATCH_PENALTY = -1.0

    /**
     * خطی که فقط *یک* حالت دیده، دستِ‌کم به این اطمینان نیاز دارد.
     *
     * این شرط دیگر به‌تنهایی کافی نیست (باگ ۱ نشان داد Tesseract روی نویز هم
     * اطمینانِ ۹۶ می‌دهد)، ولی همچنان *لازم* است و ارزان‌ترین فیلتر است.
     */
    private const val SINGLETON_MIN_CONFIDENCE = 72

    /** بیشینهٔ طولِ متنی که برای سنجش شباهت مقایسه می‌شود. */
    private const val MAX_COMPARE_CHARS = 200

    /**
     * چه سهمی از ارتفاعِ کوچک‌ترِ دو خط باید روی هم بیفتد تا «همان خط» باشند.
     *
     * ۰٫۵ یعنی نیمی از خطِ کوتاه‌تر. سخت‌گیرانه‌تر از این، خطوطی را که در دو حالت
     * کمی جابه‌جا خوانده شده‌اند از هم جدا می‌کند؛ سهل‌گیرانه‌تر، دو ردیفِ چسبیده را
     * یکی می‌کند.
     */
    private const val MIN_Y_OVERLAP = 0.5

    /**
     * ★ باگ ۲ — آیا خطی که فقط *حالتِ پرت* دیده است می‌تواند پذیرفته شود؟
     *
     * پیش‌فرض `false` است، مطابق قاعده‌ای که خواسته شده: «خطوطِ تک‌رأییِ متعلق به
     * حالتِ پرت هرگز پذیرفته نشوند». بهایش این است که اگر حالتِ پرت جایی را دیده
     * باشد که بقیه کور بوده‌اند، آن خط از دست می‌رود. برای برگرداندنش کافی است
     * همین یک ثابت `true` شود؛ آن‌وقت خطِ تک‌رأییِ حالتِ پرت مثل بقیه فقط باید از
     * دروازهٔ کیفیت و آستانهٔ اطمینان رد شود.
     */
    const val ACCEPT_OUTLIER_SINGLETONS = false

    /**
     * تلفیقِ خروجی همهٔ حالت‌ها در یک متنِ واحد.
     *
     * @param variants خروجی خطیِ هر حالت. با یک عضو، همان عضو بدون تغییر برمی‌گردد.
     * @param outliers حالت‌هایی که [ModeOutlierDetector] «پرت» تشخیص داده — رأیشان
     *   فقط وقتی به حساب می‌آید که حالتِ دیگری هم آن خط را دیده باشد.
     */
    fun combine(
        variants: List<VariantLines>,
        outliers: Set<BinarizationMethod> = emptySet(),
    ): VoteResult {
        require(variants.isNotEmpty()) { "At least one variant is required" }

        if (variants.size == 1) {
            val only = variants.single()
            val lines = only.lines.map {
                VotedLine(
                    text = it.text,
                    votes = 1,
                    agreement = 1,
                    confidence = it.confidence,
                    methods = listOf(only.method),
                    accepted = true,
                    centerY = if (it.hasGeometry) it.centerY else OcrLine.NO_GEOMETRY,
                )
            }
            return VoteResult(lines.joinToString("\n") { it.text }, lines, variantCount = 1)
        }

        // مسیرِ اصلی: گروه‌بندی هندسی. فقط وقتی ممکن است که *همهٔ* حالت‌ها مختصات
        // داشته باشند؛ مخلوط‌کردنِ خطوطِ بی‌مختصات با مختصات‌دار، بدترین حالت است.
        val geometric = variants.all { variant ->
            variant.lines.isEmpty() || variant.lines.all { it.hasGeometry }
        }

        val clusters = if (geometric) clusterByY(variants) else clusterByAlignment(variants)

        val voted = clusters.map { decide(it, variants.size, outliers) }
        val text = voted.filter { it.accepted }.joinToString("\n") { it.text }
        return VoteResult(text, voted, variants.size, geometric)
    }

    // ────────────────── گروه‌بندی هندسی: هم‌پوشانیِ بازهٔ Y (باگ ۳) ──────────────────

    /**
     * گروه‌بندیِ خطوطِ همهٔ حالت‌ها بر اساس هم‌پوشانیِ عمودی، و مرتب‌سازی بر اساس Y.
     *
     * الگوریتم یک جاروبِ ساده از بالا به پایین است:
     *  ۱. همهٔ خطوطِ همهٔ حالت‌ها در یک فهرست ریخته و بر اساس لبهٔ بالا مرتب می‌شوند.
     *  ۲. هر خط یا به آخرین خوشه‌ای که به‌اندازهٔ کافی با آن هم‌پوشانی دارد می‌چسبد،
     *     یا خوشهٔ تازه‌ای می‌سازد.
     *  ۳. چون ورودی مرتب است، خروجی هم به‌ترتیبِ Y مرتب است — یعنی ترتیبِ خواندنِ
     *     واقعیِ صفحه، نه ترتیبِ اندیسِ تراز.
     *
     * چرا این کار درست است: هر پنج حالت از یک تصویر و یک کاشی‌بندی خوانده شده‌اند
     * و مختصاتشان پیش از رسیدن به اینجا با آفستِ نوار به مختصاتِ سراسری تبدیل شده،
     * پس Yها مستقیماً قابل‌مقایسه‌اند.
     *
     * فایدهٔ جانبی: اگر یک حالت به‌خاطر نویز یک ردیف را *دوبار* بخواند، هر دو نسخه
     * در یک خوشه می‌افتند و به‌جای دو خطِ تکراری، یک خط با یک رأی شمرده می‌شوند.
     */
    private fun clusterByY(variants: List<VariantLines>): List<List<LineCandidate>> {
        val all = variants
            .flatMap { variant -> variant.lines.map { LineCandidate(variant.method, it) } }
            .sortedWith(compareBy({ it.line.top }, { it.line.bottom }))

        val clusters = ArrayList<MutableList<LineCandidate>>()
        var spanTop = 0
        var spanBottom = 0

        for (candidate in all) {
            val line = candidate.line
            val current = clusters.lastOrNull()
            if (current != null && overlapRatio(spanTop, spanBottom, line.top, line.bottom) >= MIN_Y_OVERLAP) {
                current += candidate
                // بازهٔ خوشه با میانگینِ اعضا به‌روز می‌شود نه با اجتماعشان: اجتماع
                // با هر عضوِ تازه بزرگ‌تر می‌شد و کم‌کم ردیف‌های بعدی را هم می‌بلعید.
                spanTop = current.sumOf { it.line.top } / current.size
                spanBottom = current.sumOf { it.line.bottom } / current.size
            } else {
                clusters += mutableListOf(candidate)
                spanTop = line.top
                spanBottom = line.bottom
            }
        }
        return clusters
    }

    /** سهمِ هم‌پوشانیِ دو بازهٔ عمودی از ارتفاعِ کوچک‌ترشان (۰ تا ۱). */
    internal fun overlapRatio(topA: Int, bottomA: Int, topB: Int, bottomB: Int): Double {
        val overlap = min(bottomA, bottomB) - max(topA, topB)
        if (overlap <= 0) return 0.0
        val shorter = min(bottomA - topA, bottomB - topB)
        if (shorter <= 0) return 0.0
        return overlap.toDouble() / shorter
    }

    // ────────────────── مسیرِ جایگزین: ترازِ متنیِ توالی ──────────────────

    /**
     * وقتی مختصاتی در کار نیست، همان الگوریتم قبلی: کاملـ‌ترین حالت اسکلت می‌شود و
     * بقیه با Needleman–Wunsch روی *خطوط* به آن چسبانده می‌شوند.
     */
    private fun clusterByAlignment(variants: List<VariantLines>): List<List<LineCandidate>> {
        val ordered = variants.sortedByDescending { it.lines.size }
        var clusters: List<MutableList<LineCandidate>> = ordered.first().lines
            .map { mutableListOf(LineCandidate(ordered.first().method, it)) }
        for (variant in ordered.drop(1)) {
            clusters = merge(clusters, variant)
        }
        return clusters
    }

    private fun merge(
        clusters: List<MutableList<LineCandidate>>,
        variant: VariantLines,
    ): List<MutableList<LineCandidate>> {
        val lines = variant.lines
        if (lines.isEmpty()) return clusters
        if (clusters.isEmpty()) return lines.map { mutableListOf(LineCandidate(variant.method, it)) }

        val rows = clusters.size
        val cols = lines.size

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
                    merged.addFirst(clusters[r - 1])
                    r--
                }

                else -> {
                    merged.addFirst(mutableListOf(LineCandidate(variant.method, lines[c - 1])))
                    c--
                }
            }
        }
        return merged.toList()
    }

    // ──────────────────────────── رأی‌گیریِ یک خوشه ────────────────────────────

    /**
     * انتخابِ نسخهٔ برندهٔ یک خوشه و تصمیم دربارهٔ پذیرشش.
     *
     * نکتهٔ ظریفِ انتخابِ نسخه: شکلِ متعارف فاصله‌ها را حذف می‌کند، پس «خدمات را» و
     * «خدماترا» در یک گروه می‌افتند. بین اعضای یک گروه، نسخه‌ای را برمی‌داریم که
     * *بیشترین کلمهٔ جدا* را دارد — یعنی اگر حتی یک حالت فاصله را نگه داشته باشد،
     * همان برنده است.
     */
    private fun decide(
        cluster: List<LineCandidate>,
        variantCount: Int,
        outliers: Set<BinarizationMethod>,
    ): VotedLine {
        val methods = cluster.map { it.method }.distinct()
        val votes = methods.size

        // ★ باگ ۲ — رأیِ حالتِ پرت تنها وقتی می‌ارزد که هم‌جهت با دیگران باشد.
        val onlyOutliers = methods.isNotEmpty() && methods.all { it in outliers }

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
        val text = winner.line.text.trim()

        val geometry = cluster.map { it.line }.filter { it.hasGeometry }
        val centerY = if (geometry.isEmpty()) {
            OcrLine.NO_GEOMETRY
        } else {
            geometry.sumOf { it.centerY } / geometry.size
        }

        // ── تصمیم ────────────────────────────────────────────────────────────
        // حضورِ خط در دو حالت یا بیشتر، شاهدِ مستقل است: چنین خطی هیچ‌وقت رد
        // نمی‌شود. تمام سخت‌گیری‌ها فقط روی خطوطِ تک‌رأیی اعمال می‌شود.
        val minVotes = if (variantCount >= 3) 2 else 1
        val hasMajority = votes >= minVotes

        val quality = if (hasMajority) null else LineQualityGate.assess(text)

        val reason = when {
            hasMajority -> null

            onlyOutliers && !ACCEPT_OUTLIER_SINGLETONS ->
                "تنها حالتِ پرت (${methods.joinToString { it.name }}) این خط را دید"

            quality != null && !quality.looksLikeText ->
                "تک‌رأیی و شبیه متن نیست — ${quality.failure}"

            confidence < SINGLETON_MIN_CONFIDENCE ->
                "تک‌رأیی با اطمینان $confidence (کمینه $SINGLETON_MIN_CONFIDENCE)"

            else -> null
        }

        return VotedLine(
            text = text,
            votes = votes,
            agreement = agreement,
            confidence = confidence,
            methods = methods,
            accepted = reason == null,
            reason = reason,
            centerY = centerY,
            quality = quality,
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
            if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
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

        // پیش‌فیلترِ ارزان: دو خط با اختلاف طولِ بیش از دو برابر هرگز یکی نیستند.
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
