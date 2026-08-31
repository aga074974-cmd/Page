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

    /**
     * واژه‌نامهٔ تستی با بازهٔ بسامد — چون تصمیم‌های اصلاح‌گر به بسامد وابسته‌اند:
     * شکستن دستِ‌کم یک پارهٔ پرتکرار می‌خواهد، و واژهٔ کم‌تکرار جای خود را به
     * نامزدِ بسیار پرتکرارتر می‌دهد.
     */
    private val lexicon: PersianLexicon = SetLexicon(
        mapOf(
            // واژه‌های رایج (بازهٔ بالا) — می‌توانند پارهٔ یک شکست باشند.
            "نگرش" to 8, "همچنین" to 8, "چون" to 9, "ذهنی" to 8, "گویند" to 8,
            "گوید" to 8, "میگوید" to 4,
            "تنها" to 9, "فروش" to 8, "خدمات" to 8, "خریدار" to 8, "ارزان" to 8,
            "هستند" to 9, "هرگز" to 8, "مهمترین" to 7, "امثال" to 8, "این" to 9,
            "خریداری" to 7, "یک" to 9, "دهید" to 8, "دانید" to 8, "متقاعد" to 7,
            "سازی" to 7, "راحتی" to 8, "به" to 9, "مهارت" to 8, "کار" to 9,
            "دار" to 7, "خرید" to 8, "میدان" to 8, "دان" to 7, "شدت" to 8,
            "عنوان" to 8, "کند" to 8, "کندو" to 5, "می" to 9, "جواب" to 8,
            "کهنه" to 6, "شده" to 8, "مکه" to 4,
            // واژه‌های کم‌تکرار — دقیقاً همان‌هایی که در متنِ کتاب خطای OCR اند.
            "یول" to 4, "پول" to 9, "میلاد" to 5, "لاد" to 4,
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

    // ─────────── قاعده‌هایی که از متنِ واقعی درآمدند ───────────

    @Test
    fun `a token gets more than one fix in a row`() {
        // «میکوید» باید هم جانشینی بگیرد و هم نیم‌فاصله. نسخهٔ اول فقط یکی می‌داد
        // و «میگوید» را بی‌نیم‌فاصله رها می‌کرد.
        assertEquals("می‌گوید", fix("میکوید"))
    }

    @Test
    fun `a split needs at least one common part`() {
        // «متر» و «شوم» هر دو واژه‌اند ولی هیچ‌کدام پرتکرار نیست؛ در واژه‌نامه‌ای
        // بزرگ تقریباً هر آشغالی به دو واژهٔ کم‌تکرار می‌شکند.
        val rare = SetLexicon(mapOf("متر" to 6, "شوم" to 5))
        assertEquals("مترشوم", PersianSpellCorrector.correct("مترشوم", rare).text)
    }

    @Test
    fun `a rare word yields to a far more common candidate`() {
        // «یول» در فهرست هست ولی با بسامدِ ۴، و «پول» بسامدِ ۹ دارد.
        assertEquals("پول", fix("یول"))
        // ولی واژهٔ رایج دست‌نخورده می‌ماند، هرچند نامزد داشته باشد.
        assertEquals("کار", fix("کار"))
    }

    @Test
    fun `nominal zwnj only reaches uncommon glued forms`() {
        assertEquals("جواب‌ها", fix("جوابها"))
        // «تنها» بسامدِ ۹ دارد: خودش واژه است، نه «تن + ها».
        assertEquals("تنها", fix("تنها"))
    }

    @Test
    fun `splitting beats substitution only when both parts are very common`() {
        // «یاممکن»: جانشینی هدفِ کم‌تکرار دارد، شکستن دو پارهٔ پرتکرار.
        val both = SetLexicon(mapOf("یا" to 9, "ممکن" to 7, "ناممکن" to 4, "هم" to 9, "جنین" to 5, "همچنین" to 7))
        assertEquals("یا ممکن", PersianSpellCorrector.correct("یاممکن", both).text)
        // «همجنین»: پارهٔ «جنین» کم‌تکرار است، پس جانشینی برنده می‌شود.
        assertEquals("همچنین", PersianSpellCorrector.correct("همجنین", both).text)
    }
}
