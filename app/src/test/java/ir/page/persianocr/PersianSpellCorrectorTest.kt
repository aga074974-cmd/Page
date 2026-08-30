package ir.page.persianocr

import ir.page.persianocr.text.CorrectionKind
import ir.page.persianocr.text.PersianLexicon
import ir.page.persianocr.text.PersianSpellCorrector
import ir.page.persianocr.text.SetLexicon
import ir.page.persianocr.text.SpellOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اصلاح‌گرِ املایی — باگ ۳.
 *
 * همهٔ نمونه‌ها از خروجیِ واقعیِ همان گزارشِ دستگاه برداشته شده‌اند.
 */
class PersianSpellCorrectorTest {

    private val lexicon: PersianLexicon = SetLexicon(
        setOf(
            "نگرش", "همچنین", "چون", "ذهنی", "گویند", "تنها",
            "فروش", "خدمات", "خریدار", "ارزان", "هستند", "هرگز", "مهمترین",
            "امثال", "این", "خریداری", "یک", "دهید", "دانید", "متقاعد", "سازی",
            "راحتی", "به", "مهارت", "کار", "دار", "خرید", "میدان", "میلاد", "دان", "لاد",
            "کهنه", "شدت", "عنوان", "کند", "کندو", "می",
        ),
    )

    private fun fix(text: String) = PersianSpellCorrector.correct(text, lexicon).text

    // ─────────────── جانشینیِ حروفِ هم‌شکل ───────────────

    @Test
    fun `look-alike letters are repaired`() {
        assertEquals("نگرش", fix("نکرش"))
        assertEquals("همچنین", fix("همجنین"))
        assertEquals("چون", fix("جون"))
        assertEquals("ذهنی", fix("دهنی"))
    }

    // ─────────────── درجِ فاصلهٔ جاافتاده ───────────────

    @Test
    fun `a glued pair of real words is split`() {
        assertEquals("فروش و", fix("فروشو"))
        assertEquals("خدمات را", fix("خدماترا"))
        assertEquals("یک خریدار", fix("یکخریدار"))
        assertEquals("ارزان هستند", fix("ارزانهستند"))
        assertEquals("هرگز مهمترین", fix("هرگزمهمترین"))
        assertEquals("امثال آن", fix("امثالآن"))
    }

    @Test
    fun `a real compound word is never split`() {
        // هر دو پاره واژه‌اند («متقاعد» + «سازی»)، ولی «سازی» پایانهٔ ترکیب‌ساز است.
        assertEquals("متقاعدسازی", fix("متقاعدسازی"))
        // «خری» + «دار» هم همین‌طور — و اصلاً «خریدار» خودش در واژه‌نامه هست.
        assertEquals("خریدار", fix("خریدار"))
    }

    // ─────────────── ادغامِ فاصلهٔ اضافی ───────────────

    @Test
    fun `a word broken by a stray space is rejoined`() {
        assertEquals("این فرد", fix("ا ین فرد"))
        assertEquals("خریداری است", fix("خریدا ری است"))
    }

    @Test
    fun `two real words are never glued together`() {
        // هر دو واژه‌اند، پس هرچند «بهراحتی» هم شکلی دارد، ادغام نمی‌شوند.
        assertEquals("به راحتی", fix("به راحتی"))
        assertEquals("مهارت کار", fix("مهارت کار"))
    }

    // ─────────────── درجِ نیم‌فاصله ───────────────

    @Test
    fun `zwnj is inserted before a conjugated verb`() {
        assertEquals("می‌دهید", fix("میدهید"))
        assertEquals("می‌دانید", fix("میدانید"))
        assertEquals("نمی‌دانید", fix("نمیدانید"))
    }

    @Test
    fun `zwnj is not inserted into an ordinary noun`() {
        // این دقیقاً همان جایی است که قاعدهٔ سادهٔ «اگر بقیه واژه بود» خراب می‌کرد:
        // «دان» و «لاد» هر دو در واژه‌نامه‌اند ولی صرفِ فعل نیستند.
        assertEquals("میدان", fix("میدان"))
        assertEquals("میلاد", fix("میلاد"))
    }

    // ─────────────── محافظ‌ها ───────────────

    @Test
    fun `numbers are never touched`() {
        assertEquals("۸۰ درصد و *۸ درصد", fix("۸۰ درصد و *۸ درصد"))
        assertEquals("/۱۷", fix("/۱۷"))
    }

    @Test
    fun `line breaks punctuation and column spacing survive`() {
        val input = "نکرش و                باور /۱۷\nهمجنین، جون."
        assertEquals("نگرش و                باور /۱۷\nهمچنین، چون.", fix(input))
    }

    @Test
    fun `each stage can be switched off independently`() {
        val substitutionOnly = SpellOptions(split = false, merge = false, zwnj = false)
        assertEquals("فروشو", PersianSpellCorrector.correct("فروشو", lexicon, substitutionOnly).text)
        assertEquals("نگرش", PersianSpellCorrector.correct("نکرش", lexicon, substitutionOnly).text)
    }

    @Test
    fun `the report classifies every change`() {
        val report = PersianSpellCorrector.correct("نکرش فروشو میدهید ا ین", lexicon)
        assertEquals(1, report.countOf(CorrectionKind.SUBSTITUTION))
        assertEquals(1, report.countOf(CorrectionKind.SPLIT))
        assertEquals(1, report.countOf(CorrectionKind.ZWNJ))
        assertEquals(1, report.countOf(CorrectionKind.MERGE))
    }

    @Test
    fun `an empty lexicon disables the module entirely`() {
        assertEquals("نکرش فروشو", PersianSpellCorrector.correct("نکرش فروشو", PersianLexicon.EMPTY).text)
    }

    @Test
    fun `unknown words with no confident repair are left alone`() {
        val report = PersianSpellCorrector.correct("شددایمکه بهترىوارد", lexicon)
        assertEquals(0, report.count)
        assertTrue(report.unresolved >= 1)
    }

    // ─────────── محافظ‌هایی که روی متنِ واقعی لازم شدند ───────────

    @Test
    fun `a zwnj compound is never split at its zwnj`() {
        // «به» و «شدت» هر دو واژه‌اند، ولی نیم‌فاصله می‌گوید این *یک* کلمه است.
        assertEquals("به‌شدت", fix("به‌شدت"))
        assertEquals("به‌عنوان", fix("به‌عنوان"))
    }

    @Test
    fun `a zwnj compound can still be split somewhere else`() {
        // «کهبه‌راحتی» باید بشود «که به‌راحتی» — بریدن جایی جز خودِ نیم‌فاصله.
        assertEquals("که به‌راحتی", fix("کهبه‌راحتی"))
    }

    @Test
    fun `a one-letter conjunction is never absorbed into its neighbour`() {
        // «کندو» واژه است، پس بدونِ محافظ «می‌کند و» به «می‌کندو» چسبانده می‌شد.
        assertEquals("می‌کند و", fix("می‌کند و"))
    }

    @Test
    fun `two words joined by a zwnj do not become a word by themselves`() {
        // اگر هر ترکیبِ نیم‌فاصله‌ای «واژه» شمرده شود، اصلاح‌گر «کهبه‌راحتی» را با
        // یک جانشینی به «کهنه‌راحتی» تبدیل می‌کند. باید نشود.
        assertTrue(lexicon.contains("به‌راحتی"))
        assertTrue(!lexicon.contains("کهنه‌راحتی"))
    }
}
