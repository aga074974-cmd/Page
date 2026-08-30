package ir.page.persianocr.text

/** چه چیزی روی یک توکن انجام شد — برای گزارش اشکال‌یابی. */
enum class CorrectionKind(val label: String) {
    SUBSTITUTION("جانشینی حرف"),
    SPLIT("درج فاصله"),
    MERGE("ادغام دو توکن"),
    ZWNJ("درج نیم‌فاصله"),
}

/** یک تغییر. */
data class Correction(val kind: CorrectionKind, val before: String, val after: String)

/** خلاصهٔ کارِ اصلاح‌گر روی یک متن. */
data class SpellReport(
    val text: String,
    val corrections: List<Correction>,
    /** توکن‌هایی که ناشناخته بودند ولی هیچ اصلاحِ مطمئنی نداشتند. */
    val unresolved: Int,
) {
    val count: Int get() = corrections.size
    fun countOf(kind: CorrectionKind): Int = corrections.count { it.kind == kind }
}

/** کدام مرحله‌های اصلاح اجرا شوند. همه پیش‌فرض روشن‌اند. */
data class SpellOptions(
    /** جانشینیِ حروفِ هم‌شکل: «نکرش»→«نگرش». */
    val substitute: Boolean = true,
    /** درجِ فاصلهٔ جاافتاده: «ارزانهستند»→«ارزان هستند». */
    val split: Boolean = true,
    /** ادغامِ فاصلهٔ اضافی: «ا ین»→«این». */
    val merge: Boolean = true,
    /** درجِ نیم‌فاصله: «میدهید»→«می‌دهید». */
    val zwnj: Boolean = true,
)

/**
 * ★ اصلاح‌گرِ املاییِ فارسی — ماژولِ مستقلِ باگ ۳.
 *
 * پس از رأی‌گیری و پیش از خروجیِ نهایی اجرا می‌شود و چهار خطای *نظام‌مندِ* OCR فارسی
 * را می‌گیرد؛ خطاهایی که رأی‌گیری حلشان نمی‌کند چون در همهٔ حالت‌ها یکسان‌اند:
 *
 *  ۱. **جانشینیِ حروفِ هم‌شکل** — «نکرش»→«نگرش»، «جون»→«چون»، «دهنی»→«ذهنی».
 *  ۲. **فاصلهٔ جاافتاده** — «ارزانهستند»→«ارزان هستند»، «خدماترا»→«خدمات را».
 *  ۳. **فاصلهٔ اضافیِ درونِ کلمه** — «ا ین»→«این»، «خریدا ری»→«خریداری».
 *  ۴. **نیم‌فاصلهٔ جاافتاده** — «میدهید»→«می‌دهید».
 *
 * ── اصلِ حاکم بر همهٔ مرحله‌ها ────────────────────────────────────────────────
 * هیچ تغییری بدون شاهد انجام نمی‌شود، و **ابهام یعنی دست‌نزدن**. توکنی که خودش در
 * واژه‌نامه هست هرگز دست نمی‌خورد، و هر مرحله فقط وقتی عمل می‌کند که *یک* نتیجهٔ
 * معتبر داشته باشد. خطا عمداً به‌سمتِ «کاری نکن» می‌افتد، نه «حدس بزن».
 *
 * ── اعداد ────────────────────────────────────────────────────────────────────
 * ارقام و هر توکنی که رقم دارد کاملاً دست‌نخورده می‌مانند. خطاهای عددیِ OCR
 * («۸۰»→«*۸») با واژه‌نامه قابلِ تشخیص نیستند و حدس‌زدنشان ریسکِ خرابی دارد.
 *
 * ماژول به اندروید وابسته نیست؛ واژه‌نامه را فراخوان می‌دهد
 * ([AssetLexicon.load]) و با [PersianTextOptions.correctWithLexicon] روشن می‌شود.
 */
object PersianSpellCorrector {

    /** کوتاه‌تر از این طول، برای شکستن بررسی نمی‌شود. */
    private const val MIN_SPLIT_LENGTH = 5

    /** کمینهٔ طولِ پاره‌ای که واژهٔ عادی (نه حرفِ ربط) است. */
    private const val MIN_SPLIT_PART = 3

    /** برای ادغام، دستِ‌کم یکی از دو توکن باید از این کوتاه‌تر یا برابر باشد. */
    private const val MAX_MERGE_PART = 4

    /** کمینهٔ طولِ ریشه پس از «می»/«نمی» برای درجِ نیم‌فاصله. */
    private const val MIN_ZWNJ_STEM = 3

    /**
     * واژه‌های کوتاهی که مجازند یک پارهٔ شکست باشند.
     *
     * فاصلهٔ ته‌خط تقریباً همیشه پیش یا پسِ همین‌ها می‌افتد («فروش و»، «خدمات را»،
     * «یک خریدار»). بدونِ این فهرست باید پاره‌های یک‌ودوحرفی را رد می‌کردیم و
     * نیمی از موارد از دست می‌رفت؛ با آن، شکستنِ دلبخواهی هم ممکن نمی‌شود.
     */
    private val FUNCTION_WORDS: Set<String> = setOf(
        "و", "را", "که", "در", "به", "از", "با", "تا", "هم", "یا", "بر", "نه",
        "این", "آن", "است", "یک", "ما", "او", "شما", "من", "هر", "چه", "اگر",
    )

    /**
     * پایانه‌هایی که در فارسی *کلمهٔ ترکیبی* می‌سازند، نه واژهٔ مستقل.
     *
     * بدونِ این فهرست، «متقاعدسازی» به «متقاعد سازی» و «خریدار» به «خری دار»
     * شکسته می‌شد — چون هر دو پاره در واژه‌نامه هستند.
     */
    private val COMPOUND_TAILS: Set<String> = setOf(
        "سازی", "ساز", "گری", "گر", "بندی", "بند", "گیری", "گیر", "کاری", "کار",
        "دهی", "ده", "پذیری", "پذیر", "مندی", "مند", "خانه", "نامه", "شناسی",
        "شناس", "آمیز", "انگیز", "بخش", "وار", "گونه", "دار", "بان", "ور", "ناک",
        "شده", "شدن", "کننده", "کنند", "دهنده", "زدایی", "پردازی", "نگاری",
    )

    private const val ZWNJ = PersianTextNormalizer.ZWNJ

    /**
     * اجرای همهٔ مرحله‌ها روی یک متنِ چندخطی.
     *
     * شکستِ خط، نقطه‌گذاری و فاصله‌های عمدی (مثل ستون‌بندیِ عنوان) دست‌نخورده
     * می‌مانند؛ فقط خودِ توکن‌ها بررسی می‌شوند.
     */
    fun correct(
        text: String,
        lexicon: PersianLexicon,
        options: SpellOptions = SpellOptions(),
    ): SpellReport {
        if (text.isBlank() || lexicon.size == 0) return SpellReport(text, emptyList(), 0)

        val corrections = ArrayList<Correction>()
        var unresolved = 0

        val rebuilt = text.split('\n').joinToString("\n") { line ->
            val (fixed, lineUnresolved) = correctLine(line, lexicon, options, corrections)
            unresolved += lineUnresolved
            fixed
        }
        return SpellReport(rebuilt, corrections, unresolved)
    }

    // ─────────────────────────── یک خط ───────────────────────────

    private fun correctLine(
        line: String,
        lexicon: PersianLexicon,
        options: SpellOptions,
        corrections: MutableList<Correction>,
    ): Pair<String, Int> {
        // خط به قطعه‌های «کلمه» و «غیرکلمه» شکسته می‌شود تا فاصله‌ها و نقطه‌گذاری
        // عیناً سرِ جایشان برگردند.
        val pieces = tokenise(line)
        var unresolved = 0

        // ── مرحلهٔ ادغام: پیش از بقیه، چون ورودیِ آن‌ها را درست می‌کند ──────────
        if (options.merge) mergeAdjacent(pieces, lexicon, corrections)

        for (piece in pieces) {
            if (!piece.isWord || piece.text.isEmpty()) continue
            val original = piece.text

            // اعداد و هر توکنِ آمیخته با رقم، دست‌نخورده.
            if (original.any(Char::isDigit)) continue

            if (isWord(original, lexicon)) {
                // حتی واژهٔ شناخته‌شده ممکن است نیم‌فاصله کم داشته باشد.
                if (options.zwnj) {
                    val withZwnj = insertZwnj(original)
                    if (withZwnj != null) {
                        piece.text = withZwnj
                        corrections += Correction(CorrectionKind.ZWNJ, original, withZwnj)
                    }
                }
                continue
            }

            // ترتیب عمدی، و روی متنِ واقعی سنجیده شده: جانشینی پیش از شکستن.
            // وقتی هر دو ممکن‌اند، خطای حرفی محتمل‌تر است — «همجنین» با جانشینی
            // «همچنین» می‌شود ولی با شکستن «هم جنین»، که بی‌معناست. بهایش این است
            // که «یاممکن» به‌جای «یا ممکن» می‌شود «ناممکن»؛ معاملهٔ به‌صرفه‌ای است،
            // چون جفت‌های حرفیْ خطای غالبِ این موتور روی خط فارسی‌اند.
            val fixed = (if (options.zwnj) insertZwnj(original) else null)
                ?: (if (options.substitute) substitute(original, lexicon) else null)
                ?: (if (options.split) split(original, lexicon) else null)

            when {
                fixed == null -> unresolved++
                else -> {
                    piece.text = fixed
                    corrections += Correction(kindOf(original, fixed), original, fixed)
                }
            }
        }

        return pieces.joinToString("") { it.text } to unresolved
    }

    private fun kindOf(before: String, after: String): CorrectionKind = when {
        after.contains(' ') && !before.contains(' ') -> CorrectionKind.SPLIT
        after.contains(ZWNJ) && !before.contains(ZWNJ) -> CorrectionKind.ZWNJ
        else -> CorrectionKind.SUBSTITUTION
    }

    // ─────────────────── ۱) جانشینیِ حروفِ هم‌شکل ───────────────────

    /** واگذارشده به [ConfusionCorrector] که همین کار را جداگانه و تست‌شده می‌کند. */
    private fun substitute(token: String, lexicon: PersianLexicon): String? {
        val fixed = ConfusionCorrector.correctToken(token, lexicon)
        return if (fixed != null && fixed != token) fixed else null
    }

    // ─────────────────── ۲) درجِ فاصلهٔ جاافتاده ───────────────────

    /**
     * شکستنِ یک توکنِ ناشناخته به دو واژهٔ معتبر.
     *
     * فقط وقتی عمل می‌کند که **دقیقاً یک** نقطهٔ شکست معتبر باشد. دو محافظ:
     *  • پارهٔ کوتاه‌تر از [MIN_SPLIT_PART] باید حرفِ ربط باشد ([FUNCTION_WORDS])؛
     *  • پارهٔ دوم نباید پایانهٔ ترکیب‌ساز باشد ([COMPOUND_TAILS])، وگرنه واژه‌های
     *    ترکیبیِ سالم مثل «متقاعدسازی» تکه‌تکه می‌شدند.
     */
    private fun split(token: String, lexicon: PersianLexicon): String? {
        val bare = token
        if (bare.length < MIN_SPLIT_LENGTH) return null

        var found: String? = null
        for (cut in 1 until bare.length) {
            // نیم‌فاصله یعنی «این دو تکه *یک* کلمه‌اند»؛ درست همان‌جا نباید برید.
            // بدونِ این شرط «به‌شدت» و «به‌عنوان» دو نیم می‌شدند، چون هر دو پاره‌شان
            // واژهٔ معتبری است. ولی بریدنِ *جای دیگرِ* همان توکن مانعی ندارد، و
            // همین است که «کهبه‌راحتی» را به «که به‌راحتی» تبدیل می‌کند.
            if (bare[cut - 1] == ZWNJ || bare[cut] == ZWNJ) continue

            val head = bare.substring(0, cut)
            val tail = bare.substring(cut)

            // طول بدونِ نیم‌فاصله سنجیده می‌شود؛ نیم‌فاصله حرف نیست.
            val headLength = head.count { it != ZWNJ }
            val tailLength = tail.count { it != ZWNJ }

            val headOk = if (headLength < MIN_SPLIT_PART) head in FUNCTION_WORDS else lexicon.contains(head)
            if (!headOk) continue
            val tailOk = if (tailLength < MIN_SPLIT_PART) {
                tail in FUNCTION_WORDS
            } else {
                tail !in COMPOUND_TAILS && lexicon.contains(tail)
            }
            if (!tailOk) continue

            val candidate = "$head $tail"
            if (found != null && found != candidate) return null // ابهام
            found = candidate
        }
        return found
    }

    // ─────────────────── ۳) ادغامِ فاصلهٔ اضافی ───────────────────

    /**
     * چسباندنِ دو توکنِ مجاور که با هم یک واژهٔ معتبر می‌سازند.
     *
     * شرطِ کلیدی: دستِ‌کم یکی از دو توکن باید *خودش واژه نباشد*. بدونِ آن،
     * «به راحتی» به «بهراحتی» و «مهارت های» به «مهارتهای» چسبانده می‌شد.
     * علاوه بر آن، یکی از دو توکن باید کوتاه باشد ([MAX_MERGE_PART]) — ادغامِ دو
     * واژهٔ بلند تقریباً همیشه اشتباه است.
     */
    private fun mergeAdjacent(
        pieces: MutableList<Piece>,
        lexicon: PersianLexicon,
        corrections: MutableList<Correction>,
    ) {
        var index = 0
        while (index + 2 < pieces.size) {
            val left = pieces[index]
            val gap = pieces[index + 1]
            val right = pieces[index + 2]

            // فقط وقتی که دقیقاً یک فاصلهٔ ساده بینشان است.
            if (!left.isWord || !right.isWord || gap.isWord || gap.text != " ") {
                index++
                continue
            }
            if (left.text.any(Char::isDigit) || right.text.any(Char::isDigit)) {
                index++
                continue
            }

            // «و» و «را» واژه‌اند، هرچند کوتاه‌تر از آن‌اند که در واژه‌نامه بیایند.
            // بدونِ این، «می‌کند و» به «می‌کندو» چسبانده می‌شد (چون «کندو» واژه است).
            val leftKnown = isWord(left.text, lexicon)
            val rightKnown = isWord(right.text, lexicon)
            val shortEnough = left.text.length <= MAX_MERGE_PART || right.text.length <= MAX_MERGE_PART

            if ((leftKnown && rightKnown) || !shortEnough) {
                index++
                continue
            }

            val joined = left.text + right.text
            if (!lexicon.contains(joined)) {
                index++
                continue
            }

            corrections += Correction(CorrectionKind.MERGE, "${left.text} ${right.text}", joined)
            left.text = joined
            pieces.removeAt(index + 2)
            pieces.removeAt(index + 1)
            // اندیس جلو نمی‌رود: ممکن است همین واژهٔ تازه با بعدی هم ادغام شود.
        }
    }

    /** واژه‌بودن، با احتسابِ حرف‌های ربطِ کوتاهی که در واژه‌نامه نمی‌گنجند. */
    private fun isWord(token: String, lexicon: PersianLexicon): Boolean =
        token in FUNCTION_WORDS || lexicon.contains(token)

    // ─────────────────── ۴) درجِ نیم‌فاصله ───────────────────

    /**
     * «میدهید» → «می‌دهید».
     *
     * نیم‌فاصله فقط وقتی درج می‌شود که آنچه پس از «می»/«نمی» می‌آید *صرفِ یک فعل*
     * باشد ([PersianVerbStems]). قاعدهٔ ساده‌تر («اگر بقیه واژه بود») «میدان» را
     * به «می‌دان» و «میلاد» را به «می‌لاد» تبدیل می‌کرد.
     *
     * @return شکلِ اصلاح‌شده، یا `null` اگر چیزی برای درج نبود.
     */
    internal fun insertZwnj(token: String): String? {
        if (token.contains(ZWNJ)) return null
        for (prefix in PREFIXES) {
            if (!token.startsWith(prefix)) continue
            val stem = token.substring(prefix.length)
            if (stem.length < MIN_ZWNJ_STEM) continue
            if (!PersianVerbStems.isConjugated(stem)) continue
            return prefix + ZWNJ + stem
        }
        return null
    }

    /** بلندتر اول، تا «نمی» پیش از «می» تطبیق بخورد. */
    private val PREFIXES = listOf("نمی", "می")

    // ─────────────────── تکه‌کردنِ خط ───────────────────

    /** یک قطعه از خط: یا کلمه است یا هرچیز دیگر (فاصله، نقطه‌گذاری، رقم). */
    private class Piece(var text: String, val isWord: Boolean)

    private fun tokenise(line: String): MutableList<Piece> {
        val pieces = ArrayList<Piece>()
        val buffer = StringBuilder()
        var inWord = false

        for (ch in line) {
            val wordChar = isWordChar(ch)
            if (buffer.isNotEmpty() && wordChar != inWord) {
                pieces += Piece(buffer.toString(), inWord)
                buffer.setLength(0)
            }
            inWord = wordChar
            buffer.append(ch)
        }
        if (buffer.isNotEmpty()) pieces += Piece(buffer.toString(), inWord)
        return pieces
    }

    /** حروفِ فارسی/عربی و نیم‌فاصله — یعنی چیزهایی که «داخلِ یک کلمه»اند. */
    private fun isWordChar(ch: Char): Boolean {
        if (ch == ZWNJ) return true
        val code = ch.code
        return code in 0x0620..0x064A || code in 0x066E..0x06D3
    }
}
