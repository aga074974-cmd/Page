package ir.page.persianocr.text

/** خلاصهٔ کارِ اصلاح‌گر روی یک متن. */
data class CorrectionReport(
    val text: String,
    /** جفت‌های (پیش، پس) — برای گزارش اشکال‌یابی. */
    val changes: List<Pair<String, String>>,
    /** توکن‌هایی که ناشناخته بودند ولی نامزدِ یکتایی نداشتند. */
    val unresolved: Int,
) {
    val changeCount: Int get() = changes.size
}

/**
 * ★ اصلاحِ خطاهای کاراکتریِ فارسی با تکیه بر فرهنگِ واژگان — باگ ۴ (اختیاری).
 *
 * ── مسئله ────────────────────────────────────────────────────────────────────
 * Tesseract روی خط فارسی چند خطای *نظام‌مند* دارد که همیشه بین حروفِ هم‌شکل رخ
 * می‌دهد — حرف‌هایی که فقط در تعداد یا جای نقطه/سرکش فرق دارند:
 *   گ↔ک («نگرش»→«نکرش»)، چ↔ج («همچنین»→«همجنین»)،
 *   ژ↔ز («انرژی»→«انرزی»)، ر↔ز («می‌پرسم»→«می‌پزسم»).
 *
 * ── راه‌حل و چرا امن است ─────────────────────────────────────────────────────
 * برای هر توکنِ **ناشناخته**، همهٔ جایگزینی‌های تک‌کاراکتریِ *محدود به جفت‌های
 * هم‌شکل* ساخته می‌شود. اگر **دقیقاً یک** نامزد در فرهنگ باشد، جایگزین می‌شود؛
 * صفر یا بیش از یک نامزد یعنی ابهام و دست نمی‌خورد.
 *
 * سه محافظِ دیگر:
 *  • توکنی که خودش در فرهنگ هست هرگز تغییر نمی‌کند (پس «کرم» به «گرم» نمی‌شود).
 *  • توکن‌های کوتاه‌تر از [MIN_LENGTH] دست نمی‌خورند — بین کلمه‌های دو-سه حرفی
 *    فاصلهٔ ویرایشیِ ۱ آن‌قدر زیاد است که هر تصمیمی قمار می‌شود.
 *  • فقط *جانشینی* انجام می‌شود، نه درج و حذف؛ خطای OCR روی خط فارسی تقریباً
 *    همیشه از این جنس است و درج/حذف ریسکِ به‌مراتب بالاتری دارد.
 *
 * این ماژول هیچ وابستگی‌ای به اندروید و به بقیهٔ خط لوله ندارد و از راه
 * [PersianTextOptions.correctWithLexicon] روشن/خاموش می‌شود.
 */
object ConfusionCorrector {

    /** کوتاه‌تر از این طول، اصلاح نمی‌شود. */
    const val MIN_LENGTH = 3

    /**
     * جفت‌های حروفِ هم‌شکل که OCR بینشان اشتباه می‌کند.
     *
     * چهار جفتِ اولْ همان‌هایی است که در گزارشِ واقعی دیده شد؛ بقیه بر اساس همان
     * منطقِ «تفاوت فقط در نقطه یا سرکش» اضافه شده‌اند.
     */
    private val CONFUSABLE: List<Pair<Char, Char>> = listOf(
        'ک' to 'گ',   // سرکش
        'ج' to 'چ',   // تعداد نقطه
        'ز' to 'ژ',   // تعداد نقطه
        'ر' to 'ز',   // بود/نبودِ نقطه
        'ر' to 'ژ',
        'د' to 'ذ',
        'س' to 'ش',
        'ص' to 'ض',
        'ط' to 'ظ',
        'ع' to 'غ',
        'ب' to 'پ',
        'ب' to 'ت',
        'ب' to 'ن',
        'ب' to 'ی',
        'ت' to 'ث',
        'ت' to 'ن',
        'ن' to 'ی',
        'ف' to 'ق',
        'ح' to 'خ',
        'ح' to 'ج',
        'خ' to 'ج',
    )

    /** نگاشتِ دوطرفهٔ جفت‌ها — یک بار ساخته می‌شود. */
    private val ALTERNATIVES: Map<Char, CharArray> = buildMap<Char, MutableSet<Char>> {
        for ((a, b) in CONFUSABLE) {
            getOrPut(a) { linkedSetOf() } += b
            getOrPut(b) { linkedSetOf() } += a
        }
    }.mapValues { (_, set) -> set.toCharArray() }

    /**
     * اصلاحِ یک متنِ چندخطی.
     *
     * فاصله‌ها، نقطه‌گذاری و شکستِ خط دست‌نخورده می‌مانند؛ فقط خودِ توکن‌ها بررسی
     * می‌شوند تا چیدمانی که رأی‌گیری ساخته به‌هم نریزد.
     */
    fun correct(text: String, lexicon: PersianLexicon): CorrectionReport {
        if (text.isEmpty() || lexicon.size == 0) return CorrectionReport(text, emptyList(), 0)

        val changes = ArrayList<Pair<String, String>>()
        var unresolved = 0
        val out = StringBuilder(text.length)
        val token = StringBuilder()

        fun flush() {
            if (token.isEmpty()) return
            val original = token.toString()
            val fixed = correctToken(original, lexicon)
            when {
                fixed == null -> Unit                      // شناخته‌شده یا خیلی کوتاه
                fixed == original -> unresolved++          // ناشناخته و بدونِ نامزدِ یکتا
                else -> changes += original to fixed
            }
            out.append(fixed ?: original)
            token.setLength(0)
        }

        for (ch in text) {
            if (isWordChar(ch)) token.append(ch) else { flush(); out.append(ch) }
        }
        flush()

        return CorrectionReport(out.toString(), changes, unresolved)
    }

    /**
     * اصلاحِ یک توکن.
     *
     * @return `null` اگر توکن اصلاً نامزدِ بررسی نبود (کوتاه، یا از قبل شناخته‌شده)،
     *   خودِ توکن اگر بررسی شد ولی نامزدِ یکتایی نداشت، یا شکلِ اصلاح‌شده.
     */
    internal fun correctToken(token: String, lexicon: PersianLexicon): String? {
        if (token.length < MIN_LENGTH) return null
        if (!token.any { it in ALTERNATIVES }) return null
        if (lexicon.contains(token)) return null

        var found: String? = null
        val chars = token.toCharArray()
        for (i in chars.indices) {
            val alternatives = ALTERNATIVES[chars[i]] ?: continue
            val original = chars[i]
            for (alternative in alternatives) {
                chars[i] = alternative
                val candidate = String(chars)
                if (lexicon.contains(candidate)) {
                    // نامزدِ دوم یعنی ابهام: با هیچ اطمینانی نمی‌شود یکی را انتخاب کرد.
                    if (found != null && found != candidate) {
                        chars[i] = original
                        return token
                    }
                    found = candidate
                }
            }
            chars[i] = original
        }
        return found ?: token
    }

    /** حروفِ فارسی/عربی و نیم‌فاصله، یعنی چیزهایی که «داخلِ یک کلمه»اند. */
    private fun isWordChar(ch: Char): Boolean {
        if (ch == PersianTextNormalizer.ZWNJ) return true
        val code = ch.code
        return code in 0x0620..0x064A || code in 0x066E..0x06D3
    }
}
