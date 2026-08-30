package ir.page.persianocr

import ir.page.persianocr.ocr.LineQualityGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * دروازهٔ کیفیتِ متن — باگ ۱.
 *
 * همهٔ رشته‌های زیر عیناً از گزارشِ واقعیِ دستگاه برداشته شده‌اند؛ ستون «رأی» در
 * آن گزارش برای همه‌شان ۱/۵ بود، یعنی دقیقاً همان جایی که این دروازه اعمال می‌شود.
 */
class LineQualityGateTest {

    private fun reject(text: String) {
        val quality = LineQualityGate.assess(text)
        assertFalse("باید رد می‌شد ولی پذیرفته شد: ${quality.summary()} — «$text»", quality.looksLikeText)
    }

    private fun accept(text: String) {
        val quality = LineQualityGate.assess(text)
        assertTrue("باید پذیرفته می‌شد ولی رد شد (${quality.failure}) — «$text»", quality.looksLikeText)
    }

    @Test
    fun `real Persian text passes the gate`() {
        // خط ۱۲ گزارش: متنِ واقعی، با اطمینان ۹۷.
        accept("ما به‌شدت تحت تأثير نكرش ما است؛ اين نكزش مىتواند نیزوبخشو")
        // خط ۱۷ گزارش: همان خط، خواندنِ بدترِ همان حالت — باز هم متن است.
        accept("ما به‌شدت تحت تاتير نكرش ماراستت: این نكرشن: می‌تواند تي زوبحتن.و")
        accept("متقاعدسازى بستگی دارد. فرض كنيد شما بهترين محصولات يا خدماترا")
        accept("درفروش ۸۰/درصد به عوامل ذهنى و تنها ۲۰درصد به مهارت هاى فروشو")
    }

    @Test
    fun `character soup with high confidence is rejected`() {
        // خط ۲۱ گزارش: اطمینانِ ۸۳ داشت و با قانونِ قدیمی پذیرفته شده بود.
        reject("۰              ۰۰          ی   ل               2 ,| ۳    و۱ ب ١   \"١  >  0   ۰  را   ۰")
        // خط ۲۵ (اطمینان ۸۴)، خط ۴۲ (۷۵)، خط ۵۱ (۷۲) — همه بالای آستانهٔ اطمینان.
        reject("۰                 .0        ِ   حا ۰ :8 ی أل  - ِ    «   ء  ی ۶         به   2   - ۰")
        reject("٠.                 مو              ١   :     ۰        ۰۰    ۰   ل     ۰      ١")
        reject("۰        ۱              حا           رج  ۰              \"م ۳           ٍ   :  ل .۰")
    }

    @Test
    fun `lines that are mostly whitespace are rejected`() {
        // خط ۴۸ گزارش: اطمینان ۷۷ و عملاً هیچ حرفی در آن نیست.
        reject("١             ٠                     ۰                   :     ۰                   ۰")
        // خط ۴۹: شبیهِ کلمه است ولی رشته‌های فاصلهٔ ۹ و ۱۱ تایی دارد.
        reject("عا ت. اغلب. ما         به‌عتوان           ند شدمهارمكه")
    }

    @Test
    fun `a single word is not enough evidence`() {
        reject("فروش")
        reject("")
        reject("   ")
    }

    @Test
    fun `word-like tokens survive a zwnj`() {
        // نیم‌فاصله نباید رشتهٔ حروف را بشکند، وگرنه «می‌شود» کلمه شمرده نمی‌شد.
        accept("این متن می‌شود خوانده و نمی‌شود پاک کرد")
    }
}
