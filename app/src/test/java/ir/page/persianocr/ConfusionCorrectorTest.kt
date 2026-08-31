package ir.page.persianocr

import ir.page.persianocr.text.ConfusionCorrector
import ir.page.persianocr.text.PersianLexicon
import ir.page.persianocr.text.SetLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اصلاحِ کاراکتریِ مبتنی بر فرهنگِ واژگان — باگ ۴. */
class ConfusionCorrectorTest {

    private val lexicon: PersianLexicon = SetLexicon(
        setOf(
            "نگرش", "گوید", "میگوید", "همچنین", "انرژی", "پرسم", "میپرسم", "بستگی", "رفتار",
            "پول", "چه",
            "فروش", "مشتری", "کرم", "گرم", "خرید", "دارد",
        ),
    )

    private fun fix(text: String) = ConfusionCorrector.correct(text, lexicon).text

    @Test
    fun `the four error classes from the log are corrected`() {
        assertEquals("نگرش", fix("نکرش"))        // گ خوانده شده ک
        assertEquals("همچنین", fix("همجنین"))    // چ خوانده شده ج
        assertEquals("انرژی", fix("انرزی"))      // ژ خوانده شده ز
        assertEquals("می‌پرسم", fix("می‌پزسم"))   // ر خوانده شده ز
        assertEquals("می‌گوید", fix("می‌کوید"))   // با تحلیلِ صرفیِ پیشوندِ «می»
    }

    @Test
    fun `a word already in the lexicon is never touched`() {
        // «کرم» و «گرم» هر دو واژه‌اند؛ نباید هیچ‌کدام به دیگری تبدیل شود.
        assertEquals("کرم", fix("کرم"))
        assertEquals("گرم", fix("گرم"))
    }

    @Test
    fun `an ambiguous token is left alone`() {
        // «کاز» با یک جانشینی هم «گاز» می‌شود (ک→گ) و هم «کار» (ز→ر). وقتی دو
        // نامزدِ معتبر وجود دارد هیچ‌کدام انتخاب نمی‌شود.
        val ambiguous = SetLexicon(setOf("گاز", "کار"))
        val report = ConfusionCorrector.correct("کاز", ambiguous)
        assertEquals("کاز", report.text)
        assertEquals(0, report.changeCount)
        assertEquals(1, report.unresolved)
    }

    @Test
    fun `short tokens are never rewritten`() {
        assertEquals("کی", fix("کی"))
        assertEquals("زر", fix("زر"))
    }

    @Test
    fun `unknown words with no candidate survive intact`() {
        // «متقاعدسازی» در فرهنگِ این تست نیست و هیچ جایگزینِ هم‌شکلی هم ندارد.
        assertEquals("متقاعدسازی", fix("متقاعدسازی"))
    }

    @Test
    fun `punctuation spacing and line breaks are preserved`() {
        val input = "نکرش و باور.\nهمجنین   انرزی!"
        assertEquals("نگرش و باور.\nهمچنین   انرژی!", fix(input))
    }

    @Test
    fun `the report lists what changed`() {
        val report = ConfusionCorrector.correct("نکرش و همجنین", lexicon)
        assertEquals(2, report.changeCount)
        assertTrue(report.changes.contains("نکرش" to "نگرش"))
        assertTrue(report.changes.contains("همجنین" to "همچنین"))
    }

    @Test
    fun `an empty lexicon disables the module entirely`() {
        assertEquals("نکرش", ConfusionCorrector.correct("نکرش", PersianLexicon.EMPTY).text)
    }

    @Test
    fun `a candidate must be in the list itself, not reachable through affixes`() {
        // «رسوم» واژه است، پس «می + رسوم» از راهِ صرفی تأیید می‌گرفت و «میرشوم»
        // به «میرسوم» تبدیل می‌شد. با بررسیِ دقیق، این اتفاق نمی‌افتد.
        val withStem = SetLexicon(setOf("رسوم", "شوم"))
        assertEquals("میرشوم", ConfusionCorrector.correct("میرشوم", withStem).text)
    }

    @Test
    fun `the peh-yeh pair is corrected`() {
        // «یول» → «پول»: سه نقطهٔ زیرِ پ در برابر دو نقطهٔ ی.
        assertEquals("پول", fix("یول"))
    }

    @Test
    fun `two-letter tokens are corrected too`() {
        assertEquals("چه", fix("جه"))
        // ولی توکنی که خودش واژه است همچنان دست‌نخورده می‌ماند.
        assertEquals("که", fix("که"))
    }
}
