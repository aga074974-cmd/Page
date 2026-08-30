package ir.page.persianocr.text

import java.text.Normalizer

/** تنظیمات پس‌پردازش متن فارسی. */
data class PersianTextOptions(
    /** ي→ی ، ك→ک و مانند آن. */
    val normaliseArabicLetters: Boolean = true,
    /** حذف اعراب (فتحه/کسره/…)، تطویل و کاراکترهای کنترلی نامرئی. */
    val stripDiacritics: Boolean = true,
    /** ارقام عربیِ ٠١٢… را به فارسیِ ۰۱۲… تبدیل کن. */
    val convertArabicDigits: Boolean = true,
    /** ارقام لاتینِ 012… را هم به فارسی تبدیل کن (برای یکدست‌شدن کامل). */
    val convertLatinDigits: Boolean = true,
    /** اصلاح نیم‌فاصله برای «می/نمی» و «ها/تر/ترین». */
    val applyZwnj: Boolean = true,
    /** حذف فاصله‌های اضافی و خطوط خالی زائد. */
    val tidyWhitespace: Boolean = true,
)

/**
 * پس‌پردازش خروجی خام Tesseract برای متن فارسی.
 *
 * این کلاس هیچ وابستگی‌ای به اندروید ندارد تا با تستِ واحدِ JVM قابل آزمایش باشد.
 * Pure JVM logic so it is unit-testable without an emulator.
 */
object PersianTextNormalizer {

    /** نیم‌فاصله / Zero Width Non-Joiner */
    const val ZWNJ = '‌'

    private const val ARABIC_INDIC_ZERO = '٠'   // ٠
    private const val EXTENDED_ARABIC_ZERO = '۰' // ۰

    /**
     * جایگزینی تک‌کاراکتری حروف عربی با معادل فارسی.
     * توجه: «آ» و «ؤ» و «ئ» عمداً دست‌نخورده می‌مانند چون در املای فارسی معتبرند.
     */
    private val LETTER_MAP: Map<Char, Char> = mapOf(
        'ي' to 'ی', // ي  Arabic Yeh        → ی
        'ى' to 'ی', // ى  Alef Maksura      → ی
        'ې' to 'ی', // ې  Pashto Yeh        → ی
        'ۍ' to 'ی', // ۍ                    → ی
        'ك' to 'ک', // ك  Arabic Kaf        → ک
        'ڪ' to 'ک', // ڪ  Swash Kaf         → ک
        'ګ' to 'ک', // ګ                    → ک
        'ة' to 'ه', // ة  Teh Marbuta       → ه
        'ۀ' to 'ه', // ۀ  (heh + hamza)     → ه
        'ہ' to 'ه', // ہ  Heh Goal          → ه
        'ە' to 'ه', // ە                    → ه
        'أ' to 'ا', // أ                    → ا
        'إ' to 'ا', // إ                    → ا
        'ٱ' to 'ا', // ٱ  Alef Wasla        → ا
        'ٲ' to 'ا',
        'ٳ' to 'ا',
        'ٵ' to 'ا',
        'ۋ' to 'و', // ۋ                    → و
    )

    /** اعراب، تطویل و کاراکترهای کنترلیِ نامرئی که باید حذف شوند. */
    private val STRIPPABLE: Set<Char> = buildSet {
        // اعراب عربی: فتحتان (064B) تا حرکات کوچک (065F)
        for (code in 0x064B..0x065F) add(code.toChar())
        add('\u0670') // الف خنجری / superscript alef
        add('\u0640') // ـ تطویل (kashida)
        add('\u200B') // ZWSP
        add('\u200D') // ZWJ  — توجه: ZWNJ (200C) عمداً حفظ می‌شود
        add('\u200E') // LRM
        add('\u200F') // RLM
        add('\u061C') // ALM
        add('\uFEFF') // BOM
        add('\u00AD') // soft hyphen
    }

    /** فاصله‌های غیرمعمول که به فاصلهٔ ساده تبدیل می‌شوند. */
    private val SPACE_LIKE: Set<Char> = buildSet {
        add('\u00A0') // NBSP
        add('\u202F') // narrow NBSP
        add('\u205F') // medium mathematical space
        add('\u3000') // ideographic space
        for (code in 0x2000..0x200A) add(code.toChar()) // en/em/thin spaces
    }

    /** بازهٔ حروف فارسی/عربی که برای الگوهای نیم‌فاصله استفاده می‌شود. */
    // 0621–063A: ء…غ | 0641–064A: ف…ي | 066E–06D3: حروف فارسی (پ چ ژ ک گ ی …) | 06FA–06FF
    private const val LETTER = "\u0621-\u063A\u0641-\u064A\u066E-\u06D3\u06FA-\u06FF"

    /**
     * پیشوندهای فعلی: «می» و «نمی» باید با نیم‌فاصله به فعل بچسبند.
     * گروه ۱ = مرزِ پیش از پیشوند، گروه ۲ = خودِ پیشوند.
     * «می کند» → «می‌کند» ، «نمی شود» → «نمی‌شود»
     */
    private val VERB_PREFIX = Regex("(^|[\\s$ZWNJ(\\[«\"'،؛:.!؟])(ن?می)[ \\t]+(?=[$LETTER])")

    /**
     * پسوندهای جمع/تفضیلی: «ها»، «های»، «هایی»، «تر»، «تری»، «ترین».
     * واژهٔ پیشین باید دست‌کم دو حرف باشد تا «و تر» یا «ا ها» اشتباهاً نچسبد.
     * «کتاب ها» → «کتاب‌ها» ، «بزرگ ترین» → «بزرگ‌ترین»
     */
    private val NOUN_SUFFIX = Regex(
        "([$LETTER]{2,})[ \\t]+(ها|های|هایی|هایم|هایت|هایش|هایمان|هایتان|هایشان|تر|تری|ترین)" +
            "(?=[\\s$ZWNJ.,،؛:!؟)\\]»\"']|\$)",
    )

    /** فاصلهٔ اضافی قبل از نشانه‌های پایانی. */
    private val SPACE_BEFORE_PUNCT = Regex("[ \\t]+([.,،؛:!؟?)\\]»}])")

    /** نبودِ فاصله بعد از نشانه‌های پایانی (وقتی حرف بعدی فاصله نیست). */
    private val PUNCT_NEEDS_SPACE = Regex("([،؛:!؟?])(?=[^\\s\\d])")

    /** فاصلهٔ اضافی بعد از نشانه‌های آغازین. */
    private val SPACE_AFTER_OPEN = Regex("([(\\[«{])[ \\t]+")

    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val MULTI_BLANK_LINE = Regex("\n{3,}")
    private val SPACED_ZWNJ = Regex("[ \\t]*$ZWNJ[ \\t]*")
    private val EDGE_ZWNJ = Regex("(?m)(^$ZWNJ+|$ZWNJ+\$)")

    /**
     * اجرای کامل پس‌پردازش روی خروجی خام Tesseract.
     *
     * ترتیب مراحل مهم است: اول یکسان‌سازی کاراکترها، بعد نیم‌فاصله (که به شکل
     * حروف وابسته است) و در آخر تمیزکاری فاصله‌ها.
     */
    fun normalise(raw: String, options: PersianTextOptions = PersianTextOptions()): String {
        if (raw.isBlank()) return ""

        // NFKC شکل‌های نمایشیِ عربی (مثل ligature «ﻻ») را به حروف پایه برمی‌گرداند.
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC)

        text = text.replace("\r\n", "\n").replace('\r', '\n')
        text = mapCharacters(text, options)

        if (options.applyZwnj) text = applyZwnj(text)
        if (options.tidyWhitespace) text = tidyWhitespace(text)

        return text
    }

    // ────────────────────────── نگاشت کاراکترها ──────────────────────────

    private fun mapCharacters(text: String, options: PersianTextOptions): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when {
                options.stripDiacritics && ch in STRIPPABLE -> Unit // حذف

                ch in SPACE_LIKE -> builder.append(' ')

                options.convertArabicDigits && ch in ARABIC_INDIC_ZERO..'٩' ->
                    builder.append(EXTENDED_ARABIC_ZERO + (ch - ARABIC_INDIC_ZERO))

                options.convertLatinDigits && ch in '0'..'9' ->
                    builder.append(EXTENDED_ARABIC_ZERO + (ch - '0'))

                options.normaliseArabicLetters && LETTER_MAP.containsKey(ch) ->
                    builder.append(LETTER_MAP.getValue(ch))

                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    // ─────────────────────────── نیم‌فاصله ───────────────────────────

    private fun applyZwnj(text: String): String {
        var result = VERB_PREFIX.replace(text) { m ->
            m.groupValues[1] + m.groupValues[2] + ZWNJ
        }
        result = NOUN_SUFFIX.replace(result) { m ->
            m.groupValues[1] + ZWNJ + m.groupValues[2]
        }
        return result
    }

    // ────────────────────────── تمیزکاری فاصله ──────────────────────────

    private fun tidyWhitespace(text: String): String {
        var result = text

        // نیم‌فاصله هرگز نباید کنارش فاصله داشته باشد.
        result = SPACED_ZWNJ.replace(result, ZWNJ.toString())
        result = EDGE_ZWNJ.replace(result, "")

        result = SPACE_BEFORE_PUNCT.replace(result) { it.groupValues[1] }
        result = SPACE_AFTER_OPEN.replace(result) { it.groupValues[1] }
        result = PUNCT_NEEDS_SPACE.replace(result) { it.groupValues[1] + " " }
        result = MULTI_SPACE.replace(result, " ")

        // هر خط را جداگانه trim می‌کنیم تا تورفتگی‌های تصادفیِ OCR حذف شود.
        result = result.lineSequence().joinToString("\n") { it.trim() }

        // بیش از یک خط خالی پشت سر هم بی‌معناست.
        result = MULTI_BLANK_LINE.replace(result, "\n\n")

        return result.trim()
    }
}
