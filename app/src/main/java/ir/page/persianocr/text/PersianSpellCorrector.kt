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

    /** چند بار می‌شود پاره‌های یک شکست را دوباره شکست. */
    private const val MAX_SPLIT_DEPTH = 2

    /** کمینهٔ بازهٔ بسامدِ پاره‌ها تا شکستن بر جانشینی مقدم شود. */
    private const val SPLIT_WINS_BAND = 7

    /** دستِ‌کم یک پارهٔ هر شکست باید این‌قدر پرتکرار باشد (یا حرفِ ربط). */
    private const val COMMON_PART_BAND = 7

    /** بالاتر از این بازه، شکلِ چسبیده خودش واژهٔ رایجی است و نیم‌فاصله نمی‌گیرد. */
    private const val NOMINAL_ZWNJ_MAX_BAND = 6

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

            val staged = ArrayList<Correction>()
            val fixed = repair(original, lexicon, options, staged, depth = 0)
            if (fixed == original) {
                if (!isWord(original, lexicon)) unresolved++
            } else {
                piece.text = fixed
                corrections += staged
            }
        }

        return pieces.joinToString("") { it.text } to unresolved
    }

    /**
     * اصلاحِ یک توکن — **زنجیره‌ای**.
     *
     * ── چرا زنجیره ───────────────────────────────────────────────────────────
     * نسخهٔ اول به هر توکن فقط *یک* اصلاح می‌داد و همان‌جا رها می‌کرد. روی متنِ
     * واقعی این یعنی «میکوید» تا «میگوید» درست می‌شد و نیم‌فاصله‌اش دیگر هرگز
     * گذاشته نمی‌شد. حالا مرحله‌ها پشت‌سر هم اجرا می‌شوند و پاره‌های حاصل از شکستن
     * دوباره از همین مسیر می‌گذرند، پس «جونمیدانید» می‌تواند سه اصلاح بگیرد:
     * ج→چ، سپس درجِ فاصله، سپس نیم‌فاصله روی «میدانید».
     */
    private fun repair(
        token: String,
        lexicon: PersianLexicon,
        options: SpellOptions,
        corrections: MutableList<Correction>,
        depth: Int,
    ): String {
        var current = token

        run {
            // جانشینی روی واژهٔ شناخته‌شده هم صدا زده می‌شود: خودِ
            // [ConfusionCorrector] تصمیم می‌گیرد که آیا آن واژه آن‌قدر کم‌تکرار
            // هست که جای خود را به یک نامزدِ بسیار پرتکرارتر بدهد («یول» → «پول»).
            // شکستن ولی فقط روی توکنِ ناشناخته انجام می‌شود.
            val unknown = !isWord(current, lexicon)
            val substituted = if (options.substitute) substitute(current, lexicon) else null
            val divided = if (options.split && unknown && depth < MAX_SPLIT_DEPTH) {
                split(current, lexicon)
            } else {
                null
            }

            // وقتی هر دو ممکن‌اند، پیش‌فرض *جانشینی* است: خطای حرفی خطای غالبِ این
            // موتور روی خط فارسی است. شکستن فقط وقتی برنده می‌شود که هر دو پاره‌اش
            // واژه‌های بسیار پرتکراری باشند و هدفِ جانشینی کم‌تکرار.
            //
            // آستانهٔ [SPLIT_WINS_BAND] از خودِ داده درآمده: در گزارشِ واقعی هر
            // شکستِ *غلط* کمینهٔ بازهٔ ۵ یا کمتر داشت (میک+وید، آن+رزی، هم+جنین) و
            // تنها شکستِ *درست* کمینهٔ ۷ (یا+ممکن).
            val chosen = when {
                substituted != null && divided != null -> {
                    val splitBand = divided.split(' ').minOf { lexicon.band(it) }
                    val substitutionBand = lexicon.band(substituted)
                    if (splitBand >= SPLIT_WINS_BAND && splitBand > substitutionBand) {
                        divided
                    } else {
                        substituted
                    }
                }

                substituted != null -> substituted
                else -> divided
            }

            if (chosen != null && chosen != current) {
                corrections += Correction(kindOf(current, chosen), current, chosen)
                // پاره‌های یک شکست، هرکدام دوباره از ابتدای همین مسیر می‌گذرند —
                // پاره‌ها واژه‌های تأییدشده‌اند، پس این بازگشت امن است.
                if (chosen.contains(' ')) {
                    return chosen.split(' ')
                        .joinToString(" ") { repair(it, lexicon, options, corrections, depth + 1) }
                }
                // پس از جانشینی فقط نیم‌فاصله می‌آید، نه شکستنِ دوباره.
                current = chosen
            }
        }

        // نیم‌فاصله همیشه آخر می‌آید و روی واژهٔ شناخته‌شده هم اجرا می‌شود.
        if (options.zwnj) {
            insertZwnj(current, lexicon)?.let {
                corrections += Correction(CorrectionKind.ZWNJ, current, it)
                current = it
            }
        }
        return current
    }

    private fun kindOf(before: String, after: String): CorrectionKind = when {
        after.contains(' ') && !before.contains(' ') -> CorrectionKind.SPLIT
        after.contains(ZWNJ) && !before.contains(ZWNJ) -> CorrectionKind.ZWNJ
        else -> CorrectionKind.SUBSTITUTION
    }

    // ─────────────────── ۱) جانشینیِ حروفِ هم‌شکل ───────────────────

    /**
     * واگذارشده به [ConfusionCorrector]، با یک گشایشِ *باریک*: نامزد می‌تواند
     * به‌جای واژه‌بودن، با درجِ نیم‌فاصله معنا پیدا کند — همین «کشادهتر» را به
     * «گشاده‌تر» می‌رساند.
     *
     * ⚠ «قابلِ شکستن‌بودن» عمداً معیارِ پذیرش **نیست**. یک بار امتحان شد و روی متنِ
     * واقعی فاجعه بود: در واژه‌نامه‌ای با ۳۷ هزار واژه تقریباً هر آشغالی به دو واژه
     * می‌شکند، پس «آنرژی» به «آن رزی» و «کهبه‌راحتی» به «که به‌راحتن» تبدیل شد.
     */
    private fun substitute(token: String, lexicon: PersianLexicon): String? {
        val fixed = ConfusionCorrector.correctToken(token, lexicon) { candidate ->
            // فقط مسیرِ *اسمی*: «گشادهتر» با ریشهٔ «گشاده» تأیید می‌شود.
            // مسیرِ فعلی عمداً کنار گذاشته شده — «هر می + فعلِ صرف‌شده» فضای
            // بسیار بزرگی است و «میکوید» را با دو نامزد («میگوید» و «می‌کوبد»)
            // مبهم می‌کرد، پس هیچ اصلاحی انجام نمی‌شد.
            lexicon.containsExact(candidate) || nominalZwnj(candidate, lexicon) != null
        }
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

        val candidates = ArrayList<String>(2)
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

            // ★ کفِ «واقعی‌بودن»: دستِ‌کم یک پاره باید حرفِ ربط یا واژه‌ای پرتکرار
            // باشد. در واژه‌نامه‌ای با ۳۷ هزار واژه تقریباً هر آشغالی به دو واژهٔ
            // کم‌تکرار می‌شکند؛ روی متنِ واقعی هر ۱۶ شکستِ درست از این کف رد شدند
            // و هر دو شکستِ غلط («متر شوم»، «میک وید») زیرش ماندند.
            val common = head in FUNCTION_WORDS || tail in FUNCTION_WORDS ||
                lexicon.band(head) >= COMMON_PART_BAND || lexicon.band(tail) >= COMMON_PART_BAND
            if (!common) continue

            candidates += "$head $tail"
        }

        return when {
            candidates.size == 1 -> candidates.single()
            candidates.isEmpty() -> null
            else -> {
                // ابهام. خطای واقعی تقریباً همیشه فاصله‌ای است که کنارِ یک حرفِ
                // ربط افتاده، پس نامزدی که یک پاره‌اش حرفِ ربط است ترجیح دارد:
                // «شده‌ایمکه» هم «شده‌ای + مکه» می‌دهد و هم «شده‌ایم + که»؛ دومی
                // درست است و تنها چیزی که جدایشان می‌کند همین است.
                val preferred = candidates.filter { candidate ->
                    candidate.split(' ').any { it in FUNCTION_WORDS }
                }
                preferred.singleOrNull()
            }
        }
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
    internal fun insertZwnj(token: String, lexicon: PersianLexicon): String? =
        verbalZwnj(token) ?: nominalZwnj(token, lexicon)

    /** پیشوندِ فعلی: «میدهید» → «می‌دهید». */
    private fun verbalZwnj(token: String): String? {
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

    /** پسوندِ اسمی: «جوابها» → «جواب‌ها»، «مهمترین» → «مهم‌ترین». */
    private fun nominalZwnj(token: String, lexicon: PersianLexicon): String? {
        if (token.contains(ZWNJ)) return null
        //
        // شرطِ بسامد مهم است: واژه‌ای که *چسبیده* هم پرتکرار است، همان‌طور نوشته
        // می‌شود و پسوندش پسوند نیست. «تنها» و «بیشتر» را قیدِ طولِ ریشه می‌گیرد،
        // ولی «کارها» را فقط بسامد.
        if (token in ZWNJ_EXCEPTIONS) return null
        if (lexicon.band(token) > NOMINAL_ZWNJ_MAX_BAND) return null
        for (suffix in NOMINAL_SUFFIXES) {
            if (!token.endsWith(suffix)) continue
            val stem = token.dropLast(suffix.length)
            if (stem.length < MIN_NOMINAL_STEM) continue
            if (!lexicon.containsExact(stem)) continue
            return stem + ZWNJ + suffix
        }
        return null
    }

    /** بلندتر اول، تا «نمی» پیش از «می» تطبیق بخورد. */
    private val PREFIXES = listOf("نمی", "می")

    /** پسوندهایی که در املای معیار با نیم‌فاصله می‌چسبند. بلندتر اول. */
    private val NOMINAL_SUFFIXES = listOf("هایی", "های", "ها", "ترین", "تر")

    /** کمینهٔ طولِ ریشه پیش از پسوندِ اسمی. */
    private const val MIN_NOMINAL_STEM = 3

    /**
     * واژه‌هایی که *پایانه‌شان* شبیه پسوند است ولی پسوند نیست.
     *
     * «تنها» به «تن‌ها» و «بیشتر» به «بیش‌تر» تبدیل نمی‌شود. قیدِ «ریشه دستِ‌کم
     * سه حرف» بیشترِ دام‌ها را خودش می‌گیرد («بهتر»، «کمتر»، «دفتر»، «دختر» ریشهٔ
     * دوحرفی دارند)؛ این فهرست برای بقیه است.
     */
    private val ZWNJ_EXCEPTIONS: Set<String> = setOf(
        "بیشتر", "بیشترین", "کمترین", "برترین", "تنها", "تنهایی", "رهایی",
        "اشتها", "انتها", "انتهای", "ابتدا", "بهایی", "دنیای", "دنیا",
        // قیدهایی که در عمل همیشه چسبیده نوشته می‌شوند.
        "بارها", "کارها", "چیزها", "حرفها", "بعدها", "گاهها",
    )

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
