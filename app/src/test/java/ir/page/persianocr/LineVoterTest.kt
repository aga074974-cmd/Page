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
}
