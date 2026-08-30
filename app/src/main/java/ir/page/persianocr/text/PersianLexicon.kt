package ir.page.persianocr.text

/**
 * فرهنگِ واژگانِ فارسی برای بررسیِ «آیا این توکن اصلاً کلمه است؟».
 *
 * ── چرا یک واسط و نه مستقیم یک `Set` ─────────────────────────────────────────
 * تا این ماژول به اندروید وابسته نشود و بشود در تستِ واحد یک فرهنگِ کوچکِ ساختگی
 * جایش گذاشت. بارگذاری از assets در [ir.page.persianocr.text.AssetLexicon] است.
 */
interface PersianLexicon {
    /** آیا این واژه (یا شکلِ صرف‌شده‌اش) در فرهنگ هست؟ */
    fun contains(word: String): Boolean

    /** تعداد واژه‌های پایه — فقط برای گزارش. */
    val size: Int

    companion object {
        /** فرهنگِ خالی: همه‌چیز ناشناخته است، پس هیچ اصلاحی انجام نمی‌شود. */
        val EMPTY: PersianLexicon = object : PersianLexicon {
            override fun contains(word: String) = false
            override val size = 0
        }
    }
}

/**
 * پیاده‌سازیِ درون‌حافظه‌ای روی یک مجموعهٔ واژه، با کمی تحلیلِ صرفی.
 *
 * ── چرا تحلیلِ صرفی ──────────────────────────────────────────────────────────
 * فهرستِ واژه هر شکلِ صرف‌شده را ندارد. اگر «می‌گوید» را نشناسیم ولی «گوید» را
 * بشناسیم، بدونِ این تحلیل «میکوید» را هم نمی‌توانستیم به «میگوید» اصلاح کنیم.
 * پیشوند/پسوندها فقط وقتی برداشته می‌شوند که *باقی‌مانده* خودش واژهٔ کاملی باشد،
 * پس این کار فرهنگ را «شل» نمی‌کند، فقط پوشش می‌دهد.
 *
 * اثرِ جانبیِ مطلوب: هرچه `contains` سخاوتمندتر باشد، اصلاح‌گر *کمتر* دست به
 * تغییر می‌زند (واژهٔ اصلی شناخته می‌شود، یا نامزدها بیش از یکی می‌شوند و
 * ابهام باعث انصراف می‌شود). یعنی خطا به‌سمتِ «دست‌نزدن» می‌افتد، نه «خراب‌کردن».
 */
class SetLexicon(words: Set<String>) : PersianLexicon {

    private val words: Set<String> = words

    override val size: Int get() = words.size

    override fun contains(word: String): Boolean {
        if (baseContains(word)) return true

        // واژهٔ ترکیبیِ نیم‌فاصله‌دار. فهرستِ واژگان از پیکره‌ای آمده که نیم‌فاصله
        // ندارد، پس «به‌راحتی» و «نگرش‌های» در آن نیستند در حالی که واژه‌های کاملاً
        // معتبری‌اند.
        //
        // ⚠ شرطِ «دستِ‌کم یک پاره وندِ شناخته‌شده باشد» حیاتی است. بدونِ آن، *هر* دو
        // واژه‌ای که با نیم‌فاصله کنار هم بنشینند «واژه» شمرده می‌شدند و اصلاح‌گر
        // «کهبه‌راحتی» را با اطمینان به «کهنه‌راحتی» تبدیل می‌کرد.
        if (word.indexOf(PersianTextNormalizer.ZWNJ) < 0) return false
        val parts = word.split(PersianTextNormalizer.ZWNJ).filter { it.isNotBlank() }
        if (parts.size < 2 || parts.size > MAX_ZWNJ_PARTS) return false
        if (parts.none { it in AFFIX_PARTS }) return false
        return parts.all { part -> part in AFFIX_PARTS || baseContains(part) }
    }

    /** بررسیِ پایه: خودِ واژه، یا واژه پس از برداشتنِ یک پیشوند/پسوندِ رایج. */
    private fun baseContains(word: String): Boolean {
        val key = PersianTextNormalizer.lexiconKey(word)
        if (key.length < 2) return false
        if (key in words) return true

        for (prefix in PREFIXES) {
            if (key.length > prefix.length + 1 && key.startsWith(prefix)) {
                if (key.removePrefix(prefix) in words) return true
            }
        }
        for (suffix in SUFFIXES) {
            if (key.length - suffix.length >= MIN_STEM && key.endsWith(suffix)) {
                if (key.dropLast(suffix.length) in words) return true
            }
        }
        return false
    }

    companion object {
        /** کمینهٔ طولِ ریشه پس از برداشتنِ پسوند. */
        private const val MIN_STEM = 3

        /** بیشترین پارهٔ نیم‌فاصله‌ای که یک واژهٔ ترکیبی می‌تواند داشته باشد. */
        private const val MAX_ZWNJ_PARTS = 3

        /**
         * وندها و حرف‌های ربطی که یک ترکیبِ نیم‌فاصله‌ای را «واژه» می‌کنند.
         * دستِ‌کم یکی از پاره‌ها باید از این فهرست باشد.
         */
        private val AFFIX_PARTS = setOf(
            "می", "نمی", "بی", "ها", "های", "هایی", "تر", "ترین", "ای", "به", "که",
        )

        /** پیشوندهای پرکاربردِ فعلی/نفی. ترتیب مهم است: بلندتر اول. */
        private val PREFIXES = listOf("نمی", "می", "بی")

        /** پسوندهای پرکاربرد. ترتیب مهم است: بلندتر اول. */
        private val SUFFIXES = listOf(
            "هایی", "های", "ها",
            "ترین", "تر",
            "مان", "تان", "شان",
            "ام", "ات", "اش",
            "ی",
        )
    }
}
