package ir.page.persianocr

import ir.page.persianocr.text.PersianTextNormalizer
import ir.page.persianocr.text.PersianTextOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های واحد پس‌پردازش فارسی. چون [PersianTextNormalizer] هیچ وابستگی اندرویدی
 * ندارد، این تست‌ها روی JVM و بدون شبیه‌ساز اجرا می‌شوند: `./gradlew :app:test`
 */
class PersianTextNormalizerTest {

    private val zwnj = PersianTextNormalizer.ZWNJ

    // ───────────────────── یکسان‌سازی حروف ─────────────────────

    @Test
    fun `arabic yeh and kaf become persian`() {
        // ي (U+064A) و ك (U+0643) → ی (U+06CC) و ک (U+06A9)
        val output = PersianTextNormalizer.normalise("كيف")
        assertEquals("کیف", output)
        assertFalse("Arabic yeh must be gone", output.contains('\u064A'))
        assertFalse("Arabic kaf must be gone", output.contains('\u0643'))
    }

    @Test
    fun `teh marbuta becomes heh`() {
        assertEquals("علاقه", PersianTextNormalizer.normalise("علاقة"))
    }

    @Test
    fun `diacritics and tatweel are stripped`() {
        // «مُحَمَّدـــی» با اعراب و کشیدگی
        val input = "مُحَمَّدـــی"
        assertEquals("محمدی", PersianTextNormalizer.normalise(input))
    }

    // ───────────────────────── ارقام ─────────────────────────

    @Test
    fun `arabic indic digits become persian digits`() {
        assertEquals("۱۲۳۴۵۶۷۸۹۰", PersianTextNormalizer.normalise("١٢٣٤٥٦٧٨٩٠"))
    }

    @Test
    fun `latin digits become persian digits when requested`() {
        assertEquals("۲۰۲۴", PersianTextNormalizer.normalise("2024"))
        assertEquals(
            "2024",
            PersianTextNormalizer.normalise("2024", PersianTextOptions(convertLatinDigits = false)),
        )
    }

    // ─────────────────────── نیم‌فاصله ───────────────────────

    @Test
    fun `verb prefix mi gets zwnj`() {
        assertEquals("می${zwnj}کند", PersianTextNormalizer.normalise("می کند"))
        assertEquals("نمی${zwnj}شود", PersianTextNormalizer.normalise("نمی شود"))
        assertEquals("او می${zwnj}رود.", PersianTextNormalizer.normalise("او می رود."))
    }

    @Test
    fun `plural and comparative suffixes get zwnj`() {
        assertEquals("کتاب${zwnj}ها", PersianTextNormalizer.normalise("کتاب ها"))
        assertEquals("خانه${zwnj}های قدیمی", PersianTextNormalizer.normalise("خانه های قدیمی"))
        assertEquals("بزرگ${zwnj}ترین", PersianTextNormalizer.normalise("بزرگ ترین"))
        assertEquals("کم${zwnj}تر", PersianTextNormalizer.normalise("کم تر"))
    }

    @Test
    fun `single letter word does not attract suffix`() {
        // «و تر» نباید به «و‌تر» تبدیل شود (تر در اینجا واژهٔ مستقل است)
        assertEquals("خشک و تر", PersianTextNormalizer.normalise("خشک و تر"))
    }

    @Test
    fun `already correct zwnj is preserved`() {
        val correct = "می${zwnj}کند و کتاب${zwnj}ها"
        assertEquals(correct, PersianTextNormalizer.normalise(correct))
    }

    @Test
    fun `zwnj never keeps surrounding spaces`() {
        assertEquals("می${zwnj}کند", PersianTextNormalizer.normalise("می $zwnj کند"))
    }

    // ────────────────────── تمیزکاری فاصله ──────────────────────

    @Test
    fun `extra spaces collapse`() {
        assertEquals("سلام دنیا", PersianTextNormalizer.normalise("سلام     دنیا"))
    }

    @Test
    fun `space before punctuation is removed`() {
        assertEquals("سلام، دنیا.", PersianTextNormalizer.normalise("سلام ، دنیا ."))
    }

    @Test
    fun `blank lines collapse to one`() {
        assertEquals("خط اول\n\nخط دوم", PersianTextNormalizer.normalise("خط اول\n\n\n\n   \nخط دوم"))
    }

    @Test
    fun `blank input yields empty string`() {
        assertEquals("", PersianTextNormalizer.normalise("   \n\n  "))
    }

    @Test
    fun `lam alef ligature is decomposed by nfkc`() {
        // ﻻ (U+FEFB) باید به «لا» تبدیل شود
        val output = PersianTextNormalizer.normalise("اﻻم")
        assertFalse(output.contains('ﻻ'))
        assertTrue(output.contains("لا"))
    }

    // ── نگهبان‌های نیم‌فاصله ─────────────────────────────────────────────────
    // این آزمون‌ها رفتاری را میخکوب می‌کنند که باید *اتفاق نیفتد*: چسباندنِ واژه‌های
    // مستقل به واژهٔ پیشین. در گزارش‌ها «خدماترا» و «فروشو» دیده شده بود؛ آن‌ها از
    // خروجی خام Tesseract می‌آیند، نه از اینجا — و این آزمون‌ها تضمین می‌کنند که
    // هرگز از اینجا هم نیایند.

    @Test
    fun `object marker ra is never glued to the previous word`() {
        assertEquals("بهترین خدمات را", PersianTextNormalizer.normalise("بهترین خدمات را"))
    }

    @Test
    fun `conjunction and relative pronoun are never glued`() {
        assertEquals("فروش و بازاریابی", PersianTextNormalizer.normalise("فروش و بازاریابی"))
        assertEquals("کتابی که خواندم", PersianTextNormalizer.normalise("کتابی که خواندم"))
    }

    @Test
    fun `common prepositions are never glued`() {
        val input = "کتاب در قفسه به دوست از راه با هم"
        assertEquals(input, PersianTextNormalizer.normalise(input))
    }

    @Test
    fun `a function word never takes a suffix`() {
        // بدون نگهبان، الگو «که» را یک واژهٔ دوحرفی می‌دید و «که‌تر» می‌ساخت.
        assertEquals("که تر", PersianTextNormalizer.normalise("که تر"))
        assertEquals("این ها", PersianTextNormalizer.normalise("این ها"))
    }

    @Test
    fun `indefinite ey attaches only after a word ending in heh`() {
        assertEquals("خانه\u200Cای", PersianTextNormalizer.normalise("خانه ای"))
        // «ای» ندایی پیش از یک اسم باید دست‌نخورده بماند.
        assertEquals("ای دوست", PersianTextNormalizer.normalise("ای دوست"))
        assertEquals("کتاب ای", PersianTextNormalizer.normalise("کتاب ای"))
    }
}
