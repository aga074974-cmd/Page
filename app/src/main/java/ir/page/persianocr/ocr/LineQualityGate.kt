package ir.page.persianocr.ocr

/**
 * سنجه‌های «متن‌بودن» یک خط — خروجی [LineQualityGate.assess].
 *
 * همهٔ نسبت‌ها بین ۰ و ۱ هستند و در گزارش اشکال‌یابی چاپ می‌شوند، پس اگر روزی
 * خطی اشتباه رد شد می‌شود دقیقاً دید کدام معیار آن را انداخته است.
 */
data class LineQuality(
    /** نسبتِ حروفِ فارسی/عربی به کلِ کاراکترهای غیرفاصله. */
    val letterRatio: Double,
    /** نسبتِ توکن‌های «کلمه‌مانند» به کلِ توکن‌ها. */
    val wordLikeRatio: Double,
    /** نسبتِ توکن‌های مشکوک (رقمِ تنها، نقطه‌گذاری، حرفِ تک‌افتاده). */
    val suspiciousRatio: Double,
    /** نسبتِ فاصله به کلِ طولِ خط. */
    val spaceRatio: Double,
    /** بلندترین رشتهٔ فاصله‌های پشت‌سرهم. */
    val longestSpaceRun: Int,
    /** تعداد توکن‌های کلمه‌مانند (نه نسبت). */
    val wordLikeTokens: Int,
    /** تعداد کاراکترهای غیرفاصله. */
    val nonSpaceChars: Int,
    /** آیا خط از دروازهٔ کامل رد شد؟ (فقط برای خطوطِ تک‌رأیی اعمال می‌شود) */
    val looksLikeText: Boolean,
    /**
     * آیا خط از **کفِ قاطع** رد شد؟ این یکی روی *همهٔ* خطوط اعمال می‌شود، هر چند
     * رأی که داشته باشند. ر.ک. [LineQualityGate.hardFloorFailure].
     */
    val passesHardFloor: Boolean,
    /** اگر از دروازهٔ کامل رد نشد، کدام معیار شکست — به فارسی، برای گزارش. */
    val failure: String? = null,
    /** اگر از کفِ قاطع رد نشد، چرا. */
    val hardFloorFailure: String? = null,
) {
    /** خلاصهٔ تک‌خطی برای گزارش اشکال‌یابی. */
    fun summary(): String = "حرف %.2f، کلمه %.2f (%d)، مشکوک %.2f، فاصله %.2f، رشتهٔ فاصله %d"
        .format(java.util.Locale.US, letterRatio, wordLikeRatio, wordLikeTokens, suspiciousRatio, spaceRatio, longestSpaceRun)
}

/**
 * ★ دروازهٔ کیفیتِ متن — باگ ۱.
 *
 * ── مسئله ────────────────────────────────────────────────────────────────────
 * قانونِ قبلی برای خطوطِ تک‌رأیی فقط اطمینانِ Tesseract را نگاه می‌کرد
 * (`اطمینان ≥ ۷۲`). ولی Tesseract روی نویز و خط‌خوردگیِ کاغذ هم با خیالِ راحت
 * اطمینانِ ۸۳، ۹۶ و حتی ۹۷ می‌دهد؛ «اطمینان» یعنی «مطمئنم این شکل، همین حرف
 * است»، نه «مطمئنم اینجا واقعاً متنی هست». برای همین آشغال‌هایی مثل
 * «۰ ۰۰ ی ل ۲,| ۳ و۱ ب ۱» با اطمینانِ ۸۳ پذیرفته می‌شدند.
 *
 * ── راه‌حل ────────────────────────────────────────────────────────────────────
 * به‌جای تکیه بر اطمینان، به *شکلِ خودِ متن* نگاه می‌کنیم. متنِ واقعیِ فارسی
 * ویژگی‌های آماریِ بسیار پایداری دارد که زبالهٔ باینری‌سازی ندارد. اعدادِ زیر روی
 * همان گزارشی کالیبره شده‌اند که این باگ را نشان داد: هر ۲۸ خطِ آشغالِ آن گزارش
 * رد می‌شوند و هر دو خطِ متنِ واقعی می‌مانند.
 *
 * ── دو لایه ──────────────────────────────────────────────────────────────────
 * **کفِ قاطع** ([hardFloorFailure]) روی *همهٔ* خطوط اعمال می‌شود: خطی که هیچ کلمهٔ
 * واقعی ندارد، یا کمتر از دو کلمه دارد و کوتاه هم هست، آشغال است حتی اگر چند حالت
 * دیده باشندش و اطمینانش ۹۰ باشد («,سس» در گزارش، با ۲ رأی و اطمینان ۹۰).
 *
 * **دروازهٔ کامل** ([looksLikeText]) فقط روی خطوطِ *تک‌رأیی* اعمال می‌شود. معیارهای
 * فاصله و نسبتِ کلمه عمداً روی خطوطِ چندرأیی اجرا نمی‌شوند، چون عنوان‌ها و
 * سرصفحه‌های واقعی هم رشته‌های فاصلهٔ بلند دارند: در همان گزارش «نگرش و ⟨۶۰ فاصله⟩
 * باور /۱۷» و «نگرش‌های اشتباه در دنیای ⟨۱۹ فاصله⟩ فروش» هر دو متنِ درستِ صفحه‌اند و
 * دروازهٔ کامل هر دو را می‌انداخت. ر.ک. [LineVoter.APPLY_FULL_GATE_TO_ALL_LINES].
 *
 * A text-shape gate for lines only one binarization saw: Tesseract is happily
 * confident about noise, so confidence alone cannot filter hallucinated lines.
 */
object LineQualityGate {

    /** کمینهٔ سهمِ حروف از کاراکترهای غیرفاصله. زیرِ این عدد، خط «شکلک» است نه متن. */
    const val MIN_LETTER_RATIO = 0.60

    /** کمینهٔ سهمِ توکن‌های کلمه‌مانند. */
    const val MIN_WORD_LIKE_RATIO = 0.55

    /** بیشینهٔ سهمِ توکن‌های مشکوک (رقمِ تنها، نقطه‌گذاری، حرفِ تک‌افتاده). */
    const val MAX_SUSPICIOUS_RATIO = 0.40

    /** بیشینهٔ سهمِ فاصله از طولِ خط. */
    const val MAX_SPACE_RATIO = 0.40

    /** بیشینهٔ فاصله‌های پشت‌سرهم. */
    const val MAX_SPACE_RUN = 8

    /** کمینهٔ تعدادِ مطلقِ کلمه‌های واقعی — یک کلمهٔ تنها شاهدِ کافی نیست. */
    const val MIN_WORD_LIKE_TOKENS = 2

    /**
     * خطی که کمتر از [MIN_WORD_LIKE_TOKENS] کلمهٔ واقعی دارد، اگر از این هم
     * کوتاه‌تر باشد به‌طور قاطع رد می‌شود — هر چند رأی که آورده باشد.
     */
    const val HARD_FLOOR_MIN_CHARS = 6

    /** کمینهٔ حروفِ چسبیده‌ای که یک توکن را «کلمه‌مانند» می‌کند. */
    private const val MIN_LETTER_RUN = 2

    private const val ZWNJ = '‌'

    /**
     * حروفِ متنیِ فارسی/عربی.
     *
     * از بازهٔ یونیکد استفاده می‌کنیم نه [Char.isLetter]، چون می‌خواهیم حروفِ لاتینِ
     * پراکنده‌ای که OCR از نویز درمی‌آورد («8.8[»، «7771|») هم *مشکوک* شمرده شوند،
     * نه «حرف». بازهٔ 0x0620–0x06D3 حروف را می‌گیرد و اعراب (0x064B–0x065F) و
     * ارقامِ عربی (0x0660–0x0669) و ارقامِ فارسی (0x06F0–0x06F9) را کنار می‌گذارد.
     */
    private fun isPersianLetter(ch: Char): Boolean {
        val code = ch.code
        return when {
            code in 0x0620..0x064A -> true
            code in 0x0660..0x0669 -> false // ٠١٢… رقم است، نه حرف
            code in 0x066E..0x06D3 -> code !in 0x06F0..0x06F9
            else -> false
        }
    }

    /** آیا این توکن دستِ‌کم [MIN_LETTER_RUN] حرفِ چسبیده دارد؟ (نیم‌فاصله نمی‌شکند) */
    private fun isWordLike(token: String): Boolean {
        var run = 0
        for (ch in token) {
            when {
                isPersianLetter(ch) -> {
                    run++
                    if (run >= MIN_LETTER_RUN) return true
                }
                ch == ZWNJ -> Unit // نیم‌فاصله وسطِ کلمه است، رشته را نمی‌شکند
                else -> run = 0
            }
        }
        return false
    }

    /**
     * آیا این توکن «مشکوک» است؟ یعنی چیزی که در متنِ سالم تک‌وتنها نمی‌ایستد:
     * رقمِ تنها، علامتِ تنها، یا یک حرفِ تک‌افتاده بین دو فاصله.
     */
    private fun isSuspicious(token: String): Boolean {
        if (isWordLike(token)) return false
        return true
    }

    /** سنجشِ یک خط. برای خطِ خالی، رد. */
    fun assess(text: String): LineQuality {
        // نشانه‌های جهتِ متن (RLM/LRM/ALM) نه حرف‌اند و نه فاصله؛ برای اینکه
        // نسبت‌ها را به‌هم نریزند، پیش از سنجش کنار گذاشته می‌شوند.
        val clean = text.filterNot { it.code == 0x200E || it.code == 0x200F || it.code == 0x061C }
        val trimmed = clean.trim()

        val tokens = trimmed.split(' ', '\t').filter { it.isNotBlank() }
        val wordLike = tokens.count(::isWordLike)
        val suspicious = tokens.count(::isSuspicious)

        val spaces = clean.count { it == ' ' || it == '\t' }
        val nonSpace = clean.length - spaces
        val letters = clean.count(::isPersianLetter)

        val letterRatio = if (nonSpace > 0) letters.toDouble() / nonSpace else 0.0
        val wordLikeRatio = if (tokens.isNotEmpty()) wordLike.toDouble() / tokens.size else 0.0
        val suspiciousRatio = if (tokens.isNotEmpty()) suspicious.toDouble() / tokens.size else 1.0
        val spaceRatio = if (clean.isNotEmpty()) spaces.toDouble() / clean.length else 0.0
        val longestRun = longestSpaceRun(clean)

        // ترتیبِ بررسی‌ها = ترتیبِ گویاییِ پیام. اولین شکست گزارش می‌شود.
        val failure = when {
            tokens.isEmpty() ->
                "خط خالی است"
            wordLike < MIN_WORD_LIKE_TOKENS ->
                "فقط $wordLike کلمهٔ واقعی دارد (کمینه $MIN_WORD_LIKE_TOKENS)"
            letterRatio < MIN_LETTER_RATIO ->
                "سهم حروف %.2f از %.2f کمتر است".format(java.util.Locale.US, letterRatio, MIN_LETTER_RATIO)
            wordLikeRatio < MIN_WORD_LIKE_RATIO ->
                "سهم کلمه‌های واقعی %.2f از %.2f کمتر است".format(java.util.Locale.US, wordLikeRatio, MIN_WORD_LIKE_RATIO)
            suspiciousRatio > MAX_SUSPICIOUS_RATIO ->
                "سهم توکن‌های مشکوک %.2f از %.2f بیشتر است".format(java.util.Locale.US, suspiciousRatio, MAX_SUSPICIOUS_RATIO)
            spaceRatio > MAX_SPACE_RATIO ->
                "خط پر از فاصله است (%.2f از %.2f بیشتر)".format(java.util.Locale.US, spaceRatio, MAX_SPACE_RATIO)
            longestRun > MAX_SPACE_RUN ->
                "$longestRun فاصلهٔ پشت‌سرهم دارد (بیشینه $MAX_SPACE_RUN)"
            else -> null
        }

        // ── کفِ قاطع: روی هر خطی، صرف‌نظر از تعداد رأی ────────────────────────
        val hardFloorFailure = when {
            tokens.isEmpty() -> "خط خالی است"
            wordLike == 0 -> "هیچ کلمهٔ واقعی ندارد"
            wordLike < MIN_WORD_LIKE_TOKENS && nonSpace < HARD_FLOOR_MIN_CHARS ->
                "فقط $wordLike کلمه و $nonSpace کاراکتر دارد"
            else -> null
        }

        return LineQuality(
            letterRatio = letterRatio,
            wordLikeRatio = wordLikeRatio,
            suspiciousRatio = suspiciousRatio,
            spaceRatio = spaceRatio,
            longestSpaceRun = longestRun,
            wordLikeTokens = wordLike,
            nonSpaceChars = nonSpace,
            looksLikeText = failure == null,
            passesHardFloor = hardFloorFailure == null,
            failure = failure,
            hardFloorFailure = hardFloorFailure,
        )
    }

    private fun longestSpaceRun(text: String): Int {
        var best = 0
        var run = 0
        for (ch in text) {
            if (ch == ' ' || ch == '\t') {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }
}
