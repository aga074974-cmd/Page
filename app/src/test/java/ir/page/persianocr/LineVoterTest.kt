package ir.page.persianocr

import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.ocr.LineVoter
import ir.page.persianocr.ocr.OcrLine
import ir.page.persianocr.ocr.VariantLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * رأی‌گیری خط‌به‌خط — تستِ اصلیِ باگی که در گزارش دیده شد.
 */
class LineVoterTest {

    private fun lines(vararg pairs: Pair<String, Int>): List<OcrLine> =
        pairs.map { (text, confidence) ->
            val words = text.split(" ").count(String::isNotBlank)
            OcrLine(text, confidence, wordCount = words, strongWordCount = words)
        }

    /** سه خطِ مشترک که در همهٔ حالت‌ها هست و کار تراز را لنگر می‌اندازد. */
    private val opening = "نگرش و باور /۱۷"
    private val second = "درفروش ۸۰ درصد به عوامل ذهنی و تنها ۲۰ درصد به مهارت‌های فروش"
    private val dropped = "متقاعدسازی بستگی دارد. فرض کنید شما بهترین محصولات یا خدمات را"
    private val fourth = "ارائه می‌دهید؛ و همچنین مهارت بسیار بالایی در ایجاد اعتماد دارید."

    @Test
    fun `a line dropped by the most confident mode is recovered by majority vote`() {
        // بازسازی دقیقِ سناریوی گزارش: SAUVOLA خطِ سوم را ندیده ولی مطمئن‌ترین است.
        val result = LineVoter.combine(
            listOf(
                VariantLines(
                    BinarizationMethod.SAUVOLA,
                    lines(opening to 92, second to 90, fourth to 88),
                ),
                VariantLines(
                    BinarizationMethod.OTSU,
                    lines(opening to 84, second to 80, dropped to 79, fourth to 82),
                ),
                VariantLines(
                    BinarizationMethod.ADAPTIVE_MEAN,
                    lines(opening to 80, second to 78, dropped to 77, fourth to 79),
                ),
                VariantLines(
                    BinarizationMethod.CLAHE_OTSU,
                    lines(opening to 85, second to 83, dropped to 81, fourth to 84),
                ),
            ),
        )

        assertTrue(
            "خط حذف‌شده باید برگردد:\n${result.text}",
            result.text.contains("متقاعدسازی بستگی دارد"),
        )
        // و باید سرِ جای خودش باشد، نه چسبیده به انتها.
        assertEquals(
            listOf(opening, second, dropped, fourth),
            result.acceptedLines.map { it.text },
        )
    }

    @Test
    fun `a low confidence line seen by only one mode is dropped as noise`() {
        // «اب 0 روشو» — زبالهٔ واقعیِ ADAPTIVE_GAUSSIAN در همان گزارش.
        val result = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.SAUVOLA, lines(opening to 92, second to 90)),
                VariantLines(BinarizationMethod.OTSU, lines(opening to 84, second to 80)),
                VariantLines(
                    BinarizationMethod.ADAPTIVE_GAUSSIAN,
                    lines(opening to 70, "اب 0 روشو" to 31, second to 68),
                ),
            ),
        )

        assertFalse("زباله نباید در متن نهایی بیاید:\n${result.text}", result.text.contains("روشو"))
        assertTrue(result.rejectedLines.any { it.text.contains("اب 0") })
    }

    @Test
    fun `the version that kept its word spacing wins the tie-break`() {
        // «خدماترا» و «خدمات را» شکلِ متعارفِ یکسانی دارند، پس در یک خوشه می‌افتند.
        // بین آن‌ها باید نسخه‌ای برنده شود که فاصله را از دست نداده — حتی اگر
        // اطمینانِ نسخهٔ چسبیده بالاتر باشد.
        val result = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.SAUVOLA, lines(opening to 90, "بهترین خدماترا" to 90)),
                VariantLines(BinarizationMethod.OTSU, lines(opening to 80, "بهترین خدمات را" to 70)),
                VariantLines(BinarizationMethod.ADAPTIVE_MEAN, lines(opening to 78, "بهترین خدماترا" to 75)),
            ),
        )
        assertTrue("متن نهایی:\n${result.text}", result.text.contains("خدمات را"))
    }

    @Test
    fun `a single variant passes through untouched`() {
        val single = listOf(VariantLines(BinarizationMethod.SAUVOLA, lines(opening to 90, second to 80)))
        val result = LineVoter.combine(single)
        assertEquals("$opening\n$second", result.text)
        assertEquals(1, result.variantCount)
    }

    @Test
    fun `similarity ignores spacing and punctuation`() {
        assertEquals(1.0, LineVoter.similarity("خدمات را", "خدماترا"), 0.0001)
        assertEquals(1.0, LineVoter.similarity("سلام، دنیا!", "سلام دنیا"), 0.0001)
        assertTrue(LineVoter.similarity("متن کاملاً متفاوت اینجا", "چیز دیگری") < 0.55)
    }

    @Test
    fun `canonical form keeps only letters and digits`() {
        assertEquals("سلامدنیا۱۲", LineVoter.canonical("سلام، دنیا! ۱۲"))
    }

    // ─────────────── گروه‌بندیِ هندسی (باگ ۳) و حالتِ پرت (باگ ۲) ───────────────

    /** ساختِ خطوطِ مختصات‌دار: (متن، اطمینان، بالا). ارتفاع ثابتِ ۳۰ پیکسل. */
    private fun placed(vararg triples: Triple<String, Int, Int>): List<OcrLine> =
        triples.map { (text, confidence, top) ->
            val words = text.split(" ").count(String::isNotBlank)
            OcrLine(text, confidence, words, words, top = top, bottom = top + 30)
        }

    @Test
    fun `lines are ordered by Y even when a mode reads extra lines`() {
        // بازسازیِ دقیقِ چیزی که در گزارش اتفاق افتاد: یک حالت خطوطِ آشغالِ اضافی
        // درمی‌آورد، پس ترازِ مبتنی بر اندیس، خطِ وسطِ پاراگراف را جابه‌جا می‌کرد.
        val clean = placed(
            Triple("چون رفتار", 90, 100),
            Triple("سست‌کننده باشد مثلا این کار", 90, 300),
        )
        val noisy = placed(
            Triple("چون رفتار", 88, 102),
            Triple("ما به‌شدت تحت تأثیر نگرش ما است", 96, 200),
            Triple("سست‌کننده باشد مثلا این کار", 88, 298),
        )
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, clean),
                VariantLines(BinarizationMethod.SAUVOLA, clean),
                VariantLines(BinarizationMethod.ADAPTIVE_GAUSSIAN, noisy),
            ),
        )
        assertTrue("باید مسیر هندسی انتخاب می‌شد", vote.geometric)
        // خطِ میانی باید *بین* دو خطِ دیگر بنشیند، نه اولِ صفحه.
        // (مرکزِ هر خوشه میانگینِ اعضایش است، پس عددِ دقیق را ادعا نمی‌کنیم.)
        val centers = vote.lines.map { it.centerY }
        assertEquals(centers.sorted(), centers)
        assertEquals(3, centers.size)
        assertTrue("$centers", centers[0] in 100..145 && centers[1] in 200..245 && centers[2] in 300..345)
        assertEquals(
            listOf("چون رفتار", "ما به‌شدت تحت تأثیر نگرش ما است", "سست‌کننده باشد مثلا این کار"),
            vote.lines.map { it.text },
        )
    }

    @Test
    fun `the same row read twice by one mode counts as one vote`() {
        // نویز باعث می‌شود Tesseract یک ردیف را دوبار بخواند. با گروه‌بندیِ Y هر دو
        // نسخه در یک خوشه می‌افتند و به‌جای دو خطِ تکراری یک خط شمرده می‌شوند.
        val doubled = placed(
            Triple("ما به‌شدت تحت تأثیر نگرش ما است", 97, 200),
            Triple("ما به‌شدت تحت تاثیر نکرش ما استت", 96, 205),
        )
        val other = placed(Triple("ما به‌شدت تحت تأثیر نگرش ما است", 90, 202))
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.ADAPTIVE_GAUSSIAN, doubled),
                VariantLines(BinarizationMethod.OTSU, other),
                VariantLines(BinarizationMethod.SAUVOLA, other),
            ),
        )
        assertEquals(1, vote.lines.size)
        assertEquals(3, vote.lines.single().votes)
    }

    @Test
    fun `a confident junk line seen only by the outlier mode is dropped`() {
        val real = placed(Triple("متن واقعی صفحه که همه دیده‌اند", 88, 100))
        val noisy = placed(
            Triple("متن واقعی صفحه که همه دیده‌اند", 84, 103),
            // متنِ کاملاً سالم و با اطمینانِ ۹۶ — تنها ایرادش این است که فقط
            // حالتِ پرت آن را دیده. باید *به همین دلیل* رد شود، نه به دلیل کیفیت.
            Triple("این خطِ سالمی است ولی تنها حالت پرت آن را دید", 96, 400),
        )
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, real),
                VariantLines(BinarizationMethod.SAUVOLA, real),
                VariantLines(BinarizationMethod.ADAPTIVE_GAUSSIAN, noisy),
            ),
            outliers = setOf(BinarizationMethod.ADAPTIVE_GAUSSIAN),
        )
        assertEquals(1, vote.acceptedLines.size)
        val rejected = vote.rejectedLines.single()
        assertTrue(rejected.reason.orEmpty(), rejected.reason.orEmpty().contains("پرت"))
    }

    @Test
    fun `a line the outlier mode agrees on with others still counts`() {
        // حالتِ پرت حذف نشده: رأیش وقتی هم‌جهت با بقیه باشد کاملاً معتبر است.
        val real = placed(Triple("متن واقعی صفحه", 88, 100))
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, real),
                VariantLines(BinarizationMethod.SAUVOLA, real),
                VariantLines(BinarizationMethod.ADAPTIVE_GAUSSIAN, placed(Triple("متن واقعی صفحه", 99, 101))),
            ),
            outliers = setOf(BinarizationMethod.ADAPTIVE_GAUSSIAN),
        )
        assertEquals(1, vote.acceptedLines.size)
        assertEquals(3, vote.acceptedLines.single().votes)
    }

    @Test
    fun `a junk singleton is rejected by the quality gate even without an outlier verdict`() {
        val real = placed(Triple("متن واقعی صفحه که همه دیده‌اند", 88, 100))
        val noisy = placed(
            Triple("متن واقعی صفحه که همه دیده‌اند", 84, 103),
            // یک کلمهٔ واقعی دارد (پس از کفِ قاطع رد می‌شود) ولی بقیه‌اش آشغال است.
            Triple("ابر ۰۰ ی ل ۲,| ۳ و۱ ب ۱", 96, 400),
        )
        // بدونِ اعلامِ «پرت» — این بار باید دروازهٔ کیفیت (باگ ۱) جلویش را بگیرد.
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, real),
                VariantLines(BinarizationMethod.SAUVOLA, real),
                VariantLines(BinarizationMethod.ADAPTIVE_MEAN, noisy),
            ),
        )
        assertEquals(1, vote.acceptedLines.size)
        assertTrue(
            vote.rejectedLines.single().reason.orEmpty(),
            vote.rejectedLines.single().reason.orEmpty().contains("شبیه متن نیست"),
        )
    }

    @Test
    fun `a real singleton line still survives when nothing is wrong with it`() {
        val real = placed(Triple("متن واقعی صفحه که همه دیده‌اند", 88, 100))
        val extra = placed(
            Triple("متن واقعی صفحه که همه دیده‌اند", 84, 103),
            Triple("این خط را فقط یک حالت دید ولی متنِ سالمی است", 90, 400),
        )
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, real),
                VariantLines(BinarizationMethod.SAUVOLA, real),
                VariantLines(BinarizationMethod.ADAPTIVE_MEAN, extra),
            ),
        )
        assertEquals(2, vote.acceptedLines.size)
    }

    // ─────────── کفِ قاطع روی خطوطِ چندرأیی (باگ ۲) ───────────

    @Test
    fun `a short junk line is rejected even when several modes saw it`() {
        // «,سس» در گزارشِ واقعی: ۲ رأی و اطمینان ۹۰، و با این حال آشغال.
        val real = placed(Triple("متن واقعی صفحه که همه دیده‌اند", 88, 100))
        val withJunk = placed(
            Triple("متن واقعی صفحه که همه دیده‌اند", 86, 102),
            Triple(",سس", 90, 900),
        )
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, withJunk),
                VariantLines(BinarizationMethod.CLAHE_OTSU, withJunk),
                VariantLines(BinarizationMethod.SAUVOLA, real),
            ),
        )
        assertEquals(1, vote.acceptedLines.size)
        val rejected = vote.rejectedLines.single()
        assertEquals(2, rejected.votes)
        assertTrue(rejected.reason.orEmpty(), rejected.reason.orEmpty().contains("معنادار نیست"))
    }

    // ─────────── ادغامِ ردیفِ شکسته و گاردِ ضدتکرار (باگ ۱) ───────────

    @Test
    fun `one physical row split into two clusters is merged and voted on`() {
        // بازسازیِ گزارش: دو خوشه با فاصلهٔ مرکزِ ۹۹ پیکسل، در صفحه‌ای که فاصلهٔ
        // معمولِ دو خطِ پیاپی‌اش ~۳۲۰ پیکسل است. این‌ها یک خط‌اند.
        fun page(vararg extras: Triple<String, Int, Int>) = placed(
            Triple("خط اول صفحه است", 90, 100),
            Triple("خط دوم صفحه است", 90, 420),
            Triple("خط سوم صفحه است", 90, 740),
            *extras,
            Triple("خط پایانی صفحه است", 90, 1380),
        )
        val a = page(Triple("ما به‌شدت تحت تأثیر نگرش ما است", 95, 1060))
        val b = page(Triple("ما به‌شدت تحت تأثیر نکرش ما است", 96, 1159))

        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, a),
                VariantLines(BinarizationMethod.CLAHE_OTSU, a),
                VariantLines(BinarizationMethod.SAUVOLA, b),
            ),
        )
        // پنج خط، نه شش: دو خوشه یکی شده‌اند.
        assertEquals(5, vote.acceptedLines.size)
        val merged = vote.acceptedLines.first { it.text.startsWith("ما به‌شدت") }
        assertEquals(3, merged.votes)
        // نسخه‌ای که دو حالت رویش توافق دارند برنده است — یعنی املای درست.
        assertEquals("ما به‌شدت تحت تأثیر نگرش ما است", merged.text)
    }

    @Test
    fun `an adjacent near-duplicate line is dropped by the guard`() {
        // فاصلهٔ ۳۰۰ پیکسلی یعنی ادغامِ هندسی عمل نمی‌کند؛ اینجا فقط گاردِ ضدتکرار
        // می‌تواند جلوی تکرار را بگیرد.
        val a = placed(
            Triple("خط اول صفحه است", 90, 100),
            Triple("ما به‌شدت تحت تأثیر نگرش ما است", 95, 400),
            Triple("ما به‌شدت تحت تأثیر نکرش ما است", 96, 700),
            Triple("خط پایانی صفحه است", 90, 1000),
        )
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, a),
                VariantLines(BinarizationMethod.CLAHE_OTSU, a),
                VariantLines(BinarizationMethod.SAUVOLA, a),
            ),
        )
        assertEquals(3, vote.acceptedLines.size)
        val dropped = vote.rejectedLines.single()
        assertTrue(dropped.reason.orEmpty(), dropped.reason.orEmpty().contains("تکرارِ خطِ مجاور"))
    }

    @Test
    fun `two genuinely different neighbouring lines are both kept`() {
        val a = placed(
            Triple("خط اول صفحه است", 90, 100),
            Triple("متقاعدسازی بستگی دارد فرض کنید", 92, 400),
            Triple("ارائه می‌دهید و همچنین مهارت بسیار", 91, 700),
            Triple("خط پایانی صفحه است", 90, 1000),
        )
        val vote = LineVoter.combine(
            listOf(
                VariantLines(BinarizationMethod.OTSU, a),
                VariantLines(BinarizationMethod.CLAHE_OTSU, a),
                VariantLines(BinarizationMethod.SAUVOLA, a),
            ),
        )
        assertEquals(4, vote.acceptedLines.size)
    }

    @Test
    fun `folding makes look-alike letters compare equal`() {
        assertEquals(LineVoter.folded("نگرش"), LineVoter.folded("نكرش"))
        assertEquals(LineVoter.folded("همچنین"), LineVoter.folded("همجنین"))
    }
}
